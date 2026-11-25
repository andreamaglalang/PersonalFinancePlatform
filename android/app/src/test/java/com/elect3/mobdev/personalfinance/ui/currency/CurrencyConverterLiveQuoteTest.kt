package com.elect3.mobdev.personalfinance.ui.currency

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuote
import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyConverterLiveQuoteTest {
    @get:Rule val main = MainDispatcherRule()

    private fun quote(from: String, to: String, amount: BigDecimal, rate: String = "58.12345678") = ExchangeRateQuote(from, to, amount,
        BigDecimal(rate), LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20),
        amount.multiply(BigDecimal(rate)).setScale(2, RoundingMode.HALF_UP), "Frankfurter", Instant.parse("2026-11-20T08:52:10Z"))

    private inner class Fixture(demo: Boolean = false, var reply: suspend (String, String, BigDecimal) -> FinanceResult<ExchangeRateQuote> =
        { f, t, a -> FinanceResult.Success(quote(f, t, a)) }) {
        val calls = mutableListOf<Triple<String, String, BigDecimal>>()
        var current = true; var expired = 0
        val model = CurrencyConverterViewModel("PHP", demo, emptyList(), canRecordTransaction = true,
            quotes = ExchangeRateQuoteRepository { f, t, a -> calls += Triple(f, t, a); reply(f, t, a) },
            isSessionCurrent = { current }, onSessionExpired = { expired++ })
        val store = ViewModelStore().apply { put("c", model) }
        val state get() = model.state.value
        fun close() = store.clear()
    }

    @Test fun liveQuoteGoesFromIdleThroughLoadingToTheServersNumbers() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            assertTrue(f.state.liveQuotesAvailable); assertFalse(f.state.canRequestQuote)
            f.model.amount("10.25"); assertTrue(f.state.canRequestQuote); assertEquals(LiveQuote.Idle, f.state.liveQuote)
            f.model.requestQuote(); assertEquals(LiveQuote.Loading, f.state.liveQuote); assertFalse(f.state.canRequestQuote)
            runCurrent()
            val q = (f.state.liveQuote as LiveQuote.Ready).quote
            assertEquals(Triple("USD", "PHP", BigDecimal("10.25")), f.calls.single())
            assertEquals(BigDecimal("595.77"), q.convertedAmount) // 10.25 × 58.12345678 = 595.7654... -> 595.77
            assertEquals("Frankfurter", q.source)
        } finally { f.close() }
    }

    @Test fun refreshRequestsAgainAndAnyInputChangeClearsTheShownQuote() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("100"); f.model.requestQuote(); runCurrent()
            f.model.refresh(); runCurrent(); assertEquals(2, f.calls.size); assertTrue(f.state.liveQuote is LiveQuote.Ready)
            f.model.amount("200"); assertEquals(LiveQuote.Idle, f.state.liveQuote)
            f.model.requestQuote(); runCurrent(); f.model.swap(); assertEquals(LiveQuote.Idle, f.state.liveQuote)
            assertEquals("PHP", f.state.from); assertEquals("USD", f.state.to)
            f.model.requestQuote(); runCurrent(); assertEquals(Triple("PHP", "USD", BigDecimal("200.00")), f.calls.last())
            f.model.to("EUR"); assertEquals(LiveQuote.Idle, f.state.liveQuote)
        } finally { f.close() }
    }

    @Test fun duplicateRequestsWhileLoadingUseOneCall() = runTest(main.dispatcher) {
        val reply = CompletableDeferred<FinanceResult<ExchangeRateQuote>>()
        val f = Fixture(reply = { _, _, _ -> reply.await() })
        try {
            f.model.amount("10")
            f.model.requestQuote(); f.model.requestQuote(); f.model.refresh(); runCurrent()
            assertEquals(1, f.calls.size)
            reply.complete(FinanceResult.Success(quote("USD", "PHP", BigDecimal("10.00")))); runCurrent()
            assertTrue(f.state.liveQuote is LiveQuote.Ready)
        } finally { f.close() }
    }

    @Test fun liveQuickAmountsClearTheQuoteWithoutRequestingAnotherRate() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("10"); f.model.requestQuote(); runCurrent()
            assertTrue(f.state.liveQuote is LiveQuote.Ready)
            f.model.quickAmount(1000)
            assertEquals("1000", f.state.amount)
            assertEquals(LiveQuote.Idle, f.state.liveQuote)
            assertEquals(1, f.calls.size)
            f.model.quickAmount(7)
            assertEquals("1000", f.state.amount)
            f.model.from("EUR"); f.model.quickAmount(500)
            assertEquals("EUR", f.state.from)
            assertEquals("500", f.state.amount)
        } finally { f.close() }
        val demo = Fixture(demo = true)
        try {
            demo.model.amount("25"); demo.model.quickAmount(100)
            assertEquals("25", demo.state.amount)
            assertTrue(demo.calls.isEmpty())
        } finally { demo.close() }
    }

    @Test fun aStaleAnswerForPreviousInputIsDropped() = runTest(main.dispatcher) {
        val slow = CompletableDeferred<FinanceResult<ExchangeRateQuote>>()
        val f = Fixture(reply = { _, _, _ -> slow.await() })
        try {
            f.model.amount("10"); f.model.requestQuote(); runCurrent()
            f.model.amount("11") // the user kept typing
            slow.complete(FinanceResult.Success(quote("USD", "PHP", BigDecimal("10.00")))); runCurrent()
            assertEquals(LiveQuote.Idle, f.state.liveQuote) // never shows 10's result next to 11
        } finally { f.close() }
    }

    @Test fun failuresMapToHonestStates() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("5")
            for ((failure, expected) in listOf(
                FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true) to LiveQuote.Offline,
                FinanceResult.Failure(FinanceFailure.Timeout) to LiveQuote.Offline,
                FinanceResult.Failure(FinanceFailure.Unavailable) to LiveQuote.ProviderUnavailable,
                FinanceResult.Failure(FinanceFailure.InvalidInput) to LiveQuote.NotSupported,
                FinanceResult.Failure(FinanceFailure.NotFound) to LiveQuote.NotDeployed,
                FinanceResult.Failure(FinanceFailure.InvalidResponse) to LiveQuote.Failed)) {
                f.reply = { _, _, _ -> failure }
                f.model.requestQuote(); runCurrent()
                assertEquals(failure.toString(), expected, f.state.liveQuote)
                assertTrue("retry is possible", f.state.canRequestQuote)
            }
            assertEquals(0, f.expired)
        } finally { f.close() }
    }

    @Test fun sessionExpiryRoutesToExpiryAndNeverShowsAQuote() = runTest(main.dispatcher) {
        val f = Fixture(reply = { _, _, _ -> FinanceResult.Failure(FinanceFailure.SessionExpired) })
        try {
            f.model.amount("5"); f.model.requestQuote(); runCurrent()
            assertEquals(1, f.expired); assertFalse(f.state.liveQuote is LiveQuote.Ready)
            f.current = false; f.model.requestQuote(); runCurrent()
            assertEquals(2, f.expired); assertEquals(1, f.calls.size) // no request after the session ended
        } finally { f.close() }
    }

    @Test fun noRequestForSameCurrencyInvalidAmountOrMockDemo() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("1.234"); f.model.requestQuote(); runCurrent()
            f.model.amount("50"); f.model.to("USD"); f.model.requestQuote(); runCurrent() // USD -> USD: exact, no network
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
        val demo = Fixture(demo = true)
        try {
            assertFalse(demo.state.liveQuotesAvailable)
            demo.model.amount("50"); demo.model.requestQuote(); runCurrent()
            assertTrue(demo.calls.isEmpty()); assertEquals(LiveQuote.Idle, demo.state.liveQuote)
        } finally { demo.close() }
    }
}
