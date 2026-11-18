package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics

/** Uses only a test-local transport double; never instantiates the network client. */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryAuthRepositoryTest {
    private val expires = Instant.parse("2030-01-01T00:15:00Z")

    private fun checkExpiry(now: Instant, expectedActive: Boolean) = runTest {
        val api = ControlledApi()
        val clock = MutableAuthClock(now)
        val diagnostics = RecordingAuthDiagnostics()
        val repository = InMemoryAuthRepository(api, clock, diagnostics)
        val login = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        api.logins.single().complete(ApiResult.Success(payload(expires)))
        login.await()
        assertEquals(expectedActive, repository.hasActiveSession())
        assertEquals(!expectedActive, AuthDiagnosticEvent.ACCESS_TOKEN_EXPIRED in diagnostics.events)
        assertTrue(api.logouts.isEmpty()) // Expiry is local; it must not claim server logout.
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
        if (!expectedActive) {
            assertTrue(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED in diagnostics.events)
            assertFalse(repository.hasActiveSession())
            assertEquals(1, diagnostics.events.count { it == AuthDiagnosticEvent.ACCESS_TOKEN_EXPIRED })
        }
    }

    @Test fun sessionActiveImmediatelyBeforeExpiry() = checkExpiry(expires.minusNanos(1), true)
    @Test fun sessionInvalidExactlyAtExpiry() = checkExpiry(expires, false)
    @Test fun sessionInvalidAfterExpiry() = checkExpiry(expires.plusNanos(1), false)

    @Test fun successfulLoginCreatesMemorySession() = runTest {
        val api = ControlledApi()
        val repository = InMemoryAuthRepository(api)
        val login = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        assertFalse(repository.hasActiveSession())
        api.logins.single().complete(ApiResult.Success(payload()))
        assertEquals(AuthResult.Success, login.await())
        assertTrue(repository.hasActiveSession())
        repository.clearSession()
        assertFalse(repository.hasActiveSession())
    }

    @Test fun logoutClearsMemoryBeforeServerRespondsAndFailureCannotRestoreIt() = runTest {
        val api = ControlledApi()
        val repository = InMemoryAuthRepository(api)
        val login = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        api.logins.single().complete(ApiResult.Success(payload()))
        login.await()
        val logout = async { repository.logout() }
        runCurrent()
        assertFalse(repository.hasActiveSession())
        assertFalse(logout.isCompleted)
        api.logouts.single().complete(ApiResult.Failure(AuthFailure.Unavailable))
        assertEquals(AuthResult.Failure(AuthFailure.Unavailable), logout.await())
        assertFalse(repository.hasActiveSession())
    }

    @Test fun successfulScopedLogoutClearsSessionWithoutClaimingIndependentRevocation() = runTest {
        val api = ControlledApi()
        val diagnostics = RecordingAuthDiagnostics()
        val repository = InMemoryAuthRepository(api, diagnostics = diagnostics)
        val login = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        api.logins.single().complete(ApiResult.Success(payload()))
        login.await()
        val logout = async { repository.logout() }
        runCurrent()
        assertFalse(repository.hasActiveSession())
        assertTrue(AuthDiagnosticEvent.LOCAL_SESSION_CLEARED in diagnostics.events)
        api.logouts.single().complete(ApiResult.Success(Unit))
        assertEquals(AuthResult.Success, logout.await())
        assertEquals(1, api.logouts.size)
        // A fake transport/repository success must not manufacture an HTTP acknowledgement event.
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
        assertEquals(AuthResult.Success, repository.logout())
        assertEquals(1, api.logouts.size)
        assertTrue(AuthDiagnosticEvent.LOGOUT_NO_LOCAL_SESSION in diagnostics.events)
        assertTrue(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED in diagnostics.events)
    }

    @Test fun clearingSessionInvalidatesLateLoginResponse() = runTest {
        val api = ControlledApi()
        val repository = InMemoryAuthRepository(api)
        val login = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        repository.clearSession()
        api.logins.single().complete(ApiResult.Success(payload()))
        assertEquals(AuthResult.Failure(AuthFailure.SessionExpired), login.await())
        assertFalse(repository.hasActiveSession())
    }

    @Test fun cancelledLoginDoesNotAuthenticateAndAllowsAnotherAttempt() = runTest {
        val api = ControlledApi()
        val repository = InMemoryAuthRepository(api)
        val cancelled = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        cancelled.cancelAndJoin()
        assertFalse(repository.hasActiveSession())
        val retry = async { repository.login("demo@example.test", "test-only-input") }
        runCurrent()
        assertEquals(2, api.logins.size)
        api.logins.last().complete(ApiResult.Success(payload()))
        assertEquals(AuthResult.Success, retry.await())
        assertTrue(repository.hasActiveSession())
        repository.clearSession()
    }

    private fun payload(expiry: Instant = Instant.now().plusSeconds(600)) = AuthPayload(
        accessToken = "test-only-access",
        refreshToken = "r".repeat(86),
        accessExpires = expiry,
        refreshExpires = expiry.plusSeconds(3600),
        user = AuthUser(1, "Test user", "demo@example.test", "PHP", listOf("User")),
    )

    private class ControlledApi : AuthApi {
        val logins = mutableListOf<CompletableDeferred<ApiResult<AuthPayload>>>()
        val logouts = mutableListOf<CompletableDeferred<ApiResult<Unit>>>()
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> =
            CompletableDeferred<ApiResult<AuthPayload>>().also { logins += it }.await()
        override suspend fun logout(refreshToken: String): ApiResult<Unit> =
            CompletableDeferred<ApiResult<Unit>>().also { logouts += it }.await()
    }
}
