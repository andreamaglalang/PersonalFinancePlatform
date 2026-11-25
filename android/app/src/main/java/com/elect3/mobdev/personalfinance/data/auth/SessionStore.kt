package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure

/** A durable pending marker is written BEFORE refresh dispatch. It must never be replayed after a crash. */
internal class StoredSession(val payload: AuthPayload, val renewalUncertain: Boolean = false) {
    override fun toString() = "StoredSession([redacted])"
}
internal interface SessionStore {
    val persistent: Boolean get() = true
    fun read(): StoredSession?
    fun write(value: StoredSession)
    fun clear()
    fun writeIfOwned(expected: AuthPayload, value: StoredSession): Boolean {
        if (read()?.payload?.refreshToken != expected.refreshToken) return false
        write(value); return true
    }
    fun clearIfOwned(expected: AuthPayload) {
        if (read()?.payload?.refreshToken == expected.refreshToken) clear()
    }
}
internal object NoSessionStore : SessionStore {
    override val persistent = false
    override fun read(): StoredSession? = null
    override fun write(value: StoredSession) = Unit
    override fun clear() = Unit
    override fun writeIfOwned(expected: AuthPayload, value: StoredSession) = true
    override fun clearIfOwned(expected: AuthPayload) = Unit
}
sealed interface SessionRestoration {
    data object None : SessionRestoration
    data object Restored : SessionRestoration
    class Failed(val reason: AuthFailure) : SessionRestoration
}
