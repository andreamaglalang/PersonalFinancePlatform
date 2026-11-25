package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** M1b: token rotation keeps legitimate in-flight reads valid; real session changes still reject them. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceSessionRefreshTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")

    private inner class Fixture {
        val clock = MutableAuthClock(expiry.minusSeconds(300))
        val requests = mutableListOf<FinanceHttpRequest>()
        val reply = CompletableDeferred<FinanceHttpResponse>()
        var hold = true
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> =
                ApiResult.Success(payload("access-old", 'a', expiry))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
            override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> =
                ApiResult.Success(payload("access-new", 'b', expiry.plusSeconds(900)))
        }, clock)
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { request ->
            requests += request
            if (hold) withContext(NonCancellable) { reply.await() } else FinanceHttpResponse(200, CATEGORIES)
        })
    }

    private fun payload(access: String, refresh: Char, expires: Instant) = AuthPayload(access, refresh.toString().repeat(86),
        expires, expires.plusSeconds(3600), AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User")))

    @Test fun readInFlightDuringRotationCompletesAndLaterReadsUseTheNewToken() = runTest {
        val f = Fixture()
        assertEquals(AuthResult.Success, f.auth.login("offline@example.test", "fixture-only"))
        val read = async { f.repos.categories.list() }; runCurrent()
        assertEquals("Bearer access-old", f.requests.single().authorization())
        f.clock.now = expiry.minusSeconds(60)
        f.auth.refreshIfDue()
        f.reply.complete(FinanceHttpResponse(200, CATEGORIES))
        assertTrue("Rotation must not reject a legitimate in-flight read", read.await() is FinanceResult.Success)
        f.hold = false
        f.clock.now = expiry.plusSeconds(5) // Past the original access expiry.
        assertTrue(f.repos.categories.list() is FinanceResult.Success)
        assertEquals("Bearer access-new", f.requests.last().authorization())
    }

    @Test fun logoutAfterRotationStillRejectsTheInFlightRead() = runTest {
        val f = Fixture()
        assertEquals(AuthResult.Success, f.auth.login("offline@example.test", "fixture-only"))
        val read = async { f.repos.categories.list() }; runCurrent()
        f.clock.now = expiry.minusSeconds(60)
        f.auth.refreshIfDue()
        assertEquals(AuthResult.Success, f.auth.logout())
        f.reply.complete(FinanceHttpResponse(200, CATEGORIES))
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired), read.await())
    }

    private companion object {
        const val CATEGORIES = """{"items":[{"categoryId":7,"name":"Salary","type":"Income","colorHEX":"#A0b1C2","isActive":true,"isSystemDefault":true}],"page":1,"pageSize":50,"totalCount":1}"""
    }
}
