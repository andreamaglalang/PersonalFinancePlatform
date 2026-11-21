package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val clock = Clock.fixed(Instant.parse("2026-11-14T23:30:00Z"), ZoneId.of("Asia/Shanghai"))
    private class Controlled(val ignoreCancellation: Boolean = false) : CategoryRepository {
        class Request(val query: CategoryQuery, val result: CompletableDeferred<FinanceResult<CategoryPage>> = CompletableDeferred())
        val requests = mutableListOf<Request>()
        override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
            val request = Request(query).also { requests += it }
            return if (ignoreCancellation) withContext(NonCancellable) { request.result.await() } else request.result.await()
        }
    }
    private class Harness(val model: AddTransactionViewModel) {
        val store = ViewModelStore().apply { put("form", model) }
        fun close() = store.clear()
    }
    private fun form(repository: CategoryRepository = DemoCategoryRepository(), workflow: FormWorkflow = FormWorkflow.MockDemo,
        currency: String? = "PHP", current: () -> Boolean = { true }, expired: () -> Unit = {}) =
        Harness(AddTransactionViewModel(repository, workflow, currency, current, expired, clock))
    private fun reply(request: Controlled.Request, ids: IntRange = 1..1, total: Long = ids.count().toLong()) {
        request.result.complete(FinanceResult.Success(CategoryPage(ids.map {
            Category(it, "Category $it", checkNotNull(request.query.type), null, true, true)
        }, request.query.page, 50, total)))
    }
    private fun valid(model: AddTransactionViewModel) { model.amount("12.34"); model.category(1) }

    @Test fun decimalBoundariesAreExactAndInvalidInputIsNotRounded() {
        for (raw in listOf("0.01", "1", "1.", "12,34", "9999999999999999.99")) assertTrue(raw, validAmount(raw))
        for (raw in listOf("", ".", "0", "0.00", "-1", "1.001", "1e3", "NaN", "1,234.56", "10000000000000000", " 1 "))
            assertFalse(raw, validAmount(raw))
    }
    @Test fun injectedClockSetsLocalCalendarDateAndPickerRoundTripNeverShiftsDate() = runTest(main.dispatcher) {
        val f = form()
        try {
            assertEquals(LocalDate.parse("2026-11-15"), f.model.state.value.date)
            for (date in listOf(LocalDate.of(2024, 2, 29), LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)))
                assertEquals(date, dateFromPicker(dateToPicker(date)))
            f.model.date(LocalDate.of(0, 1, 1)); assertFalse(f.model.state.value.dateValid)
        } finally { f.close() }
    }
    @Test fun typeSwitchClearsCategoryAndRejectsLateOldResults() = runTest(main.dispatcher) {
        val repo = Controlled(true); val f = form(repo)
        try {
            runCurrent(); val old = repo.requests.single()
            f.model.type(TransactionType.Income); runCurrent()
            assertNull(f.model.state.value.selectedCategoryId)
            assertEquals(TransactionType.Income, repo.requests.last().query.type)
            reply(repo.requests.last(), 2..2); reply(old); runCurrent()
            assertEquals(listOf(2), f.model.state.value.categories.map { it.categoryId })
            f.model.category(1); assertNull(f.model.state.value.selectedCategoryId)
            f.model.category(2); assertTrue(f.model.state.value.categoryValid)
            f.model.type(TransactionType.Expense); assertNull(f.model.state.value.selectedCategoryId)
            runCurrent(); reply(repo.requests.last()); runCurrent()
        } finally { repo.requests.filterNot { it.result.isCompleted }.forEach { reply(it) }; f.close() }
    }
    @Test fun categoryPaginationSearchAndLoadingPreventDuplicateRequests() = runTest(main.dispatcher) {
        val repo = Controlled(); val f = form(repo)
        try {
            runCurrent(); assertTrue(f.model.state.value.loading)
            f.model.loadCategories(); assertEquals(1, repo.requests.size)
            reply(repo.requests.single(), 1..50, 51); runCurrent()
            f.model.loadCategories(); runCurrent(); assertEquals(2, repo.requests.last().query.page)
            reply(repo.requests.last(), 51..51, 51); runCurrent()
            assertEquals(51, f.model.state.value.categories.size); assertFalse(f.model.state.value.canLoadMore)
            f.model.loadCategories(); runCurrent(); assertEquals(2, repo.requests.size)
            f.model.search("category 51")
            assertEquals(listOf(51), f.model.state.value.visibleCategories.map { it.categoryId })
        } finally { f.close() }
    }
    @Test fun categoryErrorRetryAndEmptyStatesAreExplicit() = runTest(main.dispatcher) {
        val repo = Controlled(); val f = form(repo)
        try {
            runCurrent(); repo.requests.single().result.complete(FinanceResult.Failure(FinanceFailure.Unavailable)); runCurrent()
            assertEquals(FinanceFailure.Unavailable, f.model.state.value.categoryError)
            f.model.retryCategories(); runCurrent()
            reply(repo.requests.last(), IntRange.EMPTY, 0); runCurrent()
            assertTrue(f.model.state.value.categories.isEmpty()); assertFalse(f.model.state.value.loading)
            assertNull(f.model.state.value.categoryError)
        } finally { f.close() }
    }
    @Test fun wrongTypeOrInactiveChoicesCannotBecomeValidSelection() = runTest(main.dispatcher) {
        val repo = Controlled(); val f = form(repo)
        try {
            runCurrent()
            repo.requests.single().result.complete(FinanceResult.Success(CategoryPage(listOf(
                Category(1, "Wrong", TransactionType.Income, null, false, true)), 1, 50, 1)))
            runCurrent(); f.model.category(1)
            assertEquals(FinanceFailure.InvalidResponse, f.model.state.value.categoryError)
            assertNull(f.model.state.value.selectedCategoryId)
        } finally { f.close() }
    }
    @Test fun descriptionLimitDoesNotSilentlyTruncate() = runTest(main.dispatcher) {
        val f = form()
        try {
            runCurrent(); valid(f.model)
            f.model.description("x".repeat(200)); assertTrue(f.model.state.value.valid)
            f.model.description("x".repeat(201)); assertFalse(f.model.state.value.valid)
            assertEquals(201, f.model.state.value.description.length)
        } finally { f.close() }
    }
    @Test fun authenticatedSubmitNeverCompletesOrStartsPreviewEvenWhenValid() = runTest(main.dispatcher) {
        val f = form(workflow = FormWorkflow.Authenticated, currency = "USD")
        try {
            runCurrent(); valid(f.model); assertTrue(f.model.state.value.valid)
            repeat(3) { f.model.submit() }; advanceTimeBy(1000); runCurrent()
            assertFalse(f.model.state.value.submitting); assertFalse(f.model.state.value.completed)
            assertEquals("USD", f.model.state.value.currency)
        } finally { f.close() }
    }
    @Test fun demoPreviewProgressCompletionAndDuplicateGuardNeverPersistAnything() = runTest(main.dispatcher) {
        val f = form()
        try {
            runCurrent(); valid(f.model); f.model.submit(); f.model.submit()
            assertTrue(f.model.state.value.submitting)
            f.model.amount("99"); assertEquals("12.34", f.model.state.value.amount)
            advanceTimeBy(300); runCurrent(); assertTrue(f.model.state.value.completed)
            f.model.submit(); assertFalse(f.model.state.value.submitting)
            f.model.back(); assertTrue(f.model.state.value.closeRequested)
        } finally { f.close() }
    }
    @Test fun invalidSubmissionRemainsIdleAndMarksFields() = runTest(main.dispatcher) {
        val f = form()
        try {
            f.model.submit(); assertFalse(f.model.state.value.submitting)
            assertEquals(FormField.entries.toSet(), f.model.state.value.touched)
        } finally { f.close() }
    }
    @Test fun dirtyBackRequiresConfirmationAndDiscardErasesDraft() = runTest(main.dispatcher) {
        val f = form()
        try {
            f.model.amount("12"); f.model.back()
            assertTrue(f.model.state.value.discardConfirmation); assertFalse(f.model.state.value.closeRequested)
            f.model.keepEditing(); assertFalse(f.model.state.value.discardConfirmation)
            f.model.back(); f.model.discard()
            assertEquals("", f.model.state.value.amount); assertTrue(f.model.state.value.closeRequested)
        } finally { f.close() }
    }
    @Test fun pristineBackClosesWithoutConfirmation() = runTest(main.dispatcher) {
        val f = form()
        try { f.model.back(); assertTrue(f.model.state.value.closeRequested); assertFalse(f.model.state.value.discardConfirmation) }
        finally { f.close() }
    }
    @Test fun sessionReplacementRejectsLateCategoriesAndClearsDraft() = runTest(main.dispatcher) {
        var current = true
        val repo = Controlled(true); val f = form(repo, current = { current })
        try {
            runCurrent(); f.model.amount("123"); f.model.description("draft")
            current = false; reply(repo.requests.single()); runCurrent()
            assertTrue(f.model.state.value.invalidated); assertEquals("", f.model.state.value.amount)
            assertTrue(f.model.state.value.categories.isEmpty()); assertNull(f.model.state.value.currency)
        } finally { f.close() }
    }
    @Test fun explicitInvalidationAndOwnerCleanupDropLoadedDrafts() = runTest(main.dispatcher) {
        for (closeOwner in listOf(false, true)) {
            val f = form(); runCurrent(); valid(f.model)
            if (closeOwner) f.close() else f.model.invalidate()
            assertTrue(f.model.state.value.invalidated); assertEquals("", f.model.state.value.amount)
            f.model.amount("99"); f.model.submit(); assertEquals("", f.model.state.value.amount)
            f.close()
        }
    }
    @Test fun protectedCategory401UsesExistingExpiryCallbackOnlyForCurrentSession() = runTest(main.dispatcher) {
        var calls = 0
        val repo = Controlled(); val f = form(repo, expired = { calls++ })
        try {
            runCurrent(); repo.requests.single().result.complete(FinanceResult.Failure(FinanceFailure.SessionExpired)); runCurrent()
            assertEquals(1, calls); assertTrue(f.model.state.value.invalidated)
        } finally { f.close() }
    }
    @Test fun missingCurrencyAndOverprecisionDisableValidForm() = runTest(main.dispatcher) {
        val f = form(currency = null)
        try { runCurrent(); valid(f.model); assertFalse(f.model.state.value.valid) } finally { f.close() }
    }
    @Test fun demoCategoryRepositoryIsTypeFilteredPaginatedAndHasNoAuthDependency() = runTest(main.dispatcher) {
        val repo = DemoCategoryRepository()
        for (type in TransactionType.entries) {
            val response = repo.list(CategoryQuery(type = type, page = 1, pageSize = 2)) as FinanceResult.Success
            assertEquals(2, response.value.items.size)
            assertTrue(response.value.items.all { it.type == type && it.isActive })
        }
    }
}
