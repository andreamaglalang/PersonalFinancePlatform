package com.elect3.mobdev.personalfinance.ui.transactions.edit

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.domain.currency.RateImpact
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditTransactionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val day = LocalDate.of(2026, 11, 15)
    private val v1 = "A".repeat(64)
    private val v2 = "B".repeat(64)
    private fun usd(billId: Int? = null, currency: String = "USD", version: String? = v1, amount: String = "10.00") = Transaction(7,
        UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"),
        TransactionType.Expense, 2, "Travel", BigDecimal(amount), currency, BigDecimal("58.12345678"), day.minusDays(1), BigDecimal("581.23"),
        day, "Taxi", Instant.parse("2026-11-15T01:02:03Z"), billId, billId?.let { day }, version)

    private inner class Fixture(original: Transaction = usd(), var reply: (Int, TransactionDraft) -> FinanceResult<Transaction> = { id, d -> ok(id, d) }) {
        val calls = mutableListOf<Pair<Int, TransactionDraft>>()
        val versions = mutableListOf<String>()
        var getReply: () -> FinanceResult<Transaction> = { FinanceResult.Success(usd(version = v2, amount = "20.00")) }
        var deleteReply: () -> FinanceResult<Unit> = { FinanceResult.Success(Unit) }
        val deletes = mutableListOf<Transaction>()
        val updates = object : TransactionUpdateRepository {
            override suspend fun update(transactionId: Int, draft: TransactionDraft, version: String): FinanceResult<Transaction> {
                calls += transactionId to draft; versions += version; return reply(transactionId, draft)
            }
            override suspend fun get(transactionId: Int) = getReply()
            override suspend fun delete(transaction: Transaction): FinanceResult<Unit> { deletes += transaction; return deleteReply() }
        }
        var current = true; var expired = 0
        val categories = object : CategoryRepository {
            override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> = FinanceResult.Success(CategoryPage(listOf(
                Category(2, "Travel", TransactionType.Expense, null, true, false), Category(5, "Food", TransactionType.Expense, null, true, false),
                Category(9, "Old", TransactionType.Expense, null, false, false)), query.page, query.pageSize, 3))
        }
        val model = EditTransactionViewModel(original, "PHP", updates, categories, { current }, { expired++ })
        val store = ViewModelStore().apply { put("edit", model) }
        val state get() = model.state.value
        fun close() = store.clear()
    }
    /** The API's answer: amount-only edits keep the stored rate; other edits get a (fake) newly validated rate. */
    private fun ok(id: Int, d: TransactionDraft): FinanceResult<Transaction> {
        val keep = d.currencyCode == "USD" && d.transactionDate == day
        val rate = if (d.currencyCode == "PHP") BigDecimal("1.00000000") else if (keep) BigDecimal("58.12345678") else BigDecimal("63.00000000")
        return FinanceResult.Success(Transaction(id, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), d.type, d.categoryId, "Travel",
            d.amount, d.currencyCode, rate, if (keep) day.minusDays(1) else d.transactionDate, d.amount.multiply(rate).setScale(2, RoundingMode.HALF_UP),
            d.transactionDate, d.description, Instant.parse("2026-11-15T01:02:03Z"), null, null, v2))
    }

    @Test fun opensWithTheStoredValuesAndNothingToSave() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            runCurrent()
            assertEquals("10.00", f.state.amount); assertEquals("USD", f.state.currency); assertEquals(day, f.state.date)
            assertFalse(f.state.changed); assertFalse(f.state.canSave); assertEquals(RateImpact.Unchanged, f.state.impact)
            assertEquals(listOf(2, 5), f.state.categories.map { it.categoryId }) // inactive categories are not offered
            f.model.submit(); runCurrent(); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun amountOnlyEditKeepsTheStoredRateAndSendsTheSameCurrencyAndDate() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("12,50")
            assertEquals(RateImpact.KeepsStoredRate(BigDecimal("58.12345678"), day.minusDays(1), BigDecimal("726.54")), f.state.impact)
            f.model.submit(); runCurrent()
            val (id, draft) = f.calls.single()
            assertEquals(v1, f.versions.single()) // the version that was read is sent
            assertEquals(7, id); assertEquals("USD", draft.currencyCode); assertEquals(day, draft.transactionDate)
            assertEquals(BigDecimal("12.50"), draft.amount); assertEquals(TransactionType.Expense, draft.type); assertEquals("Taxi", draft.description)
            val saved = checkNotNull(f.state.saved)
            assertEquals(BigDecimal("58.12345678"), saved.fxRate); assertEquals(day.minusDays(1), saved.fxRateDate)
            assertEquals(BigDecimal("726.54"), saved.amountBase) // the server value equals the previewed one
            f.model.amount("13"); assertEquals("12,50", f.state.amount) // confirmed: no further edits in this dialog
        } finally { f.close() }
    }

    @Test fun currencyOrDateChangesRequireANewServerRate() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.currency("EUR"); assertEquals(RateImpact.NeedsNewRate, f.state.impact)
            f.model.currency("USD"); f.model.date(day.minusDays(3)); assertEquals(RateImpact.NeedsNewRate, f.state.impact)
            f.model.date(day); f.model.currency("PHP"); assertEquals(RateImpact.SameCurrency, f.state.impact)
            f.model.currency("EUR"); f.model.submit(); runCurrent()
            assertEquals("EUR", f.calls.single().second.currencyCode)
            assertEquals(BigDecimal("63.00000000"), checkNotNull(f.state.saved).fxRate)
            f.model.currency("XYZ"); assertEquals("EUR", f.state.currency)
        } finally { f.close() }
    }

    @Test fun aStoredCurrencyOutsideTheOfferedListStaysSelectable() = runTest(main.dispatcher) {
        val f = Fixture(usd(currency = "NZD"))
        try {
            assertEquals("NZD", f.state.currency)
            assertTrue(f.state.currencyOptions.any { it.code == "NZD" }); assertEquals("PHP", f.state.currencyOptions.first().code)
            f.model.amount("11.00"); assertTrue(f.state.canSave)
        } finally { f.close() }
    }

    @Test fun failuresAreBoundedAndNeverPretendNothingChangedAfterATimeout() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            f.model.amount("12.00")
            for ((reason, expected) in listOf(FinanceFailure.Unavailable to EditFailure.Offline, FinanceFailure.InvalidInput to EditFailure.Rejected,
                FinanceFailure.NotFound to EditFailure.NotFound,
                FinanceFailure.Timeout to EditFailure.Unknown, FinanceFailure.InvalidResponse to EditFailure.Unknown)) {
                f.reply = { _, _ -> FinanceResult.Failure(reason) }
                f.model.submit(); runCurrent()
                assertEquals(expected, f.state.failure); assertNull(f.state.saved); assertFalse(f.state.saving)
            }
            // A retry sends the same values (PUT is idempotent for identical bodies).
            assertEquals(1, f.calls.map { it.second }.distinct().size)
        } finally { f.close() }
    }

    @Test fun sessionExpiryInvalidatesAndRoutesToExpiry() = runTest(main.dispatcher) {
        val f = Fixture(reply = { _, _ -> FinanceResult.Failure(FinanceFailure.SessionExpired) })
        try {
            f.model.amount("12.00"); f.model.submit(); runCurrent()
            assertTrue(f.state.invalidated); assertEquals(1, f.expired)
            f.current = false; f.model.amount("1.00"); assertEquals("12.00", f.state.amount)
        } finally { f.close() }
    }

    @Test fun billPaymentsAndInvalidAmountsCannotBeSubmitted() = runTest(main.dispatcher) {
        val bill = Fixture(usd(billId = 3))
        try {
            assertTrue(bill.state.locked); bill.model.amount("12.00"); bill.model.submit(); runCurrent()
            assertTrue(bill.calls.isEmpty())
        } finally { bill.close() }
        val f = Fixture()
        try {
            f.model.amount("12.345"); assertFalse(f.state.canSave); f.model.submit(); runCurrent(); assertTrue(f.calls.isEmpty())
            f.model.amount("12.34"); f.model.category(9); assertEquals(2, f.state.categoryId) // inactive category refused
            runCurrent(); f.model.category(5); assertEquals(5, f.state.categoryId); assertEquals("Food", f.state.categoryName)
        } finally { f.close() }
    }

    @Test fun aStaleVersionConflictBlocksSavingUntilReloadShowsTheLatestVersion() = runTest(main.dispatcher) {
        val f = Fixture(reply = { _, _ -> FinanceResult.Failure(FinanceFailure.Conflict) })
        try {
            f.model.amount("12.00"); f.model.submit(); runCurrent()
            assertEquals(EditFailure.Conflict, f.state.failure); assertTrue(f.state.stale); assertTrue(f.state.needsReload)
            assertNull(f.state.saved); assertEquals("12.00", f.state.amount) // the user's values are kept, nothing overwritten
            assertFalse(f.state.canSave); f.model.submit(); runCurrent(); assertEquals(1, f.calls.size) // never resent blindly
            assertTrue(f.state.canReload)
            f.model.reload(); runCurrent()
            assertFalse(f.state.stale); assertNull(f.state.failure); assertFalse(f.state.reloading)
            assertEquals(v2, f.state.original.version); assertEquals("20.00", f.state.amount) // the other device's change is shown
            f.reply = { id, d -> ok(id, d) }
            f.model.amount("21.00"); f.model.submit(); runCurrent()
            assertEquals(v2, f.versions.last()); assertNotNull(f.state.saved)
        } finally { f.close() }
    }

    @Test fun aFailedReloadKeepsTheConflictAndCanBeRetried() = runTest(main.dispatcher) {
        val f = Fixture(reply = { _, _ -> FinanceResult.Failure(FinanceFailure.Conflict) })
        try {
            f.model.amount("12.00"); f.model.submit(); runCurrent()
            f.getReply = { FinanceResult.Failure(FinanceFailure.Unavailable) }
            f.model.reload(); runCurrent()
            assertEquals(EditFailure.Offline, f.state.failure); assertTrue(f.state.stale); assertTrue(f.state.canReload)
            f.getReply = { FinanceResult.Failure(FinanceFailure.SessionExpired) }
            f.model.reload(); runCurrent()
            assertTrue(f.state.invalidated); assertEquals(1, f.expired)
        } finally { f.close() }
    }

    @Test fun aCopyWithoutAVersionCannotBeSubmittedUntilReloaded() = runTest(main.dispatcher) {
        val f = Fixture(usd(version = null))
        try {
            f.model.amount("12.00")
            assertTrue(f.state.needsReload); assertFalse(f.state.canSave)
            f.model.submit(); runCurrent(); assertTrue(f.calls.isEmpty())
            f.model.reload(); runCurrent()
            assertFalse(f.state.needsReload); assertEquals(v2, f.state.original.version)
        } finally { f.close() }
    }

    @Test fun deleteMapsEveryServerAnswer() = runTest(main.dispatcher) {
        for ((reply, expected) in listOf<Pair<FinanceResult<Unit>, Any>>(
            FinanceResult.Success(Unit) to DeleteOutcome.Deleted,
            FinanceResult.Failure(FinanceFailure.NotFound) to DeleteOutcome.AlreadyDeleted,
            FinanceResult.Failure(FinanceFailure.Conflict) to DeleteFailure.BillPayment,
            FinanceResult.Failure(FinanceFailure.Unavailable) to DeleteFailure.Offline,
            FinanceResult.Failure(FinanceFailure.Timeout) to DeleteFailure.Unknown)) {
            val f = Fixture()
            try {
                f.deleteReply = { reply }
                f.model.delete(); runCurrent()
                assertEquals(7, f.deletes.single().transactionId); assertFalse(f.state.deleting)
                if (expected is DeleteOutcome) { assertEquals(expected, f.state.deleted); assertFalse(f.state.canDelete); assertFalse(f.state.editable) }
                else { assertEquals(expected, f.state.deleteFailure); assertNull(f.state.deleted); assertTrue(f.state.canDelete) } // item kept
            } finally { f.close() }
        }
        val f = Fixture(reply = { _, _ -> FinanceResult.Failure(FinanceFailure.SessionExpired) })
        try {
            f.deleteReply = { FinanceResult.Failure(FinanceFailure.SessionExpired) }
            f.model.delete(); runCurrent(); assertTrue(f.state.invalidated); assertEquals(1, f.expired)
        } finally { f.close() }
    }

    @Test fun billPaymentsCannotBeDeletedFromTheDevice() = runTest(main.dispatcher) {
        val f = Fixture(usd(billId = 3))
        try { assertFalse(f.state.canDelete); f.model.delete(); runCurrent(); assertTrue(f.deletes.isEmpty()) } finally { f.close() }
    }
}
