package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.*

/**
 * M1: the production account path. Form -> FinanceRepositories.creation (RemoteTransactionCreationRepository)
 * -> TransactionCreationCoordinator -> real serializer/decoder -> fake POST engine. Never opens a socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSaveWorkflowTest {
    @get:Rule val main = MainDispatcherRule()

    private class Fixture(workflow: FormWorkflow = FormWorkflow.Authenticated, withCreator: Boolean = true) {
        var current = true
        var expired = 0
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", Instant.parse("2030-01-01T00:15:00Z"),
                    Instant.parse("2030-01-01T00:30:00Z"), AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        })
        val posts = mutableListOf<FinancePostRequest>()
        var reply: suspend (FinancePostRequest) -> FinanceHttpResponse = { FinanceHttpResponse(201, response(it)) }
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { error("No GET expected in this test") },
            FinancePostEngine { posts += it; reply(it) })
        val model = AddTransactionViewModel(DemoCategoryRepository(), workflow, "PHP", { current },
            { expired++; current = false; auth.clearSession() }, Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC),
            accountCreation = if (withCreator) finance.creation else null)
        val store = ViewModelStore().apply { put("form", model) }
        suspend fun prepare() { auth.login("offline@example.test", "fixture-only") }
        fun valid() { model.amount("12,34"); model.category(1); model.description("Account note") }
        fun close() = store.clear()
    }

    @Test fun authenticatedExpenseIsPostedOnceWithBearerAndClosesFormOnce() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            assertTrue(f.model.state.value.accountSaveEnabled); assertFalse(f.model.state.value.offlineSaveEnabled)
            f.prepare(); runCurrent(); f.valid(); f.model.submit(); runCurrent()
            val post = f.posts.single()
            assertEquals("Bearer offline-access", post.authorization())
            assertEquals("https://localhost:7027/api/transactions", post.url.toString())
            val json = JsonParser.parseString(post.body).asJsonObject
            assertEquals("Expense", json["type"].asString); assertEquals(BigDecimal("12.34"), json["amount"].asBigDecimal)
            assertEquals("PHP", json["currencyCode"].asString); assertEquals("2026-11-15", json["transactionDate"].asString)
            assertEquals("Account note", json["description"].asString); assertTrue(json["clientId"].asString.isNotBlank())
            assertEquals(FormSaveState.Succeeded(false), f.model.state.value.saveState)
            assertTrue(f.model.state.value.closeRequested)
            assertTrue(f.model.consumeSaveCompletion()); assertFalse(f.model.consumeSaveCompletion())
            f.model.submit(); runCurrent(); assertEquals(1, f.posts.size)
        } finally { f.close() }
    }

    @Test fun incomeReplayIsReportedAsReplayedSuccess() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.model.type(TransactionType.Income); runCurrent(); f.valid()
            f.reply = { FinanceHttpResponse(200, response(it)) }
            f.model.submit(); runCurrent()
            assertEquals("Income", JsonParser.parseString(f.posts.single().body).asJsonObject["type"].asString)
            assertEquals(FormSaveState.Succeeded(true), f.model.state.value.saveState)
        } finally { f.close() }
    }

    @Test fun unknownOutcomeRetryReusesTheSameClientIdSoTheServerCannotDuplicate() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { throw SocketTimeoutException("private") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.OutcomeUnknown(UnknownCreationReason.Timeout), f.model.state.value.saveState)
            assertFalse(f.model.state.value.closeRequested)
            f.reply = { FinanceHttpResponse(200, response(it)) }
            f.model.retryUnknown(); runCurrent()
            assertEquals(2, f.posts.size); assertEquals(client(f.posts.first()), client(f.posts.last()))
            assertEquals(FormSaveState.Succeeded(true), f.model.state.value.saveState)
        } finally { f.close() }
    }

    @Test fun validationRejectionKeepsTheDraftAndNeverClaimsSuccess() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { FinanceHttpResponse(400, "private details") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.Failed(FinanceFailure.InvalidInput), f.model.state.value.saveState)
            assertEquals("12,34", f.model.state.value.amount); assertFalse(f.model.state.value.closeRequested)
            assertFalse(f.model.consumeSaveCompletion())
        } finally { f.close() }
    }

    @Test fun server401ExpiresTheSessionAndErasesTheDraft() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { FinanceHttpResponse(401, "private") }
            f.model.submit(); runCurrent()
            assertEquals(1, f.expired); assertTrue(f.model.state.value.invalidated)
            assertEquals("", f.model.state.value.amount); assertFalse(f.model.consumeSaveCompletion())
        } finally { f.close() }
    }

    @Test fun missingLocalSessionCannotSendAndUsesExpiryCallback() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid()
            f.auth.clearSession() // Token gone (expired or signed out) while the form is still open.
            f.model.submit(); runCurrent()
            assertTrue(f.posts.isEmpty()); assertEquals(1, f.expired); assertTrue(f.model.state.value.invalidated)
        } finally { f.close() }
    }

    @Test fun staleSessionKeyBlocksSubmitBeforeAnyRequest() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.current = false
            f.model.submit(); runCurrent()
            assertTrue(f.posts.isEmpty()); assertEquals(FormSaveState.Idle, f.model.state.value.saveState)
        } finally { f.close() }
    }

    @Test fun mockDemoCannotReceiveAnAccountCreator() {
        try {
            Fixture(workflow = FormWorkflow.MockDemo).close()
            fail("Mock Demo accepted an account creator")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun mockDemoWithoutCreatorStaysLocalPreviewOnly() = runTest(main.dispatcher) {
        val f = Fixture(workflow = FormWorkflow.MockDemo, withCreator = false)
        try {
            runCurrent(); f.valid(); f.model.submit(); advanceTimeBy(300); runCurrent()
            assertTrue(f.model.state.value.completed); assertTrue(f.posts.isEmpty())
            assertFalse(f.model.state.value.accountSaveEnabled)
        } finally { f.close() }
    }

    @Test fun testHarnessCannotReplaceTheAccountCreator() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            try {
                f.model.enableOfflineSave(OfflineSaveWorkflow(ControlledCreationRepository { error("not used") }))
                fail("Harness replaced the account creator")
            } catch (_: IllegalArgumentException) { }
            assertTrue(f.model.state.value.accountSaveEnabled); assertFalse(f.model.state.value.offlineSaveEnabled)
        } finally { f.close() }
    }

    @Test fun authenticatedFormWithoutCreatorStaysUnavailable() = runTest(main.dispatcher) {
        val f = Fixture(withCreator = false)
        try {
            f.prepare(); runCurrent(); f.valid(); f.model.submit(); runCurrent()
            assertTrue(f.posts.isEmpty()); assertFalse(f.model.state.value.accountSaveEnabled)
            assertEquals(FormSaveState.Idle, f.model.state.value.saveState)
        } finally { f.close() }
    }

    @Test fun testHostFinanceRepositoriesNeverSendWithoutAnExplicitPostEngine() = runTest(main.dispatcher) {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", Instant.parse("2030-01-01T00:15:00Z"),
                    Instant.parse("2030-01-01T00:30:00Z"), AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        })
        auth.login("offline@example.test", "fixture-only")
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { error("unused") })
        val draft = TransactionDraft(TransactionType.Expense, 1, BigDecimal("1.00"), "PHP", LocalDate.parse("2026-11-15"))
        val result = finance.creation.create(CreateTransactionRequest(java.util.UUID.randomUUID(), draft))
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure), result)
    }

    companion object {
        private fun client(post: FinancePostRequest) = JsonParser.parseString(post.body).asJsonObject["clientId"].asString
        private fun response(post: FinancePostRequest) = JsonParser.parseString(post.body).asJsonObject.apply {
            addProperty("transactionId", 42); addProperty("categoryName", "Account")
            addProperty("fxRate", BigDecimal.ONE); add("fxRateDate", get("transactionDate")); add("amountBase", get("amount"))
            addProperty("createdAtUtc", "2026-11-15T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }
}
