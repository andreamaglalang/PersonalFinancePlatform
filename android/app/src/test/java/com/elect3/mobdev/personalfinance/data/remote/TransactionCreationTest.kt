package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionCreationTest {
    private class Fixture(configured: Boolean = true) {
        val clock = MutableAuthClock(Instant.parse("2029-01-01T00:00:00Z"))
        var user = 1
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access-$user", "offline-refresh-$user", expiry, expiry.plusSeconds(1000),
                    AuthUser(user, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, clock)
        val requests = mutableListOf<FinancePostRequest>()
        var reply: suspend (FinancePostRequest) -> FinanceHttpResponse = { FinanceHttpResponse(201, response(it)) }
        val repository = RemoteTransactionCreationRepository(auth, FinancePostEngine { requests += it; reply(it) },
            if (configured) ApiConfiguration.https("https://localhost:7027/") else ApiConfiguration.unconfigured())
        suspend fun login() { auth.login("offline@example.test", "fixture-only") }
    }
    @Test fun exactApprovedPostPathBearerAndJsonFieldsOnly() = runTest {
        val f = Fixture(); f.login()
        val result = f.repository.create(request()) as CreationResult.Success
        val sent = f.requests.single()
        assertEquals("https://localhost:7027/api/transactions", sent.url.toString())
        assertEquals("Bearer offline-access-1", sent.authorization())
        val json = JsonParser.parseString(sent.body).asJsonObject
        assertEquals(setOf("clientId", "type", "categoryId", "amount", "currencyCode", "transactionDate", "description"), json.keySet())
        assertEquals("Expense", json["type"].asString); assertEquals(7, json["categoryId"].asInt)
        assertEquals("2026-11-15", json["transactionDate"].asString)
        assertTrue(json["description"].isJsonNull)
        assertFalse(result.replayed); assertEquals(42, result.transaction.transactionId)
    }
    @Test fun decimalsNeverUseDoubleOrScientificNotationAndDescriptionsAreEscaped() = runTest {
        val f = Fixture(); f.login()
        val draft = draft("9999999999999999.99", "Quotes \" and newline\n and unicode \u00e9")
        assertTrue(f.repository.create(request(draft)) is CreationResult.Success)
        val body = f.requests.single().body
        assertTrue(body.contains("9999999999999999.99"))
        assertEquals(draft.description, JsonParser.parseString(body).asJsonObject["description"].asString)
        assertEquals(BigDecimal("10000.00"), draft("1E+4").amount)
        assertTrue(CreationJson.encode(request(draft("1E+4"))).contains("10000.00"))
    }
    @Test fun http200ReplayIsDecodedWithoutClaimingNewCreation() = runTest {
        val f = Fixture(); f.login(); f.reply = { FinanceHttpResponse(200, response(it)) }
        val result = f.repository.create(request()) as CreationResult.Success
        assertTrue(result.replayed); assertEquals(42, result.transaction.transactionId)
    }
    @Test fun missingExpiredAndUnconfiguredSessionsNeverDispatch() = runTest {
        val f = Fixture()
        assertEquals(CreationResult.Failure(FinanceFailure.MissingSession), f.repository.create(request()))
        f.login(); f.clock.now = expiry
        assertEquals(CreationResult.Failure(FinanceFailure.SessionExpired), f.repository.create(request()))
        assertTrue(f.requests.isEmpty())
        val other = Fixture(false); other.login()
        assertEquals(CreationResult.Failure(FinanceFailure.NotConfigured), other.repository.create(request()))
        assertTrue(other.requests.isEmpty())
    }
    @Test fun timeoutAndInterruptedResponseAreUnknownAndNeverAutomaticallyRetried() = runTest {
        for ((exception, reason) in listOf(SocketTimeoutException("private") to UnknownCreationReason.Timeout,
            IOException("private partial response") to UnknownCreationReason.TransportFailure)) {
            val f = Fixture(); f.login(); f.reply = { throw exception }
            val result = f.repository.create(request())
            assertEquals(CreationResult.UnknownOutcome(reason), result)
            assertFalse(result.toString().contains("private")); assertEquals(1, f.requests.size)
        }
    }
    @Test fun malformedOversizedAndMismatchedSuccessRemainUnknown() = runTest {
        for (makeBody in listOf<(FinancePostRequest) -> String>({ "{" }, { "x".repeat(MAX_BODY_BYTES + 1) },
            { response(it).replace("\"amount\":12.34", "\"amount\":99.00") },
            { response(it).replace("\"clientId\":\"$id\"", "\"clientId\":\"${UUID.randomUUID()}\"") })) {
            val f = Fixture(); f.login(); f.reply = { FinanceHttpResponse(201, makeBody(it)) }
            assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.InvalidResponse), f.repository.create(request()))
        }
    }
    @Test fun serverFailureCouldFollowCommitAndIsNotReportedAsKnownFailure() = runTest {
        val f = Fixture(); f.login(); f.reply = { FinanceHttpResponse(503, "private SQL details") }
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure), f.repository.create(request()))
    }
    @Test fun explicitRejectionsAreTypedAndDoNotLeakErrorBodies() = runTest {
        for ((status, reason) in listOf(400 to FinanceFailure.InvalidInput, 401 to FinanceFailure.SessionExpired,
            403 to FinanceFailure.AccessDenied, 409 to FinanceFailure.Conflict, 429 to FinanceFailure.RateLimited)) {
            val f = Fixture(); f.login(); f.reply = { FinanceHttpResponse(status, "password or token must not escape") }
            val result = f.repository.create(request())
            assertEquals(CreationResult.Failure(reason), result)
            assertFalse(result.toString().contains("password")); assertEquals(1, f.requests.size)
        }
    }
    @Test fun logoutCancelsPendingDispatchWithoutClaimingNoWriteOccurred() = runTest {
        val f = Fixture(); f.login()
        var cancelled = false
        f.reply = { try { awaitCancellation() } finally { cancelled = true } }
        val call = async { f.repository.create(request()) }; runCurrent()
        f.auth.logout(); runCurrent()
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true), call.await())
        assertTrue(cancelled); assertFalse(f.auth.hasActiveSession())
    }
    @Test fun accountSwitchCannotPublishOldSuccessOrClearReplacementSession() = runTest {
        val f = Fixture(); f.login()
        val pending = CompletableDeferred<FinanceHttpResponse>()
        f.reply = { withContext(NonCancellable) { pending.await() } }
        val call = async { f.repository.create(request()) }; runCurrent()
        val old = f.requests.single()
        f.user = 2; f.login()
        pending.complete(FinanceHttpResponse(201, response(old)))
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true), call.await())
        assertTrue(f.auth.hasActiveSession())
        f.reply = { FinanceHttpResponse(201, response(it)) }
        assertTrue(f.repository.create(request()) is CreationResult.Success)
        assertEquals("Bearer offline-access-2", f.requests.last().authorization())
    }
    @Test fun expiryDuringDispatchRejectsResponseAsUncertain() = runTest {
        val f = Fixture(); f.login()
        f.reply = { f.clock.now = expiry; FinanceHttpResponse(201, response(it)) }
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true), f.repository.create(request()))
    }
    @Test fun callerCancellationPropagates() = runTest {
        val f = Fixture(); f.login(); var cancelled = false
        f.reply = { try { awaitCancellation() } finally { cancelled = true } }
        val call = async { f.repository.create(request()) }; runCurrent(); call.cancelAndJoin()
        assertTrue(cancelled)
    }
    @Test fun requestValidationRejectsInvalidMoneyIdsDatesAndDescriptions() {
        for (make in listOf<() -> Any>({ request(client = UUID(0, 0)) }, { draft("0") }, { draft("-1") },
            { draft("1.001") }, { draft("10000000000000000") }, { draft(description = "x".repeat(201)) },
            { TransactionDraft(TransactionType.Income, 0, BigDecimal.ONE, "PHP", date) },
            { TransactionDraft(TransactionType.Income, 1, BigDecimal.ONE, "php", date) },
            { TransactionDraft(TransactionType.Income, 1, BigDecimal.ONE, "PHP", LocalDate.of(0, 1, 1)) })) {
            try { make(); fail("Invalid request accepted") } catch (_: IllegalArgumentException) { } catch (_: ArithmeticException) { }
        }
    }
    @Test fun payloadEqualityCanonicalizesScaleAndRedactsRepresentations() {
        assertEquals(draft("1"), draft("1.00"))
        assertFalse(request().toString().contains(id.toString()))
        assertFalse(draft(description = "private").toString().contains("private"))
    }
    companion object {
        private val expiry = Instant.parse("2030-01-01T00:15:00Z")
        private val id = UUID.fromString("5bdb163e-8c0a-4fb9-ade4-e45d94368bfc")
        private val date = LocalDate.parse("2026-11-15")
        private fun draft(amount: String = "12.34", description: String? = null) =
            TransactionDraft(TransactionType.Expense, 7, BigDecimal(amount), "PHP", date, description)
        private fun request(draft: TransactionDraft = draft(), client: UUID = id) = CreateTransactionRequest(client, draft)
        private fun response(sent: FinancePostRequest): String = JsonParser.parseString(sent.body).asJsonObject.apply {
            addProperty("transactionId", 42); addProperty("categoryName", "Offline")
            addProperty("fxRate", BigDecimal.ONE); addProperty("fxRateDate", "2026-11-15")
            add("amountBase", get("amount")); addProperty("createdAtUtc", "2026-11-15T01:02:03")
            add("billId", com.google.gson.JsonNull.INSTANCE); add("billDueDate", com.google.gson.JsonNull.INSTANCE)
        }.toString()
    }
}
