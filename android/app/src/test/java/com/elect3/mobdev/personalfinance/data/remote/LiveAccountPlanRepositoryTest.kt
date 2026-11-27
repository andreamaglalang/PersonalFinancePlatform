package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.local.InMemoryPendingRequestStore
import com.elect3.mobdev.personalfinance.data.local.PendingRequestStore
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveAccountPlanRepositoryTest {
    private class Fixture(val store: PendingRequestStore = InMemoryPendingRequestStore(), accountId: Int = 42) {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String) = ApiResult.Success(AuthPayload("access", "refresh", now.plusSeconds(900), now.plusSeconds(9000), AuthUser(accountId, "Original", "fixture@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String) = ApiResult.Success(Unit)
        }, MutableAuthClock(now))
        val requests = mutableListOf<FinanceWriteRequest>()
        var respond: suspend (FinanceWriteRequest) -> FinanceHttpResponse = { FinanceHttpResponse(204, "") }
        var read: suspend (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, "{\"id\":42,\"fullName\":\"Updated\",\"email\":\"fixture@example.test\",\"baseCurrency\":\"PHP\",\"version\":\"${"A".repeat(64)}\"}") }
        val repo = LiveAccountPlanRepository(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { read(it) }, FinanceWriteEngine { requests += it; respond(it) }, store)
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
    @Test fun overFundedGoalLoadsWithSignedRemaining() = runTest {
        val f = Fixture(); f.login()
        fun goal(saved: String, remaining: String) = "{\"goalId\":7,\"name\":\"Bike\",\"targetAmount\":200.00,\"savedAmount\":$saved,\"remainingAmount\":$remaining,\"surplusAmount\":30.75,\"progressPercentage\":115.38,\"baseCurrency\":\"PHP\",\"status\":\"Completed\",\"targetDate\":null,\"version\":\"AAAAAAAAAAE=\"}"
        f.read = { FinanceHttpResponse(200, goal("230.75", "-30.75")) }
        assertTrue("an over-funded goal must load", f.repo.get("savings-goals/7") is FinanceResult.Success)
        f.read = { FinanceHttpResponse(200, goal("230.75", "-10.00")) }
        assertTrue("remaining must equal target minus saved", f.repo.get("savings-goals/7") is FinanceResult.Failure)
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
        // Durable per account: signing in again (same account) still offers the same identity.
        f.auth.clearSession(); f.login(); assertEquals(body, f.repo.pendingRequest(path))
    }
    private val paymentPath = "recurring-bills/7/payments"
    private val paymentId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val paymentBody = fields("clientId" to paymentId, "dueDate" to "2030-01-01")
    private val paymentReceipt = "{\"receipt\":{\"clientId\":\"$paymentId\",\"billId\":7,\"dueDate\":\"2030-01-01\",\"amount\":10.00,\"currencyCode\":\"PHP\"}}"
    @Test fun sameIdentityAndPayloadAreReusedAfterSimulatedProcessDeath() = runTest {
        val store = InMemoryPendingRequestStore()
        val before = Fixture(store); before.login()
        val contribution = "savings-goals/5/contributions"
        val body = fields("amount" to java.math.BigDecimal("12.50"), "contributedOn" to "2030-01-01", "note" to null, "clientId" to paymentId)
        before.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceFailure.Timeout, (before.repo.save(contribution, body) as FinanceResult.Failure).reason)
        // New process: new auth session and repository instance over the same durable store.
        val after = Fixture(store); after.login()
        assertEquals(setOf(contribution), after.repo.pendingPaths())
        val restored = after.repo.pendingRequest(contribution)!!
        assertEquals(paymentId, restored.get("clientId").asString)
        after.respond = { FinanceHttpResponse(201, "{\"receipt\":{\"clientId\":\"$paymentId\",\"goalId\":5,\"originalContributedOn\":\"2030-01-01\",\"originalAmount\":12.50,\"originalNote\":null},\"deleted\":false}") }
        assertTrue(after.repo.save(contribution, restored) is FinanceResult.Success)
        assertEquals(before.requests.single().body, after.requests.single().body)
        assertNull(after.repo.pendingRequest(contribution))
    }
    @Test fun identityIsPersistedBeforeTheRequestIsSent() = runTest {
        val f = Fixture(); f.login()
        f.respond = { assertNotNull(f.store.find("https://localhost:7027/#42", paymentPath)); FinanceHttpResponse(201, paymentReceipt) }
        assertTrue(f.repo.save(paymentPath, paymentBody) is FinanceResult.Success)
        assertEquals(1, f.requests.size); assertTrue(f.repo.pendingPaths().isEmpty())
    }
    @Test fun keptOnTransportFailureServerErrorAuthAndRateLimit() = runTest {
        val outcomes = listOf<suspend (FinanceWriteRequest) -> FinanceHttpResponse>(
            { throw SocketTimeoutException() }, { throw java.io.IOException() },
            { FinanceHttpResponse(500, "") }, { FinanceHttpResponse(503, "") }, { FinanceHttpResponse(429, "") }, { FinanceHttpResponse(401, "") },
            { FinanceHttpResponse(201, "{}") /* unverifiable receipt */ })
        for (outcome in outcomes) {
            val f = Fixture(); f.login(); f.respond = outcome
            assertTrue(f.repo.save(paymentPath, paymentBody) is FinanceResult.Failure)
            assertEquals(paymentBody.toString(), f.store.find("https://localhost:7027/#42", paymentPath))
        }
    }
    @Test fun clearedOnSuccessReplayAndDefinitiveRejection() = runTest {
        for (status in listOf(200, 201, 400, 403, 404, 409)) {
            val f = Fixture(); f.login()
            f.respond = { FinanceHttpResponse(status, if (status < 300) paymentReceipt else "") }
            f.repo.save(paymentPath, paymentBody)
            assertNull("status $status", f.repo.pendingRequest(paymentPath))
        }
    }
    @Test fun anotherAccountNeverSeesOrReplaysThePendingRequest() = runTest {
        val store = InMemoryPendingRequestStore()
        val first = Fixture(store, accountId = 42); first.login(); first.respond = { throw SocketTimeoutException() }
        first.repo.save(paymentPath, paymentBody)
        val other = Fixture(store, accountId = 43); other.login()
        assertNull(other.repo.pendingRequest(paymentPath)); assertTrue(other.repo.pendingPaths().isEmpty())
        // The other account's own payment is independent and does not conflict with account 42's record.
        val otherBody = fields("clientId" to "9f2504e0-4f89-41d3-9a0c-0305e82c3309", "dueDate" to "2030-01-01")
        other.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceFailure.Timeout, (other.repo.save(paymentPath, otherBody) as FinanceResult.Failure).reason)
        assertEquals(paymentBody.toString(), store.find("https://localhost:7027/#42", paymentPath))
        assertEquals(otherBody, other.repo.pendingRequest(paymentPath))
    }
    @Test fun signedOutNeitherPersistsNorSends() = runTest {
        val f = Fixture()
        assertEquals(FinanceFailure.MissingSession, (f.repo.save(paymentPath, paymentBody) as FinanceResult.Failure).reason)
        assertTrue(f.requests.isEmpty()); assertTrue(f.store.paths("https://localhost:7027/#42").isEmpty())
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
