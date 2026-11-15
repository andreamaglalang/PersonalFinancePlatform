package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthApiClient
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import java.time.Instant
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
}

/** Tokens and user data never leave this repository through its public interface. No disk writes. */
class InMemoryAuthRepository internal constructor(private val api: AuthApi) : AuthRepository {
    constructor() : this(AuthApiClient())

    private val lock = Any()
    private var session: AuthPayload? = null
    private var generation = 0L
    private var loginInProgress = false

    override suspend fun login(email: String, password: String): AuthResult {
        val attempt = synchronized(lock) {
            if (loginInProgress) return AuthResult.Failure(AuthFailure.Unavailable)
            loginInProgress = true
            session = null
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
            session.also { session = null }
        } ?: return AuthResult.Success
        return when (val result = api.logout(previous.refreshToken)) {
            is ApiResult.Success -> AuthResult.Success
            is ApiResult.Failure -> AuthResult.Failure(result.reason)
        }
    }

    override fun hasActiveSession(): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        if (!Instant.now().isBefore(current.accessExpires)) {
            session = null
            ++generation
            false
        } else true
    }

    override fun clearSession() {
        synchronized(lock) { ++generation; session = null }
    }
}
