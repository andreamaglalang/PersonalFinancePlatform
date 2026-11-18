package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.ApiConfiguration
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthApiClient
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Job
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
}

/** Tokens and user data never leave this repository through its public interface. No disk writes. */
class InMemoryAuthRepository internal constructor(
    private val api: AuthApi,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
) : AuthRepository {
    constructor(
        configuration: ApiConfiguration = ApiConfiguration.unconfigured(),
        diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    ) : this(AuthApiClient(configuration, diagnostics), diagnostics = diagnostics)

    val diagnosticId = AuthDiagnosticIds.next()

    private fun record(event: AuthDiagnosticEvent) = diagnostics.record(event, diagnosticId, null)

    private fun clearLocalSession() {
        financeLifetime?.cancel()
        financeLifetime = null
        session = null
        refreshAttempt = null
        refreshBlocked = false
        record(AuthDiagnosticEvent.LOCAL_SESSION_CLEARED)
    }

    private val lock = Any()
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
            ++generation
        }
        try {
            val result = api.login(email, password)
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                if (attempt != generation) return@synchronized AuthResult.Failure(AuthFailure.SessionExpired)
                when (result) {
                    is ApiResult.Success -> {
                        session = result.value
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
        if (!clock.instant().isBefore(current.accessExpires)) {
            record(AuthDiagnosticEvent.ACCESS_TOKEN_EXPIRED)
            clearLocalSession()
            record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
            ++generation
            false
        } else true
    }

    /**
     * Single-flight, proactive rotation. At most one request per refresh token; never retried automatically.
     * Any outcome other than a verified success blocks further refresh for this session, which then ends at
     * its original access expiry. Results for a session that is no longer current are never applied.
     */
    override suspend fun refreshIfDue() {
        val attempt = Any()
        val (version, previous) = synchronized(lock) {
            val current = session ?: return
            if (refreshBlocked || refreshAttempt != null) return
            val now = clock.instant()
            // Expiry itself is handled by hasActiveSession(); refresh never revives an expired session.
            if (!now.isBefore(current.accessExpires)) return
            if (Duration.between(now, current.accessExpires) >= REFRESH_WINDOW) return
            if (!now.isBefore(current.refreshExpires)) {
                refreshBlocked = true
                record(AuthDiagnosticEvent.REFRESH_REJECTED)
                return
            }
            refreshAttempt = attempt
            generation to current
        }
        val result = try {
            api.refresh(previous.refreshToken)
        } catch (cancelled: CancellationException) {
            // The request may have reached the server: outcome unknown, so the token is never reused.
            synchronized(lock) { settleFailure(attempt, version, previous, AuthFailure.Unavailable) }
            throw cancelled
        } catch (_: Exception) {
            ApiResult.Failure(AuthFailure.Unavailable)
        }
        val orphan = synchronized(lock) {
            when (result) {
                is ApiResult.Success -> settleSuccess(attempt, version, previous, result.value)
                is ApiResult.Failure -> { settleFailure(attempt, version, previous, result.reason); null }
            }
        }
        if (orphan != null) revokeOrphan(orphan)
    }

    private fun isCurrentAttempt(attempt: Any, version: Long, previous: AuthPayload): Boolean {
        val owns = refreshAttempt === attempt
        if (owns) refreshAttempt = null
        return owns && generation == version && session === previous
    }

    private fun settleFailure(attempt: Any, version: Long, previous: AuthPayload, reason: AuthFailure) {
        if (isCurrentAttempt(attempt, version, previous)) refreshBlocked = true
        record(if (reason == AuthFailure.SessionExpired || reason == AuthFailure.InvalidInput)
            AuthDiagnosticEvent.REFRESH_REJECTED else AuthDiagnosticEvent.REFRESH_NOT_COMPLETED)
    }

    /** Returns credentials that belong only to an abandoned session (safe to revoke), otherwise null. */
    private fun settleSuccess(attempt: Any, version: Long, previous: AuthPayload, fresh: AuthPayload): AuthPayload? {
        val sameUser = fresh.user.id == previous.user.id
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
        session = AuthPayload(fresh.accessToken, fresh.refreshToken, fresh.accessExpires, fresh.refreshExpires, previous.user)
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
        if (!hasActiveSession()) return@synchronized FinanceResult.Failure(FinanceFailure.SessionExpired)
        val captured = session!!
        val version = generation
        val lifetime = financeLifetime!!
        val userId = captured.user.id
        // Validity is bound to the login (generation, lifetime, user), not to one access token, so a
        // successful rotation does not invalidate requests already in flight. Logout, clear, expiry and
        // account switches still change the generation or cancel the lifetime.
        FinanceResult.Success(FinanceSessionLease(captured.accessToken, lifetime) {
            synchronized(lock) {
                generation == version && financeLifetime === lifetime && session?.user?.id == userId && hasActiveSession()
            }
        })
    }

    /** Non-secret display metadata only; never returns profile or token objects. */
    internal fun accountBaseCurrency(): String? = synchronized(lock) {
        if (hasActiveSession()) session?.user?.baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } else null
    }

    override fun clearSession() {
        synchronized(lock) { ++generation; clearLocalSession() }
    }

    private companion object {
        val REFRESH_WINDOW: Duration = Duration.ofMinutes(2)
    }
}
