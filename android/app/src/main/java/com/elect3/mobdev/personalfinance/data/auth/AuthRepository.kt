package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.ApiConfiguration
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthApiClient
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.RequestDelivery
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.elect3.mobdev.personalfinance.data.remote.FinanceSessionLease
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val reason: AuthFailure) : AuthResult
}

interface AuthRepository {
    suspend fun login(email: String, password: String): AuthResult
    /** Local state is cleared before the revocation request. Failure means revocation is unconfirmed. */
    suspend fun logout(): AuthResult
    fun hasActiveSession(): Boolean
    fun clearSession()
    /** Proactively rotates credentials shortly before access expiry. Default: no refresh capability. */
    suspend fun refreshIfDue() = Unit
    val supportsRestoration: Boolean get() = false
    suspend fun restoreSession(): SessionRestoration = SessionRestoration.None
    fun hasRecoverableSession(): Boolean = hasActiveSession()
    fun sessionFailure(): AuthFailure = AuthFailure.SessionExpired
    fun onUiDetached() = clearSession()
}

/** Credentials stay within the session owner and its optional protected store; UI never receives them. */
class InMemoryAuthRepository internal constructor(
    private val api: AuthApi,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    private val store: SessionStore = NoSessionStore,
) : AuthRepository {
    constructor(
        configuration: ApiConfiguration = ApiConfiguration.unconfigured(),
        diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    ) : this(AuthApiClient(configuration, diagnostics), diagnostics = diagnostics)

    val diagnosticId = AuthDiagnosticIds.next()

    private fun record(event: AuthDiagnosticEvent) = diagnostics.record(event, diagnosticId, null)

    override val supportsRestoration get() = store.persistent
    private var lastFailure = AuthFailure.SessionExpired
    private var nextRefreshAt = java.time.Instant.MIN
    private class AccessState { var rejected = false }
    private var accessState = AccessState()
    private var forceRefresh = false
    override fun sessionFailure(): AuthFailure = synchronized(lock) { lastFailure }
    override fun onUiDetached() { if (!store.persistent) clearSession() }
    override fun hasRecoverableSession(): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        if (!clock.instant().isBefore(current.refreshExpires)) lastFailure = AuthFailure.SessionExpired
        (!accessState.rejected && clock.instant().isBefore(current.accessExpires)) || (!refreshBlocked && clock.instant().isBefore(current.refreshExpires))
    }
    override suspend fun restoreSession(): SessionRestoration = synchronized(lock) {
        if (session != null) return@synchronized if (hasRecoverableSession()) SessionRestoration.Restored else SessionRestoration.Failed(lastFailure)
        val saved = try { store.read() } catch (_: Exception) { lastFailure = AuthFailure.SecureStorage; return@synchronized SessionRestoration.Failed(lastFailure) }
            ?: return@synchronized SessionRestoration.None
        if (!clock.instant().isBefore(saved.payload.refreshExpires)) {
            session = saved.payload
            clearLocalSession(); lastFailure = AuthFailure.SessionExpired
            return@synchronized SessionRestoration.Failed(lastFailure)
        }
        session = saved.payload; financeLifetime = Job(); accessState = AccessState()
        refreshBlocked = saved.renewalUncertain
        lastFailure = if (refreshBlocked) AuthFailure.RenewalUnconfirmed else AuthFailure.SessionExpired
        if (hasRecoverableSession()) SessionRestoration.Restored else SessionRestoration.Failed(lastFailure)
    }
    private fun persist(payload: AuthPayload, uncertain: Boolean = false): Boolean = try {
        val expected = session
        if (expected == null) { store.write(StoredSession(payload, uncertain)); true }
        else if (store.writeIfOwned(expected, StoredSession(payload, uncertain))) true
        else { lastFailure = AuthFailure.SessionExpired; refreshBlocked = true; false }
    } catch (_: Exception) { lastFailure = AuthFailure.SecureStorage; refreshBlocked = true; false }
    private fun clearLocalSession() {
        try { session?.let { store.clearIfOwned(it) } } catch (_: Exception) { lastFailure = AuthFailure.SecureStorage }
        forceRefresh = false
        nextRefreshAt = java.time.Instant.MIN
        financeLifetime?.cancel()
        financeLifetime = null
        session = null
        refreshAttempt = null
        refreshBlocked = false
        accessExpiryRecorded = false
        record(AuthDiagnosticEvent.LOCAL_SESSION_CLEARED)
    }

    private val lock = Any()
    private val refreshMutex = if (store.persistent) processRefreshMutex else Mutex()
    private var accessExpiryRecorded = false
    private var session: AuthPayload? = null
    private var financeLifetime: Job? = null
    private var generation = 0L
    private var loginInProgress = false
    /** Owner token of the single in-flight refresh for the current session; null when none. */
    private var refreshAttempt: Any? = null
    /** Set after any refresh that did not succeed; the session then ends at its original expiry. */
    private var refreshBlocked = false

    override suspend fun login(email: String, password: String): AuthResult {
        val attempt = synchronized(lock) {
            if (loginInProgress) return AuthResult.Failure(AuthFailure.Unavailable)
            loginInProgress = true
            clearLocalSession()
            try { store.clear() } catch (_: Exception) { loginInProgress = false; return AuthResult.Failure(AuthFailure.SecureStorage) }
            ++generation
        }
        try {
            val result = api.login(email, password)
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                if (attempt != generation) return@synchronized AuthResult.Failure(AuthFailure.SessionExpired)
                when (result) {
                    is ApiResult.Success -> {
                        if (!persist(result.value)) return@synchronized AuthResult.Failure(AuthFailure.SecureStorage)
                        lastFailure = AuthFailure.SessionExpired
                        session = result.value
                        accessState = AccessState()
                        financeLifetime = Job()
                        AuthResult.Success
                    }
                    is ApiResult.Failure -> AuthResult.Failure(result.reason)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(lock) { loginInProgress = false }
        }
    }

    override suspend fun logout(): AuthResult {
        val previous = synchronized(lock) {
            ++generation
            session.also { clearLocalSession() }
        } ?: run {
            record(AuthDiagnosticEvent.LOGOUT_NO_LOCAL_SESSION)
            record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
            return AuthResult.Success
        }
        return when (val result = api.logout(previous.refreshToken)) {
            is ApiResult.Success -> AuthResult.Success
            is ApiResult.Failure -> AuthResult.Failure(result.reason)
        }
    }

    override fun hasActiveSession(): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        if (clock.instant().isBefore(current.accessExpires)) return@synchronized true
        if (!accessExpiryRecorded) {
            accessExpiryRecorded = true
            record(AuthDiagnosticEvent.ACCESS_TOKEN_EXPIRED)
            record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
        }
        // Access expiry denies access, but does not discard an unused, eligible refresh token.
        if (refreshAttempt == null && (refreshBlocked || !clock.instant().isBefore(current.refreshExpires))) {
            clearLocalSession()
            ++generation
        }
        false
    }

    /** Waiters share the owner's result. Cancellation while waiting never consumes a refresh token. */
    override suspend fun refreshIfDue() {
        val version = synchronized(lock) { generation }
        refreshMutex.withLock {
            if (synchronized(lock) { generation == version }) refreshOnce()
        }
    }

    /** Only proven-unsent or rate-limited refreshes may retry. Uncertain outcomes always block reuse. */
    private suspend fun refreshOnce() {
        val attempt = Any()
        val (version, previous) = synchronized(lock) {
            var current = session ?: return
            if (store.persistent) {
                val saved = try { store.read() } catch (_: Exception) { lastFailure = AuthFailure.SecureStorage; refreshBlocked = true; return }
                if (saved == null || saved.payload.user.id != current.user.id || !saved.payload.user.email.equals(current.user.email, true) || saved.payload.user.baseCurrency != current.user.baseCurrency) {
                    lastFailure = AuthFailure.SessionExpired; ++generation; clearLocalSession(); return
                }
                if (saved.renewalUncertain) { refreshBlocked = true; lastFailure = AuthFailure.RenewalUnconfirmed; return }
                if (saved.payload.refreshToken != current.refreshToken) {
                    current = saved.payload; session = current; accessState = AccessState(); forceRefresh = false; refreshBlocked = false
                }
            }
            if (refreshBlocked || refreshAttempt != null) return
            val now = clock.instant()
            if ((!forceRefresh && Duration.between(now, current.accessExpires) >= REFRESH_WINDOW) || now.isBefore(nextRefreshAt)) return
            if (!now.isBefore(current.refreshExpires)) {
                refreshBlocked = true
                record(AuthDiagnosticEvent.REFRESH_REJECTED)
                return
            }
            // Crash-safe journal: a token with an uncertain dispatch must never be replayed after restart.
            if (!persist(current, uncertain = true)) return
            refreshAttempt = attempt
            generation to current
        }
        val result = try {
            api.refresh(previous.refreshToken)
        } catch (cancelled: CancellationException) {
            synchronized(lock) { settleFailure(attempt, version, previous, AuthFailure.Unavailable) }
            throw cancelled
        } catch (_: Exception) {
            ApiResult.Failure(AuthFailure.Unavailable)
        }
        val orphan = synchronized(lock) {
            when (result) {
                is ApiResult.Success -> settleSuccess(attempt, version, previous, result.value)
                is ApiResult.Failure -> { settleFailure(attempt, version, previous, result.reason, result.delivery); null }
            }
        }
        if (orphan != null) revokeOrphan(orphan)
    }

    /** All protected transports wait here. Mutations are never replayed; GET may recover one confirmed 401. */
    internal suspend fun readyFinanceSession(): FinanceResult<FinanceSessionLease> {
        val version = synchronized(lock) { generation }
        refreshIfDue()
        return synchronized(lock) {
            if (generation != version) FinanceResult.Failure(FinanceFailure.SessionExpired) else financeSession()
        }
    }

    private fun isCurrentAttempt(attempt: Any, version: Long, previous: AuthPayload): Boolean {
        val owns = refreshAttempt === attempt
        if (owns) refreshAttempt = null
        return owns && generation == version && session?.refreshToken == previous.refreshToken && session?.user?.id == previous.user.id
    }

    private fun settleFailure(attempt: Any, version: Long, previous: AuthPayload, reason: AuthFailure, delivery: RequestDelivery = RequestDelivery.Unknown) {
        if (isCurrentAttempt(attempt, version, previous)) {
            val safeToRetry = delivery == RequestDelivery.NotSent || (delivery == RequestDelivery.Rejected && reason == AuthFailure.RateLimited)
            if (safeToRetry) {
                refreshBlocked = false
                lastFailure = reason
                nextRefreshAt = clock.instant().plusSeconds(if (reason == AuthFailure.RateLimited) 60 else 10)
                persist(session ?: previous)
            } else {
                refreshBlocked = true
                lastFailure = if (reason in setOf(AuthFailure.SessionExpired, AuthFailure.InvalidInput, AuthFailure.NotConfigured)) AuthFailure.SessionExpired else AuthFailure.RenewalUnconfirmed
                if (reason == AuthFailure.SessionExpired || reason == AuthFailure.InvalidInput) { ++generation; clearLocalSession() }
            }
        }
        record(if (reason == AuthFailure.SessionExpired || reason == AuthFailure.InvalidInput)
            AuthDiagnosticEvent.REFRESH_REJECTED else AuthDiagnosticEvent.REFRESH_NOT_COMPLETED)
    }

    /** Returns credentials that belong only to an abandoned session (safe to revoke), otherwise null. */
    private fun settleSuccess(attempt: Any, version: Long, previous: AuthPayload, fresh: AuthPayload): AuthPayload? {
        val sameUser = fresh.user.id == previous.user.id && fresh.user.email.equals(previous.user.email, true) && fresh.user.baseCurrency == previous.user.baseCurrency
        if (!isCurrentAttempt(attempt, version, previous)) {
            // Logout, clear, expiry or a new login happened meanwhile. The rotated token descends only from
            // the abandoned session's token; it is revoked best-effort and never applied.
            record(AuthDiagnosticEvent.REFRESH_RESULT_DISCARDED)
            return if (sameUser) fresh else null
        }
        if (!sameUser) {
            // Identity mismatch: fail closed. Ownership of the returned token is unproven, so no cleanup.
            record(AuthDiagnosticEvent.REFRESH_RESULT_DISCARDED)
            ++generation
            clearLocalSession()
            return null
        }
        if (!clock.instant().isBefore(fresh.accessExpires)) {
            record(AuthDiagnosticEvent.REFRESH_RESULT_DISCARDED)
            refreshBlocked = true
            return null
        }
        // Same login generation, same lifetime Job and same user identity: in-flight requests stay valid.
        val replacement = AuthPayload(fresh.accessToken, fresh.refreshToken, fresh.accessExpires, fresh.refreshExpires, session?.user ?: previous.user)
        if (!persist(replacement)) return if (lastFailure == AuthFailure.SessionExpired) fresh else null
        lastFailure = AuthFailure.SessionExpired
        accessExpiryRecorded = false
        forceRefresh = false
        nextRefreshAt = java.time.Instant.MIN
        accessState = AccessState()
        session = replacement
        record(AuthDiagnosticEvent.REFRESH_SUCCEEDED)
        return null
    }

    private suspend fun revokeOrphan(orphan: AuthPayload) {
        try {
            api.logout(orphan.refreshToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort only; the outcome is not reported as a confirmed revocation.
        }
    }

    /** Internal capability only: UI never receives credentials or session metadata. */
    internal fun financeSession(): FinanceResult<FinanceSessionLease> = synchronized(lock) {
        if (session == null) return@synchronized FinanceResult.Failure(FinanceFailure.MissingSession)
        if (!hasActiveSession() || accessState.rejected) return@synchronized financeAccessResult()
        val captured = session!!
        val version = generation
        val lifetime = financeLifetime!!
        val userId = captured.user.id
        val capturedAccess = accessState
        // Validity is bound to the login (generation, lifetime, user), not to one access token, so a
        // successful rotation does not invalidate requests already in flight. Logout, clear, expiry and
        // account switches still change the generation or cancel the lifetime.
        FinanceResult.Success(FinanceSessionLease(captured.accessToken, lifetime, canAuthorize = { clock.instant().isBefore(captured.accessExpires) && synchronized(lock) { !capturedAccess.rejected } },
            onUnauthorized = { synchronized(lock) { capturedAccess.rejected = true; if (accessState === capturedAccess) forceRefresh = true } }) {
            synchronized(lock) {
                // An already dispatched response belongs to the login, not the old token's expiry.
                // Authorization separately checks the captured token's expiry before any new dispatch.
                hasActiveSession()
                generation == version && financeLifetime === lifetime && session?.user?.id == userId
            }
        })
    }

    /** Non-secret display metadata only; never returns profile or token objects. */
    /** Name, email and base currency of the active session's user (as returned at sign-in); null without one. */
    internal fun accountProfile(): AccountProfile? = synchronized(lock) {
        hasActiveSession() // May clear an ineligible session; retained metadata grants no API access.
        session?.user?.let { AccountProfile(it.fullName.trim().takeIf(String::isNotEmpty), it.email.trim().takeIf(String::isNotEmpty),
            it.baseCurrency.takeIf { c -> c.matches(Regex("[A-Z]{3}")) }) }
    }

    /** Active account's numeric ID, used only to scope device-only display preferences. */
    internal fun accountId(): Int? = synchronized(lock) { hasActiveSession(); session?.user?.id }

    internal fun accountBaseCurrency(): String? = synchronized(lock) {
        hasActiveSession()
        session?.user?.baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) }
    }

    internal fun financeAccessFailure(): FinanceFailure = synchronized(lock) {
        if (!hasRecoverableSession()) FinanceFailure.SessionExpired else when(lastFailure) {
            AuthFailure.Timeout -> FinanceFailure.Timeout
            AuthFailure.SecureConnection -> FinanceFailure.SecureConnection
            AuthFailure.RateLimited -> FinanceFailure.RateLimited
            else -> FinanceFailure.Unavailable
        }
    }
    internal fun financeAccessResult(): FinanceResult.Failure = synchronized(lock) {
        FinanceResult.Failure(financeAccessFailure(), isConnectivityFailure = hasRecoverableSession() && lastFailure == AuthFailure.ConnectionFailed)
    }
    internal fun sessionMarker(): Any? = synchronized(lock) { if (session == null) null else financeLifetime }
    internal fun clearIfCurrent(marker: Any?) = synchronized(lock) {
        if (marker != null && marker === financeLifetime) { ++generation; clearLocalSession() }
    }
    internal fun updateDisplayName(marker: Any?, name: String) = synchronized(lock) {
        if (marker != null && marker === financeLifetime) session = session?.let {
            AuthPayload(it.accessToken, it.refreshToken, it.accessExpires, it.refreshExpires, AuthUser(it.user.id,name,it.user.email,it.user.baseCurrency,it.user.roles)).also { value -> persist(value, refreshBlocked || refreshAttempt != null) }
        }
    }

    override fun clearSession() {
        synchronized(lock) { ++generation; clearLocalSession() }
    }

    private companion object {
        val processRefreshMutex = Mutex()
        val REFRESH_WINDOW: Duration = Duration.ofMinutes(2)
    }
}

/** Display-only profile from the sign-in response. Never contains tokens. */
data class AccountProfile(val fullName: String?, val email: String?, val baseCurrency: String?)
