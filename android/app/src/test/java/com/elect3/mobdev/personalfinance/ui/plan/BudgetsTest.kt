package com.elect3.mobdev.personalfinance.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.DemoBudgetStore
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsTest {
    @get:Rule val main = MainDispatcherRule()
    private val nov = YearMonth.of(2026, 11)
    private fun budget(id: Int, limit: String, spent: String, status: BudgetStatus = BudgetStatus.OK, currency: String = "PHP", cat: Int = id) =
        Budget(id, cat, "C$id", true, nov, BigDecimal(limit), 80, BigDecimal(spent), BigDecimal(limit) - BigDecimal(spent), status, currency)

    // ---------- Summary arithmetic ----------
    @Test fun summaryAddsServerFiguresAndDividesWhatIsLeftByDaysIncludingToday() {
        val s = budgetSummary(listOf(budget(1, "1800.00", "1849.00"), budget(2, "3000.00", "811.25")), nov, LocalDate.of(2026, 11, 7))!!
        assertEquals(BigDecimal("4800.00"), s.totalLimit); assertEquals(BigDecimal("2660.25"), s.totalSpent)
        assertEquals(BigDecimal("2139.75"), s.left)
        assertEquals(24, s.daysLeft) // Nov 7..30
        assertEquals(BigDecimal("89.16"), s.perDay) // 2139.75 / 24 = 89.156… half-up
    }

    @Test fun overspentLeavesNothingPerDayAndOtherMonthsHaveNoDailyFigure() {
        val over = budgetSummary(listOf(budget(1, "100.00", "150.00", BudgetStatus.Exceeded)), nov, LocalDate.of(2026, 11, 30))!!
        assertEquals(BigDecimal("-50.00"), over.left); assertEquals(1, over.daysLeft); assertEquals(BigDecimal("0.00"), over.perDay)
        val past = budgetSummary(listOf(budget(1, "100.00", "10.00")), nov, LocalDate.of(2026, 12, 2))!!
        assertNull(past.daysLeft); assertNull(past.perDay)
    }

    @Test fun mixedCurrenciesOrNoBudgetsProduceNoSummary() {
        assertNull(budgetSummary(emptyList(), nov, LocalDate.of(2026, 11, 7)))
        assertNull(budgetSummary(listOf(budget(1, "1.00", "0"), budget(2, "1.00", "0", currency = "USD")), nov, LocalDate.of(2026, 11, 7)))
    }

    @Test fun percentRoundsDownAndLimitsParseExactly() {
        assertEquals(99, percentUsed(budget(1, "100.00", "99.99")))
        assertEquals(100, percentUsed(budget(1, "100.00", "100.00", BudgetStatus.Warning)))
        assertEquals(102, percentUsed(budget(1, "1800.00", "1849.00", BudgetStatus.Exceeded)))
        assertEquals(BigDecimal("1250.50"), parseLimit(" 1250.50 "))
        // Same reading as every other amount field: a comma is the decimal separator, never a thousands separator.
        assertEquals(0, BigDecimal("10.50").compareTo(parseLimit("10,50")))
        listOf("0", "-5", "1.005", "abc", "", "99999999999999999.00", "1,250.50", "1e5", "+5", "₱100").forEach { assertNull(it, parseLimit(it)) }
        assertThrows(IllegalArgumentException::class.java) { BudgetLimits(BigDecimal("10"), 0) }
    }

    // ---------- Demo store: same spending and status rules as the API ----------
    @Test fun demoSpendingCountsOnlyThatMonthsExpensesInTheCategoryAndFollowsNewDemoExpenses() = runTest {
        val session = DemoSession()
        session.budgets.reset(nov)
        val food = (session.budgets.list(nov) as FinanceResult.Success).value.first { it.categoryName == "Food" }
        val expected = com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures.items
            .filter { it.type == TransactionType.Expense && it.categoryName == "Food" && YearMonth.from(it.transactionDate) == nov }
            .fold(BigDecimal.ZERO) { a, t -> a + t.amountBase }
        assertEquals(0, expected.compareTo(food.spentAmount))
        assertEquals(0, food.limitAmount.subtract(food.spentAmount).compareTo(food.remainingAmount))
        val cat = (session.categories.list(CategoryQuery(type = TransactionType.Expense, pageSize = 100)) as FinanceResult.Success).value.items.first { it.name == "Food" }
        session.ledger.add(TransactionType.Expense, cat, BigDecimal("5000.00"), LocalDate.of(2026, 11, 19), null)
        session.ledger.add(TransactionType.Expense, cat, BigDecimal("999.00"), LocalDate.of(2026, 10, 2), null) // other month: excluded
        val after = (session.budgets.list(nov) as FinanceResult.Success).value.first { it.categoryName == "Food" }
        assertEquals(0, (expected + BigDecimal("5000.00")).compareTo(after.spentAmount))
        assertEquals(BudgetStatus.Exceeded, after.status)
        session.reset()
        assertTrue((session.budgets.list(nov) as FinanceResult.Success).value.none { it.spentAmount > BigDecimal("4000") })
    }

    @Test fun demoRulesMatchTheApi() = runTest {
        val session = DemoSession(); session.budgets.reset(nov)
        val salary = (session.categories.list(CategoryQuery(type = TransactionType.Income, pageSize = 100)) as FinanceResult.Success).value.items.first()
        assertEquals(FinanceResult.Failure(FinanceFailure.InvalidInput), session.budgets.create(salary.categoryId, nov, BudgetLimits(BigDecimal("10"), 80)))
        assertEquals(FinanceResult.Failure(FinanceFailure.Conflict), session.budgets.create(3, nov, BudgetLimits(BigDecimal("10"), 80)))
        assertTrue(session.budgets.create(3, nov.plusMonths(1), BudgetLimits(BigDecimal("10"), 80)) is FinanceResult.Success)
        assertEquals(BudgetStatus.OK, DemoBudgetStore.statusOf(BigDecimal("79.99"), BigDecimal("100"), 80))
        assertEquals(BudgetStatus.Warning, DemoBudgetStore.statusOf(BigDecimal("80"), BigDecimal("100"), 80))
        assertEquals(BudgetStatus.Warning, DemoBudgetStore.statusOf(BigDecimal("100"), BigDecimal("100"), 80))
        assertEquals(BudgetStatus.Exceeded, DemoBudgetStore.statusOf(BigDecimal("100.01"), BigDecimal("100"), 80))
    }

    // ---------- API mapping ----------
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private fun row(id: Int = 4, limit: String = "1800.00", spent: String = "1849.00", remaining: String = "-49.00", status: String = "Exceeded", month: String = "2026-11-01") =
        """{"budgetId":$id,"categoryId":5,"categoryName":"Utilities","isCategoryActive":true,"periodMonth":"$month","limitAmount":$limit,"alertThresholdPct":80,"spentAmount":$spent,"remainingAmount":$remaining,"status":"$status","baseCurrency":"PHP"}"""

    @Test fun budgetJsonMapsServerFiguresAndRejectsInconsistentOnes() {
        val b = FinanceJson.budget(row())
        assertEquals(BudgetStatus.Exceeded, b.status); assertEquals(BigDecimal("-49.00"), b.remainingAmount); assertEquals(nov, b.periodMonth)
        listOf(row(remaining = "-48.00"), row(status = "Fine"), row(month = "2026-11-02"), row(limit = "0")).forEach { bad ->
            assertThrows(Exception::class.java) { FinanceJson.budget(bad) }
        }
        assertThrows(Exception::class.java) { FinanceJson.budgetPage("""{"items":[${row(month = "2026-10-01")}],"page":1,"pageSize":100,"totalCount":1}""", 1, 100, nov) }
    }

    private inner class Server {
        val gets = mutableListOf<String>(); val writes = mutableListOf<FinanceWriteRequest>()
        var getStatus = 200; var writeStatus = 201; var total = 1
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("access", "refresh", expiry, expiry.plusSeconds(1000), AuthUser(1, "T", "t@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, MutableAuthClock(expiry.minusSeconds(60)))
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { r ->
                gets += r.url.path + "?" + r.url.query
                val page = Regex("[?&]page=(\\d+)").find("?" + r.url.query)!!.groupValues[1].toInt()
                val ids = ((page - 1) * 100 + 1..minOf(page * 100, total))
                val items = ids.joinToString(",") { row(id = it) }
                FinanceHttpResponse(getStatus, """{"items":[$items],"page":$page,"pageSize":100,"totalCount":$total}""")
            },
            writeEngine = FinanceWriteEngine { r -> writes += r; FinanceHttpResponse(writeStatus, if (writeStatus in 200..201) row(limit = "2000.00", remaining = "151.00", status = "Warning") else "private") })
    }

    @Test fun remoteListReadsEveryPageForTheMonth() = runTest {
        val s = Server(); s.total = 150; s.auth.login("t@example.test", "fixture-only")
        val list = (s.repos.budgets.list(nov) as FinanceResult.Success).value
        assertEquals(150, list.size); assertEquals(2, s.gets.size)
        assertTrue(s.gets.all { it.startsWith("/api/budgets?") && it.contains("periodMonth=2026-11-01") && it.contains("pageSize=100") })
    }

    @Test fun remoteWritesUseExactlyTheApiFieldsAndMapFailures() = runTest {
        val s = Server(); s.auth.login("t@example.test", "fixture-only")
        val created = s.repos.budgets.create(5, nov, BudgetLimits(BigDecimal("2000.00"), 80))
        assertTrue(created is FinanceResult.Success)
        val post = s.writes.single()
        assertEquals(WriteMethod.POST, post.method); assertEquals("/api/budgets", post.url.path)
        assertEquals(setOf("categoryId", "periodMonth", "limitAmount", "alertThresholdPct"), JsonParser.parseString(post.body).asJsonObject.keySet())
        assertEquals("2026-11-01", JsonParser.parseString(post.body).asJsonObject["periodMonth"].asString)
        s.writeStatus = 200
        assertTrue(s.repos.budgets.update(4, BudgetLimits(BigDecimal("2000.00"), 80)) is FinanceResult.Success)
        assertEquals("/api/budgets/4", s.writes.last().url.path)
        assertEquals(setOf("limitAmount", "alertThresholdPct"), JsonParser.parseString(s.writes.last().body).asJsonObject.keySet())
        s.writeStatus = 409
        assertEquals(FinanceResult.Failure(FinanceFailure.Conflict), s.repos.budgets.delete(4))
        assertEquals(WriteMethod.DELETE, s.writes.last().method); assertNull(s.writes.last().body)
        s.getStatus = 401
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired), s.repos.budgets.list(nov))
    }

    @Test fun noSessionSendsNothing() = runTest {
        val s = Server()
        assertEquals(FinanceResult.Failure(FinanceFailure.MissingSession), s.repos.budgets.list(nov))
        assertEquals(FinanceResult.Failure(FinanceFailure.MissingSession), s.repos.budgets.delete(1))
        assertTrue(s.gets.isEmpty() && s.writes.isEmpty())
    }

    // ---------- ViewModel ----------
    private val store = ViewModelStore()
    @After fun clear() = store.clear()
    private var n = 0
    private val clock = java.time.Clock.fixed(Instant.parse("2026-11-20T03:45:00Z"), java.time.ZoneOffset.UTC)
    private fun vm(repo: BudgetRepository, cats: CategoryRepository, expired: () -> Unit = {}, currency: () -> String? = { null }) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return BudgetsViewModel(repo, cats, nov, onSessionExpired = expired, accountCurrency = currency, clock = clock) as T }
    })["b${n++}", BudgetsViewModel::class.java]

    private class FakeBudgets(var items: MutableList<Budget>) : BudgetRepository {
        var failNext: FinanceFailure? = null; var listFailure: FinanceFailure? = null; val calls = mutableListOf<String>()
        val listed = mutableListOf<YearMonth>()
        override suspend fun list(month: YearMonth): FinanceResult<List<Budget>> { listed += month
            return listFailure?.let { FinanceResult.Failure(it) } ?: FinanceResult.Success(items.filter { it.periodMonth == month }) }
        override suspend fun create(categoryId: Int, month: YearMonth, limits: BudgetLimits): FinanceResult<Budget> { calls += "create:$categoryId:${limits.limitAmount}"
            failNext?.let { failNext = null; return FinanceResult.Failure(it) }
            val b = Budget(99, categoryId, "New", true, month, limits.limitAmount, limits.alertThresholdPct, BigDecimal.ZERO, limits.limitAmount, BudgetStatus.OK, "PHP")
            items.add(b); return FinanceResult.Success(b) }
        override suspend fun update(budgetId: Int, limits: BudgetLimits): FinanceResult<Budget> { calls += "update:$budgetId"
            failNext?.let { failNext = null; return FinanceResult.Failure(it) }; return FinanceResult.Success(items.first { it.budgetId == budgetId }) }
        override suspend fun delete(budgetId: Int): FinanceResult<Unit> { calls += "delete:$budgetId"
            failNext?.let { failNext = null; return FinanceResult.Failure(it) }; items.removeAll { it.budgetId == budgetId }; return FinanceResult.Success(Unit) }
    }

    @Test fun viewModelLoadsValidatesAndRereadsAfterWrites() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00", cat = 3)))
        val model = vm(repo, DemoSession().categories)
        advanceUntilIdle()
        assertTrue(model.state.value.loaded); assertEquals(1, model.state.value.budgets.size)
        assertTrue(model.state.value.availableCategories.none { it.categoryId == 3 }) // one budget per category per month
        model.create(null, "10", 80); assertEquals(BudgetFormError.Category, model.state.value.formError)
        model.create(4, "1.005", 80); assertEquals(BudgetFormError.Amount, model.state.value.formError)
        model.create(4, "250", 80); model.create(4, "250", 80) // double tap
        advanceUntilIdle()
        assertEquals(listOf("create:4:250"), repo.calls)
        assertEquals(BudgetNotice.Created, model.state.value.notice); assertEquals(2, model.state.value.budgets.size)
    }

    @Test fun conflictsUnknownOutcomesAndBlockedDeletesAreReportedHonestly() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        repo.failNext = FinanceFailure.Conflict; model.create(4, "10", 80); advanceUntilIdle()
        assertEquals(BudgetFormError.Duplicate, model.state.value.formError)
        repo.failNext = FinanceFailure.Timeout; model.update(1, "10", 80); advanceUntilIdle()
        assertEquals(BudgetFormError.Unknown, model.state.value.formError)
        repo.failNext = FinanceFailure.Conflict; model.delete(1); advanceUntilIdle()
        assertEquals(BudgetNotice.DeleteBlocked, model.state.value.notice); assertEquals(1, model.state.value.budgets.size)
    }

    @Test fun loadErrorsRetryAndSessionExpiry() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf()).apply { listFailure = FinanceFailure.Unavailable }
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        assertEquals(FinanceFailure.Unavailable, model.state.value.error)
        repo.listFailure = null; model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.loaded && model.state.value.budgets.isEmpty())
        var expired = 0
        val gone = vm(FakeBudgets(mutableListOf()).apply { listFailure = FinanceFailure.SessionExpired }, DemoSession().categories, expired = { expired++ })
        advanceUntilIdle()
        assertEquals(1, expired); assertTrue(gone.state.value.invalidated); assertTrue(gone.state.value.budgets.isEmpty())
    }

    // ---------- Month navigation ----------
    @Test fun monthNavigationLoadsOnlyThatMonthAndStaysInRange() = runTest(main.dispatcher) {
        val oct = nov.minusMonths(1)
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00"), budget(2, "50.00", "5.00").copy(periodMonth = oct)))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        assertEquals(listOf(1), model.state.value.budgets.map { it.budgetId })
        model.selectMonth(oct)
        // The previous month's figures are never shown under the new label.
        assertEquals(oct, model.state.value.month); assertTrue(model.state.value.loading && model.state.value.budgets.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(2), model.state.value.budgets.map { it.budgetId })
        assertEquals(listOf(nov, oct), repo.listed)
        // Create targets the month on screen.
        model.create(4, "10", 80); advanceUntilIdle()
        assertEquals(oct, repo.items.last().periodMonth)
        // Bounds: 24 months back, 12 ahead.
        model.selectMonth(nov.minusMonths(BUDGET_MONTHS_BACK + 1)); model.selectMonth(nov.plusMonths(BUDGET_MONTHS_AHEAD + 1))
        assertEquals(oct, model.state.value.month)
        model.selectMonth(nov.plusMonths(BUDGET_MONTHS_AHEAD)); advanceUntilIdle()
        assertFalse(model.state.value.canGoForward); assertTrue(model.state.value.canGoBack)
    }

    @Test fun monthCannotChangeWhileSaving() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        model.update(1, "200", 80)
        assertTrue(model.state.value.saving)
        model.selectMonth(nov.plusMonths(1))
        assertEquals(nov, model.state.value.month)
        advanceUntilIdle()
    }

    // ---------- Offline / stale ----------
    @Test fun failedReloadKeepsLastFiguresReadOnlyUntilARefreshSucceeds() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        assertEquals(java.time.LocalDateTime.of(2026, 11, 20, 3, 45), model.state.value.loadedAt)
        repo.listFailure = FinanceFailure.Unavailable
        model.refresh(); advanceUntilIdle()
        with(model.state.value) {
            assertTrue(loaded); assertTrue(stale); assertEquals(FinanceFailure.Unavailable, staleReason)
            assertEquals(1, budgets.size); assertFalse(canWrite)
        }
        // Nothing is sent or queued while the figures are stale.
        model.update(1, "200", 80); model.create(4, "10", 80); model.delete(1); advanceUntilIdle()
        assertTrue(repo.calls.isEmpty()); assertEquals(BudgetFormError.Stale, model.state.value.formError)
        repo.listFailure = null
        model.refresh(); advanceUntilIdle()
        assertFalse(model.state.value.stale); assertTrue(model.state.value.canWrite)
    }

    @Test fun firstLoadFailureIsAnErrorNotStaleData() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00"))).apply { listFailure = FinanceFailure.Timeout }
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        assertEquals(FinanceFailure.Timeout, model.state.value.error); assertFalse(model.state.value.stale)
        assertTrue(model.state.value.budgets.isEmpty())
    }

    // ---------- Conflicts ----------
    @Test fun updateConflictMeansChangedElsewhereAndReloads() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        val reads = repo.listed.size
        repo.failNext = FinanceFailure.Conflict; model.update(1, "150", 80); advanceUntilIdle()
        assertEquals(BudgetFormError.Changed, model.state.value.formError)
        assertEquals(reads + 1, repo.listed.size)
    }

    @Test fun blockedDeleteReloadsTheList() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        val reads = repo.listed.size
        repo.failNext = FinanceFailure.Conflict; model.delete(1); advanceUntilIdle()
        assertEquals(BudgetNotice.DeleteBlocked, model.state.value.notice); assertEquals(reads + 1, repo.listed.size)
    }

    @Test fun alertThresholdOutsideApiRangeIsNotSent() = runTest(main.dispatcher) {
        val repo = FakeBudgets(mutableListOf(budget(1, "100.00", "20.00")))
        val model = vm(repo, DemoSession().categories); advanceUntilIdle()
        model.update(1, "100", 0); model.create(4, "100", 101); advanceUntilIdle()
        assertTrue(repo.calls.isEmpty()); assertEquals(BudgetFormError.Rejected, model.state.value.formError)
    }

    // ---------- Categories and currency ----------
    private fun cat(id: Int, active: Boolean = true, type: TransactionType = TransactionType.Expense) = Category(id, "Cat$id", type, null, active, false)

    @Test fun categoryFailureIsReportedAndEveryPageIsRead() = runTest(main.dispatcher) {
        val failing = object : CategoryRepository {
            override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> = FinanceResult.Failure(FinanceFailure.Unavailable) }
        val down = vm(FakeBudgets(mutableListOf()), failing); advanceUntilIdle()
        assertTrue(down.state.value.loaded); assertTrue(down.state.value.categoriesFailed); assertTrue(down.state.value.availableCategories.isEmpty())

        val all = (1..150).map { cat(it, active = it != 7) }
        val pages = mutableListOf<Int>()
        val paged = object : CategoryRepository {
            override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> { pages += query.page
                val slice = all.drop((query.page - 1) * query.pageSize).take(query.pageSize)
                return FinanceResult.Success(CategoryPage(slice, query.page, query.pageSize, all.size.toLong())) } }
        val model = vm(FakeBudgets(mutableListOf(budget(1, "100.00", "0.00", cat = 3))), paged); advanceUntilIdle()
        assertEquals(listOf(1, 2), pages)
        assertFalse(model.state.value.categoriesFailed)
        assertEquals(148, model.state.value.availableCategories.size) // 150 - inactive #7 - budgeted #3
    }

    @Test fun createFormUsesServerCurrencyThenAccountCurrencyAndNeverGuesses() = runTest(main.dispatcher) {
        val none = vm(FakeBudgets(mutableListOf()), DemoSession().categories); advanceUntilIdle()
        assertNull(none.state.value.formCurrency)
        val account = vm(FakeBudgets(mutableListOf()), DemoSession().categories, currency = { "USD" }); advanceUntilIdle()
        assertEquals("USD", account.state.value.formCurrency)
        val server = vm(FakeBudgets(mutableListOf(budget(1, "1.00", "0", currency = "EUR"))), DemoSession().categories, currency = { "USD" }); advanceUntilIdle()
        assertEquals("EUR", server.state.value.formCurrency)
    }

    @Test fun demoAndAccountStoresAreSeparate() = runTest(main.dispatcher) {
        val a = DemoSession(); val b = DemoSession()
        a.budgets.reset(nov); b.budgets.reset(nov)
        a.budgets.create(103, nov, BudgetLimits(BigDecimal("50"), 80)) // demo user category "Education"
        assertNotEquals((a.budgets.list(nov) as FinanceResult.Success).value.size, (b.budgets.list(nov) as FinanceResult.Success).value.size)
    }
}
