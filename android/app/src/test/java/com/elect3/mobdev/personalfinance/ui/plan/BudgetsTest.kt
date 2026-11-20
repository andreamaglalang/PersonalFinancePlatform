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
        assertEquals(BigDecimal("1250.50"), parseLimit(" 1,250.50 "))
        listOf("0", "-5", "1.005", "abc", "", "99999999999999999.00").forEach { assertNull(it, parseLimit(it)) }
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
    private fun vm(repo: BudgetRepository, cats: CategoryRepository, expired: () -> Unit = {}) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return BudgetsViewModel(repo, cats, nov, onSessionExpired = expired) as T }
    })["b${n++}", BudgetsViewModel::class.java]

    private class FakeBudgets(var items: MutableList<Budget>) : BudgetRepository {
        var failNext: FinanceFailure? = null; var listFailure: FinanceFailure? = null; val calls = mutableListOf<String>()
        override suspend fun list(month: YearMonth) = listFailure?.let { FinanceResult.Failure(it) } ?: FinanceResult.Success(items.toList())
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
        val gone = vm(FakeBudgets(mutableListOf()).apply { listFailure = FinanceFailure.SessionExpired }, DemoSession().categories) { expired++ }
        advanceUntilIdle()
        assertEquals(1, expired); assertTrue(gone.state.value.invalidated); assertTrue(gone.state.value.budgets.isEmpty())
    }

    @Test fun demoAndAccountStoresAreSeparate() = runTest(main.dispatcher) {
        val a = DemoSession(); val b = DemoSession()
        a.budgets.reset(nov); b.budgets.reset(nov)
        a.budgets.create(103, nov, BudgetLimits(BigDecimal("50"), 80)) // demo user category "Education"
        assertNotEquals((a.budgets.list(nov) as FinanceResult.Success).value.size, (b.budgets.list(nov) as FinanceResult.Success).value.size)
    }
}
