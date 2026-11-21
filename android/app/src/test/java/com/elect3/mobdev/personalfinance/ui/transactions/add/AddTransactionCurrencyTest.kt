package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Currency selection on the production account path: form -> coordinator -> real JSON -> fake POST engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionCurrencyTest {
    @get:Rule val main = MainDispatcherRule()

    private class Fixture(workflow: FormWorkflow = FormWorkflow.Authenticated) {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("fx-access", "fx-refresh", Instant.parse("2030-01-01T00:15:00Z"),
                    Instant.parse("2030-01-01T00:30:00Z"), AuthUser(1, "Fx", "fx@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        })
        val posts = mutableListOf<FinancePostRequest>()
        var reply: suspend (FinancePostRequest) -> FinanceHttpResponse = { FinanceHttpResponse(201, response(it)) }
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { error("No GET expected") }, FinancePostEngine { posts += it; reply(it) })
        val model = AddTransactionViewModel(DemoCategoryRepository(), workflow, "PHP", { true }, {},
            Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC),
            accountCreation = if (workflow == FormWorkflow.Authenticated) finance.creation else null)
        val store = ViewModelStore().apply { put("form", model) }
        val state get() = model.state.value
        suspend fun prepare() { auth.login("fx@example.test", "fixture-only") }
        fun close() = store.clear()

        /** The API's answer for a foreign currency: its validated rate (8 dp) and base amount (2 dp, away from zero). */
        fun response(post: FinancePostRequest) = JsonParser.parseString(post.body).asJsonObject.apply {
            val foreign = get("currencyCode").asString != "PHP"
            val rate = if (foreign) BigDecimal("58.50000000") else BigDecimal("1.00000000")
            addProperty("transactionId", 42); addProperty("categoryName", "Account")
            addProperty("fxRate", rate); addProperty("fxRateDate", if (foreign) "2026-11-14" else get("transactionDate").asString)
            addProperty("amountBase", get("amount").asBigDecimal.multiply(rate).setScale(2, RoundingMode.HALF_UP))
            addProperty("createdAtUtc", "2026-11-15T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }

    @Test fun defaultsToTheAccountCurrencyAndSavesPhpAsBefore() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent()
            assertEquals("PHP", f.state.currency); assertEquals("PHP", f.state.baseCurrency)
            assertTrue(f.state.currencySelectable); assertFalse(f.state.foreignCurrency); assertFalse(f.state.dirty)
            f.model.amount("100.00"); f.model.category(1); f.model.submit(); runCurrent()
            assertEquals("PHP", JsonParser.parseString(f.posts.single().body).asJsonObject["currencyCode"].asString)
            val saved = checkNotNull(f.state.savedTransaction)
            assertEquals(0, saved.fxRate.compareTo(BigDecimal.ONE)); assertEquals(0, saved.amountBase.compareTo(BigDecimal("100.00")))
        } finally { f.close() }
    }

    @Test fun selectedForeignCurrencyAndOriginalAmountAreSubmittedAndTheServerSnapshotIsKept() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent()
            f.model.currency("USD")
            assertEquals("USD", f.state.currency); assertTrue(f.state.foreignCurrency); assertTrue(f.state.dirty); assertTrue(f.state.hasUserInput)
            f.model.amount("10.25"); f.model.category(1); f.model.submit(); runCurrent()
            val json = JsonParser.parseString(f.posts.single().body).asJsonObject
            assertEquals("USD", json["currencyCode"].asString)
            assertEquals(BigDecimal("10.25"), json["amount"].asBigDecimal) // the original amount, never a converted one
            assertFalse(json.has("fxRate") || json.has("amountBase")) // the phone never sends a rate
            val saved = checkNotNull(f.state.savedTransaction)
            assertEquals(BigDecimal("58.50000000"), saved.fxRate); assertEquals(BigDecimal("599.63"), saved.amountBase)
            assertEquals(java.time.LocalDate.parse("2026-11-14"), saved.fxRateDate)
        } finally { f.close() }
    }

    @Test fun onlyOfferedCurrenciesCanBeSelectedAndMockDemoStaysPhp() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.currency("XYZ"); assertEquals("PHP", f.state.currency)
            f.model.currency("usd"); assertEquals("PHP", f.state.currency)
        } finally { f.close() }
        val demo = Fixture(FormWorkflow.MockDemo)
        try {
            assertFalse(demo.state.currencySelectable)
            demo.model.currency("USD"); assertEquals("PHP", demo.state.currency)
        } finally { demo.close() }
    }

    @Test fun rateUnavailableServerErrorIsNotQueuedAndRetryReusesTheClientId() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent()
            f.model.currency("JPY"); f.model.amount("1000"); f.model.category(1)
            f.reply = { FinanceHttpResponse(503, "") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.OutcomeUnknown(UnknownCreationReason.ServerFailure), f.state.saveState)
            assertNull(f.state.savedTransaction)
            f.reply = { FinanceHttpResponse(201, f.response(it)) }
            f.model.retryUnknown(); runCurrent()
            val ids = f.posts.map { JsonParser.parseString(it.body).asJsonObject["clientId"].asString }
            assertEquals(2, ids.size); assertEquals(1, ids.distinct().size) // same ClientId: the API's idempotency prevents a duplicate
            assertTrue(f.state.saveState is FormSaveState.Succeeded)
        } finally { f.close() }
    }

    @Test fun rejectedCurrencyOrDateIsAFailedSaveNotAnUnknownOne() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.prepare(); runCurrent()
            f.model.currency("EUR"); f.model.amount("5.00"); f.model.category(1)
            f.reply = { FinanceHttpResponse(400, "") }
            f.model.submit(); runCurrent()
            assertEquals(FormSaveState.Failed(FinanceFailure.InvalidInput), f.state.saveState)
            assertNull(f.state.savedTransaction)
        } finally { f.close() }
    }
}
