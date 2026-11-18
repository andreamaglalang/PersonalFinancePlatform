package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** M1b: single-flight rotation, fail-closed outcomes and session isolation. In-memory fakes only; no HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryAuthRefreshTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private val refreshedExpiry = expiry.plusSeconds(900)

    private class ControlledApi : AuthApi {
        val logins = mutableListOf<CompletableDeferred<ApiResult<AuthPayload>>>()
        val refreshTokens = mutableListOf<String>()
        val refreshes = mutableListOf<CompletableDeferred<ApiResult<AuthPayload>>>()
        val logoutTokens = mutableListOf<String>()
        var nextLogin: AuthPayload? = null
        var throwOnRefresh = false
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> =
            nextLogin?.let { ApiResult.Success(it) }
                ?: CompletableDeferred<ApiResult<AuthPayload>>().also { logins += it }.await()
        override suspend fun logout(refreshToken: String): ApiResult<Unit> {
            logoutTokens += refreshToken
            return ApiResult.Success(Unit)
        }
        override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> {
            refreshTokens += refreshToken
            if (throwOnRefresh) throw IllegalStateException("synthetic-private-detail")
            return CompletableDeferred<ApiResult<AuthPayload>>().also { refreshes += it }.await()
        }
    }

    private class Fixture(now: Instant, first: AuthPayload) {
        val api = ControlledApi().apply { nextLogin = first }
        val clock = MutableAuthClock(now)
        val diagnostics = RecordingAuthDiagnostics()
        val repository = InMemoryAuthRepository(api, clock, diagnostics)
        suspend fun signIn() { assertEquals(AuthResult.Success, repository.login("demo@example.test", "test-only-input")) }
        fun bearer() = ((repository.financeSession() as FinanceResult.Success).value).authorization()
        fun count(event: AuthDiagnosticEvent) = diagnostics.events.count { it == event }
    }

    private fun payload(
        access: String, refreshChar: Char, accessExpires: Instant = expiry, user: Int = 1,
        refreshExpires: Instant = accessExpires.plusSeconds(3600),
    ) = AuthPayload(access, refreshChar.toString().repeat(86), accessExpires, refreshExpires,
        AuthUser(user, "Test user $user", "user$user@example.test", "PHP", listOf("User")))

    private fun fixture(secondsLeft: Long = 60) = Fixture(expiry.minusSeconds(secondsLeft), payload("access-old", 'a'))

    private fun TestScope.startRefresh(f: Fixture) = async { f.repository.refreshIfDue() }.also { runCurrent() }

    @Test fun notDueMakesNoRequest() = runTest {
        val f = fixture(secondsLeft = 121); f.signIn()
        f.repository.refreshIfDue()
        assertTrue(f.api.refreshTokens.isEmpty())
        f.clock.now = expiry.minusSeconds(120) // Exactly two minutes left is still not "less than two minutes".
        f.repository.refreshIfDue()
        assertTrue(f.api.refreshTokens.isEmpty())
    }

    @Test fun defaultRefreshCapabilityFailsClosedWithoutARequest() = runTest {
        val api = object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = error("unused")
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = error("unused")
        }
        assertEquals(AuthFailure.NotConfigured, (api.refresh("a".repeat(86)) as ApiResult.Failure).reason)
    }

    @Test fun noSessionMeansNoRequest() = runTest {
        val f = fixture()
        f.repository.refreshIfDue()
        assertTrue(f.api.refreshTokens.isEmpty())
        assertTrue(f.diagnostics.events.isEmpty())
    }

    @Test fun concurrentTriggersSendTheRefreshTokenExactlyOnce() = runTest {
        val f = fixture(); f.signIn()
        val first = startRefresh(f)
        val second = startRefresh(f)
        val third = startRefresh(f)
        assertEquals(listOf("a".repeat(86)), f.api.refreshTokens)
        second.await(); third.await() // Non-owners return immediately without a request.
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-new", 'b', refreshedExpiry)))
        first.await()
        assertEquals(1, f.api.refreshTokens.size)
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_SUCCEEDED))
    }

    @Test fun successReplacesCredentialsKeepsIdentityAndExtendsTheSession() = runTest {
        val f = fixture(); f.signIn()
        val oldLease = (f.repository.financeSession() as FinanceResult.Success).value
        val refresh = startRefresh(f)
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-new", 'b', refreshedExpiry)))
        refresh.await()
        assertEquals("Bearer access-new", f.bearer())
        assertTrue("In-flight requests survive rotation", oldLease.isCurrent())
        assertFalse(oldLease.lifetime.isCancelled)
        f.clock.now = expiry.plusSeconds(1) // Past the original expiry.
        assertTrue(f.repository.hasActiveSession())
        assertTrue(oldLease.isCurrent())
        assertEquals("PHP", f.repository.accountBaseCurrency())
        assertTrue(f.api.logoutTokens.isEmpty())
        // The next rotation presents the rotated token, never the consumed one.
        f.clock.now = refreshedExpiry.minusSeconds(30)
        val next = startRefresh(f)
        assertEquals(listOf("a".repeat(86), "b".repeat(86)), f.api.refreshTokens)
        f.api.refreshes.last().complete(ApiResult.Failure(AuthFailure.Timeout)); next.await()
        f.repository.clearSession(); runCurrent()
    }

    @Test fun definitiveRejectionBlocksFurtherAttemptsAndKeepsOriginalExpiry() = runTest {
        for (reason in listOf(AuthFailure.SessionExpired, AuthFailure.InvalidInput)) {
            val f = fixture(); f.signIn()
            val refresh = startRefresh(f)
            f.api.refreshes.single().complete(ApiResult.Failure(reason)); refresh.await()
            assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_REJECTED))
            assertTrue("Session continues until its own expiry", f.repository.hasActiveSession())
            assertEquals("Bearer access-old", f.bearer())
            f.clock.now = expiry.minusSeconds(10)
            f.repository.refreshIfDue()
            assertEquals(1, f.api.refreshTokens.size)
            f.clock.now = expiry
            assertFalse(f.repository.hasActiveSession())
            assertTrue(f.api.logoutTokens.isEmpty())
        }
    }

    @Test fun ambiguousOrUnavailableOutcomesNeverReuseTheToken() = runTest {
        val reasons = listOf(AuthFailure.Timeout, AuthFailure.Unavailable, AuthFailure.InvalidResponse,
            AuthFailure.SecureConnection, AuthFailure.RateLimited, AuthFailure.AccessDenied, AuthFailure.NotConfigured)
        for (reason in reasons) {
            val f = fixture(); f.signIn()
            val refresh = startRefresh(f)
            f.api.refreshes.single().complete(ApiResult.Failure(reason)); refresh.await()
            assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_NOT_COMPLETED))
            f.clock.now = expiry.minusSeconds(5)
            f.repository.refreshIfDue(); f.repository.refreshIfDue()
            assertEquals("$reason must not be retried", 1, f.api.refreshTokens.size)
            assertTrue(f.repository.hasActiveSession())
            f.clock.now = expiry
            assertFalse(f.repository.hasActiveSession())
        }
    }

    @Test fun unexpectedExceptionIsContainedAndBlocksWithoutLeakingDetail() = runTest {
        val f = fixture(); f.signIn()
        f.api.throwOnRefresh = true
        f.repository.refreshIfDue()
        f.repository.refreshIfDue()
        assertEquals(1, f.api.refreshTokens.size)
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_NOT_COMPLETED))
        assertFalse(f.diagnostics.entries.toString().contains("synthetic-private-detail"))
        assertTrue(f.repository.hasActiveSession())
    }

    @Test fun cancellationMidRequestIsTreatedAsUncertainAndBlocksReuse() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        refresh.cancelAndJoin()
        f.repository.refreshIfDue(); runCurrent()
        assertEquals(1, f.api.refreshTokens.size)
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_NOT_COMPLETED))
        assertTrue(f.repository.hasActiveSession())
    }

    @Test fun lateSuccessAfterLogoutNeverRestoresTheSessionAndRevokesOnlyTheOrphan() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        assertEquals(AuthResult.Success, f.repository.logout())
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-orphan", 'o', refreshedExpiry)))
        refresh.await()
        assertFalse(f.repository.hasActiveSession())
        assertTrue(f.repository.financeSession() is FinanceResult.Failure)
        assertEquals(listOf("a".repeat(86), "o".repeat(86)), f.api.logoutTokens)
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_RESULT_DISCARDED))
        assertEquals(0, f.count(AuthDiagnosticEvent.REFRESH_SUCCEEDED))
    }

    @Test fun lateSuccessNeverReplacesCredentialsOfANewLogin() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        f.api.nextLogin = payload("access-second", 'c', expiry, user = 2)
        f.signIn()
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-orphan", 'o', refreshedExpiry)))
        refresh.await()
        assertEquals("Bearer access-second", f.bearer())
        // Cleanup uses only the orphan token, never the new session's refresh token.
        assertEquals(listOf("o".repeat(86)), f.api.logoutTokens)
        f.repository.clearSession(); runCurrent()
    }

    @Test fun lateSuccessAfterLocalExpiryIsDiscardedAndOrphanRevoked() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        f.clock.now = expiry
        assertFalse(f.repository.hasActiveSession())
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-orphan", 'o', refreshedExpiry)))
        refresh.await()
        assertFalse(f.repository.hasActiveSession())
        assertEquals(listOf("o".repeat(86)), f.api.logoutTokens)
    }

    @Test fun lateFailureFromAnAbandonedSessionDoesNotBlockTheNewSession() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        f.repository.clearSession()
        f.api.nextLogin = payload("access-second", 'c', expiry, user = 2)
        f.signIn()
        f.api.refreshes.single().complete(ApiResult.Failure(AuthFailure.SessionExpired)); refresh.await()
        val second = startRefresh(f)
        assertEquals(listOf("a".repeat(86), "c".repeat(86)), f.api.refreshTokens)
        f.api.refreshes.last().complete(ApiResult.Success(payload("access-third", 'd', refreshedExpiry, user = 2)))
        second.await()
        assertEquals("Bearer access-third", f.bearer())
        f.repository.clearSession(); runCurrent()
    }

    @Test fun mismatchedUserIdentityFailsClosedWithoutCleanup() = runTest {
        val f = fixture(); f.signIn()
        val lease = (f.repository.financeSession() as FinanceResult.Success).value
        val refresh = startRefresh(f)
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-other", 'x', refreshedExpiry, user = 9)))
        refresh.await()
        assertFalse(f.repository.hasActiveSession())
        assertFalse(lease.isCurrent()); assertTrue(lease.lifetime.isCancelled)
        assertTrue(f.api.logoutTokens.isEmpty())
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_RESULT_DISCARDED))
    }

    @Test fun alreadyExpiredRefreshedCredentialsAreRejectedAndBlocked() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        f.api.refreshes.single().complete(ApiResult.Success(payload("access-stale", 'e', expiry.minusSeconds(120))))
        refresh.await()
        assertEquals("Bearer access-old", f.bearer())
        f.repository.refreshIfDue()
        assertEquals(1, f.api.refreshTokens.size)
        assertTrue(f.api.logoutTokens.isEmpty())
        f.clock.now = expiry
        assertFalse(f.repository.hasActiveSession())
    }

    @Test fun expiredRefreshTokenIsNeverSent() = runTest {
        val f = Fixture(expiry.minusSeconds(60), payload("access-old", 'a', refreshExpires = expiry.minusSeconds(90)))
        f.signIn()
        f.repository.refreshIfDue()
        assertTrue(f.api.refreshTokens.isEmpty())
        assertEquals(1, f.count(AuthDiagnosticEvent.REFRESH_REJECTED))
    }

    @Test fun refreshedTokensNeverAppearInDiagnosticsOrRepresentations() = runTest {
        val f = fixture(); f.signIn()
        val refresh = startRefresh(f)
        val fresh = payload("access-new-secret", 'b', refreshedExpiry)
        f.api.refreshes.single().complete(ApiResult.Success(fresh)); refresh.await()
        val visible = f.diagnostics.entries.toString() + fresh.toString() + f.repository.toString() +
            (f.repository.financeSession() as FinanceResult.Success).value.toString()
        assertFalse(visible.contains("access-new-secret"))
        assertFalse(visible.contains("b".repeat(86)))
        f.repository.clearSession(); runCurrent()
    }
}
