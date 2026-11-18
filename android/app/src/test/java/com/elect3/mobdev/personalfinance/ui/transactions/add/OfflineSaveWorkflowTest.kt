package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.*
import com.elect3.mobdev.personalfinance.ui.transactions.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.math.BigDecimal
import java.net.SocketTimeoutException

/** Form -> coordinator -> real serializer/decoder -> fake engine. Never opens a socket. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSaveWorkflowTest {
    @get:Rule val main = MainDispatcherRule()
    private class Fixture(enable: Boolean = true, workflow: FormWorkflow = FormWorkflow.Authenticated) {
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
        private val creator = RemoteTransactionCreationRepository(auth, FinancePostEngine { posts += it; reply(it) }, ApiConfiguration.https("https://localhost:7027/"))
        val capability = OfflineSaveWorkflow(ControlledCreationRepository { creator.create(it) })
        val model = AddTransactionViewModel(DemoCategoryRepository(), workflow, "PHP", { current },
            { expired++; current = false; auth.clearSession() }, Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC))
        val store = ViewModelStore().apply { put("form", model) }
        init { if (enable) model.enableOfflineSave(capability) }
        suspend fun prepare() { auth.login("offline@example.test", "fixture-only") }
        fun valid() { model.amount("12,34"); model.category(1); model.description("Offline note") }
        fun close() = store.clear()
    }
    @Test fun expenseMappingIsValidatedAndSuccessfulCreationClosesFormOnce() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.model.submit(); runCurrent()
            val json = JsonParser.parseString(f.posts.single().body).asJsonObject
            assertEquals("Expense", json["type"].asString); assertEquals(BigDecimal("12.34"), json["amount"].asBigDecimal)
            assertEquals("2026-11-15", json["transactionDate"].asString); assertEquals("Offline note", json["description"].asString)
            assertEquals(FormSaveState.Succeeded(false), f.model.state.value.saveState)
            assertTrue(f.model.state.value.closeRequested)
            assertTrue(f.model.consumeSaveCompletion()); assertFalse(f.model.consumeSaveCompletion())
            f.model.submit(); runCurrent(); assertEquals(1, f.posts.size)
        } finally { f.close() }
    }
    @Test fun incomeAndReplaySuccessUseSameFormAndExplicitReplayState() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.model.type(TransactionType.Income); runCurrent(); f.valid()
            f.reply = { FinanceHttpResponse(200, response(it)) }
            f.model.submit(); runCurrent()
            assertEquals("Income", JsonParser.parseString(f.posts.single().body).asJsonObject["type"].asString)
            assertEquals(FormSaveState.Succeeded(true), f.model.state.value.saveState)
        } finally { f.close() }
    }
    @Test fun duplicateTapsAndAllPayloadEditingAreFrozenDuringSubmission() = runTest(main.dispatcher) {
        val f = Fixture(); val pending = CompletableDeferred<FinanceHttpResponse>()
        try {
            f.prepare(); runCurrent(); f.valid(); val before = f.model.state.value
            f.reply = { pending.await() }
            f.model.submit(); f.model.submit(); runCurrent()
            assertEquals(1, f.posts.size); assertEquals(FormSaveState.Submitting, f.model.state.value.saveState)
            f.model.amount("99"); f.model.description("Changed"); f.model.type(TransactionType.Income)
            f.model.date(LocalDate.parse("2026-11-16")); f.model.category(2)
            assertEquals(before.amount, f.model.state.value.amount); assertEquals(before.date, f.model.state.value.date)
            assertEquals(before.description, f.model.state.value.description); assertEquals(before.type, f.model.state.value.type)
            f.model.back(); assertFalse(f.model.state.value.closeRequested); assertFalse(f.model.state.value.discardConfirmation)
            pending.complete(FinanceHttpResponse(201, response(f.posts.single()))); runCurrent()
        } finally { f.close() }
    }
    @Test fun knownRejectionRetainsDraftAndCanBeRetried() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { FinanceHttpResponse(400, "private details") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.Failed(FinanceFailure.InvalidInput), f.model.state.value.saveState)
            assertEquals("12,34", f.model.state.value.amount); assertFalse(f.model.state.value.closeRequested)
            f.reply = { FinanceHttpResponse(201, response(it)) }; f.model.submit(); runCurrent()
            assertEquals(FormSaveState.Succeeded(false), f.model.state.value.saveState)
            assertEquals(client(f.posts.first()), client(f.posts.last()))
        } finally { f.close() }
    }
    @Test fun unknownOutcomeFreezesDraftAndUnchangedRetryReusesClientId() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { throw SocketTimeoutException("private") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.OutcomeUnknown(UnknownCreationReason.Timeout), f.model.state.value.saveState)
            assertFalse(f.model.state.value.closeRequested); assertTrue(f.model.state.value.hasUnresolvedOutcome)
            f.model.amount("99"); assertEquals("12,34", f.model.state.value.amount)
            f.reply = { FinanceHttpResponse(200, response(it)) }
            f.model.retryUnknown(); f.model.retryUnknown(); runCurrent()
            assertEquals(2, f.posts.size); assertEquals(client(f.posts.first()), client(f.posts.last()))
            assertEquals(FormSaveState.Succeeded(true), f.model.state.value.saveState)
        } finally { f.close() }
    }
    @Test fun changingUnknownDraftRequiresAcknowledgementAndNewId() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { throw SocketTimeoutException() }
            f.model.submit(); runCurrent()
            f.model.requestUnknownEdit(); assertTrue(f.model.state.value.unknownEditConfirmation)
            f.model.cancelUnknownEdit(); f.model.amount("99"); assertEquals("12,34", f.model.state.value.amount)
            f.model.requestUnknownEdit(); f.model.acknowledgeUnknownEdit(); f.model.amount("99")
            assertEquals("99", f.model.state.value.amount)
            f.reply = { FinanceHttpResponse(201, response(it)) }; f.model.submit(); runCurrent()
            assertNotEquals(client(f.posts.first()), client(f.posts.last()))
            assertTrue(f.model.state.value.hasUnresolvedOutcome)
            assertEquals(FormSaveState.Succeeded(false), f.model.state.value.saveState)
        } finally { f.close() }
    }
    @Test fun confirmedSaveRefreshFailureCannotBecomeFailedSaveOrSecondPost() = runTest(main.dispatcher) {
        val f = Fixture(); val reads = ControlledTransactionRepository()
        val list = TransactionListViewModel(reads); val owner = ViewModelStore().apply { put("list", list) }
        try {
            f.prepare(); runCurrent()
            reads.requests.single().succeed(TransactionPage(TransactionFixtures.items.take(2), 1, 20, 2)); runCurrent()
            val receipt = OfflineSaveReceipt(list)
            f.valid(); f.model.submit(); runCurrent()
            if (f.model.consumeSaveCompletion()) receipt.confirmAndRefresh()
            runCurrent(); assertEquals(1, reads.requests.last().page)
            reads.requests.last().fail(); runCurrent()
            assertTrue(receipt.confirmed.value)
            assertEquals(FormSaveState.Succeeded(false), f.model.state.value.saveState)
            val content = list.state.value as TransactionListUiState.Content
            assertEquals(TransactionFailure.Unavailable, content.refreshError); assertEquals(2, content.items.size)
            receipt.confirmAndRefresh(); runCurrent(); assertEquals(2, reads.requests.size); assertEquals(1, f.posts.size)
        } finally { f.close(); owner.clear() }
    }
    @Test fun successRefreshSupersedesAnOlderInitialRead() = runTest(main.dispatcher) {
        val reads = ControlledTransactionRepository(true)
        val list = TransactionListViewModel(reads); val owner = ViewModelStore().apply { put("list", list) }
        try {
            runCurrent(); val old = reads.requests.single()
            val receipt = OfflineSaveReceipt(list); receipt.confirmAndRefresh(); runCurrent()
            assertEquals(2, reads.requests.size); assertTrue(old.isCancelled)
            old.succeed(TransactionPage(emptyList(), 1, 20, 0))
            reads.requests.last().succeed(TransactionPage(TransactionFixtures.items.take(1), 1, 20, 1)); runCurrent()
            assertEquals(1, (list.state.value as TransactionListUiState.Content).items.size)
            assertTrue(receipt.confirmed.value)
        } finally { owner.clear(); reads.requests.filterNot { it.isCompleted }.forEach { it.fail() }; runCurrent() }
    }
    @Test fun current401UsesExistingExpiryCallbackAndErasesDraft() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { FinanceHttpResponse(401, "private") }
            f.model.submit(); runCurrent()
            assertEquals(1, f.expired); assertTrue(f.model.state.value.invalidated)
            assertEquals("", f.model.state.value.amount); assertNull(f.model.state.value.currency)
            assertFalse(f.model.consumeSaveCompletion())
        } finally { f.close() }
    }
    @Test fun accountSwitchRejectsLateSuccessWithoutSignOutCallback() = runTest(main.dispatcher) {
        val f = Fixture(); val pending = CompletableDeferred<FinanceHttpResponse>()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { withContext(NonCancellable) { pending.await() } }
            f.model.submit(); runCurrent()
            f.current = false; f.auth.clearSession()
            pending.complete(FinanceHttpResponse(201, response(f.posts.single()))); runCurrent()
            assertEquals(0, f.expired); assertTrue(f.model.state.value.invalidated)
            assertFalse(f.model.consumeSaveCompletion()); assertEquals("", f.model.state.value.description)
        } finally { f.close() }
    }
    @Test fun logoutCancelsFakeDispatchAndDropsAllFormState() = runTest(main.dispatcher) {
        val f = Fixture(); var cancelled = false
        try {
            f.prepare(); runCurrent(); f.valid()
            f.reply = { try { awaitCancellation() } finally { cancelled = true } }
            f.model.submit(); runCurrent(); f.current = false; f.model.invalidate(); f.auth.logout(); runCurrent()
            assertTrue(cancelled); assertTrue(f.model.state.value.invalidated)
            assertFalse(f.model.state.value.offlineSaveEnabled); assertFalse(f.model.consumeSaveCompletion())
        } finally { f.close() }
    }
    @Test fun productionAuthenticatedSubmitHasNoCreatorAndCannotClaimSuccess() = runTest(main.dispatcher) {
        val f = Fixture(enable = false)
        try {
            f.prepare(); runCurrent(); f.valid(); f.model.submit(); runCurrent()
            assertTrue(f.posts.isEmpty()); assertFalse(f.model.state.value.offlineSaveEnabled)
            assertEquals(FormSaveState.Idle, f.model.state.value.saveState)
            assertFalse(f.model.state.value.closeRequested)
        } finally { f.close() }
    }
    @Test fun mockDemoRejectsSaveCapabilityAndKeepsItsLocalPreview() = runTest(main.dispatcher) {
        val f = Fixture(enable = false, workflow = FormWorkflow.MockDemo)
        try {
            runCurrent(); f.valid()
            try { f.model.enableOfflineSave(f.capability); fail("Demo accepted an authenticated save harness") } catch (_: IllegalArgumentException) { }
            f.model.submit(); advanceTimeBy(300); runCurrent()
            assertTrue(f.model.state.value.completed); assertTrue(f.posts.isEmpty())
            assertEquals(FormSaveState.Idle, f.model.state.value.saveState)
        } finally { f.close() }
    }
    @Test fun unknownOutcomeBackRequiresDiscardAndDoesNotClaimRollback() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent(); f.valid(); f.reply = { throw SocketTimeoutException() }
            f.model.submit(); runCurrent(); f.model.back()
            assertTrue(f.model.state.value.discardConfirmation); assertTrue(f.model.state.value.hasUnresolvedOutcome)
            f.model.keepEditing(); assertTrue(f.model.state.value.saveState is FormSaveState.OutcomeUnknown)
            f.model.discard(); assertTrue(f.model.state.value.invalidated); assertFalse(f.model.consumeSaveCompletion())
        } finally { f.close() }
    }
    companion object {
        private fun client(post: FinancePostRequest) = JsonParser.parseString(post.body).asJsonObject["clientId"].asString
        private fun response(post: FinancePostRequest) = JsonParser.parseString(post.body).asJsonObject.apply {
            addProperty("transactionId", 42); addProperty("categoryName", "Offline")
            addProperty("fxRate", BigDecimal.ONE); add("fxRateDate", get("transactionDate")); add("amountBase", get("amount"))
            addProperty("createdAtUtc", "2026-11-15T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }
}
