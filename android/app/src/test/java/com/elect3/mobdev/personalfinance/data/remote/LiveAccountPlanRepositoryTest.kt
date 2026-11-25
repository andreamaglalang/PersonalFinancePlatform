package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveAccountPlanRepositoryTest {
    private class Fixture {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String) = ApiResult.Success(AuthPayload("access", "refresh", now.plusSeconds(900), now.plusSeconds(9000), AuthUser(42, "Original", "fixture@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String) = ApiResult.Success(Unit)
        }, MutableAuthClock(now))
        val requests = mutableListOf<FinanceWriteRequest>()
        var respond: suspend (FinanceWriteRequest) -> FinanceHttpResponse = { FinanceHttpResponse(204, "") }
        var read: suspend (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, "{\"id\":42,\"fullName\":\"Updated\",\"email\":\"fixture@example.test\",\"baseCurrency\":\"PHP\",\"version\":\"${"A".repeat(64)}\"}") }
        val repo = LiveAccountPlanRepository(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { read(it) }, FinanceWriteEngine { requests += it; respond(it) })
        suspend fun login() { assertEquals(AuthResult.Success, auth.login("fixture@example.test", "fixture-only")) }
    }
    @Test fun profileUsesAuthenticatedMemberEndpointWithoutQuery() = runTest {
        val f = Fixture(); f.login()
        f.read = { assertEquals("/api/account/profile", it.url.path); assertNull(it.url.query); FinanceHttpResponse(200, "{\"id\":42,\"fullName\":\"Updated\",\"email\":\"fixture@example.test\",\"baseCurrency\":\"PHP\",\"version\":\"${"A".repeat(64)}\"}") }
        assertTrue(f.repo.get("account/profile") is FinanceResult.Success)
    }
    @Test fun rejectsProfileForDifferentAccount() = runTest {
        val f = Fixture(); f.login(); f.read = { FinanceHttpResponse(200, "{\"id\":99}") }
        assertEquals(FinanceFailure.InvalidResponse, (f.repo.get("account/profile") as FinanceResult.Failure).reason)
    }
    @Test fun passwordSuccessClearsOnlyCurrentLocalSession() = runTest {
        val f = Fixture(); f.login(); assertTrue(f.repo.changePassword("old", "new", "new") is FinanceResult.Success)
        assertFalse(f.auth.hasActiveSession()); assertFalse(f.requests.single().toString().contains("old"))
    }
    @Test fun incorrectPasswordRetainsSessionAndNeverRetries() = runTest {
        val f = Fixture(); f.login(); f.respond = { FinanceHttpResponse(400, "") }
        assertEquals(FinanceFailure.InvalidInput, (f.repo.changePassword("old", "new", "new") as FinanceResult.Failure).reason)
        assertTrue(f.auth.hasActiveSession()); assertEquals(1, f.requests.size)
    }
    @Test fun uncertainPasswordOutcomeSignsOutAndNeverRetries() = runTest {
        val f = Fixture(); f.login(); f.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceFailure.Timeout, (f.repo.changePassword("old", "new", "new") as FinanceResult.Failure).reason)
        assertFalse(f.auth.hasActiveSession()); assertEquals(1, f.requests.size)
    }
    @Test fun passwordResponseCannotClearNewLogin() = runTest {
        val f = Fixture(); f.login(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.respond = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; FinanceHttpResponse(204, "") }
        val job = async { f.repo.changePassword("old", "new", "new") }; entered.await()
        f.auth.clearSession(); f.login(); release.complete(Unit); job.await()
        assertTrue(f.auth.hasActiveSession())
    }
    @Test fun goalsUseStrongIfMatchAndConflictIsNotRetried() = runTest {
        val f = Fixture(); f.login(); f.respond = { FinanceHttpResponse(412, "") }
        val result = f.repo.save("savings-goals/7", fields("name" to "Name"), update = true, version = "AAAAAAAAAAE=")
        assertEquals(FinanceFailure.Conflict, (result as FinanceResult.Failure).reason)
        assertEquals("\"AAAAAAAAAAE=\"", f.requests.single().ifMatch)
    }
    @Test fun invalidVersionDoesNotSend() = runTest {
        val f = Fixture(); f.login()
        assertTrue(f.repo.save("savings-goals/7", null, delete = true, version = "invalid") is FinanceResult.Failure)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun uncertainPaymentRetainsExactPayloadAcrossReopening() = runTest {
        val f = Fixture(); f.login(); f.respond = { throw SocketTimeoutException() }
        val path = "recurring-bills/7/payments"; val body = fields("clientId" to "fixture-id", "dueDate" to "2030-01-01")
        f.repo.save(path, body)
        assertEquals(body, f.repo.pendingRequest(path))
        assertEquals(FinanceFailure.Conflict, (f.repo.save(path, fields("clientId" to "new-id")) as FinanceResult.Failure).reason)
        assertEquals(1, f.requests.size)
        f.repo.save(path, f.repo.pendingRequest(path)); assertEquals(2, f.requests.size)
        assertEquals(f.requests[0].body, f.requests[1].body)
        f.auth.clearSession(); f.login(); assertNull(f.repo.pendingRequest(path))
    }
    @Test fun concurrentFinancialMutationsDoNotSendTwice() = runTest {
        val f = Fixture(); f.login(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.respond = { entered.complete(Unit); release.await(); FinanceHttpResponse(400, "") }
        val first = async { f.repo.save("recurring-bills/7/payments", fields("clientId" to "fixture-id")) }; entered.await()
        assertEquals(FinanceFailure.Conflict, (f.repo.save("recurring-bills/7/payments", fields("clientId" to "fixture-id")) as FinanceResult.Failure).reason)
        release.complete(Unit); first.await(); assertEquals(1, f.requests.size)
    }
    @Test fun missingSessionNeverSends() = runTest {
        val f = Fixture(); assertTrue(f.repo.get("account/profile") is FinanceResult.Failure); assertTrue(f.requests.isEmpty())
    }
    @Test fun urlsRejectArbitraryOrSpoofedResources() {
        val config = ApiConfiguration.https("https://localhost:7027/")
        listOf("account/profile?userId=2", "../admin", "savings-goals/0", "recurring-bills/1/payments?x=1").forEach { path ->
            assertTrue(runCatching { config.memberUrl(path) }.isFailure)
        }
    }
}
