package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportResult
import com.elect3.mobdev.personalfinance.domain.reports.LoadServerReport
import com.elect3.mobdev.personalfinance.domain.repository.BudgetStatus
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signed-in Insights over the real authenticated transport and JSON parser, with an in-memory HTTP engine:
 * C6 `GET api/reports/monthly` per month (server totals, savings contributed and budgets shown unchanged),
 * C4 `GET api/reports/summaries`, strict validation and failure mapping.
 */
class ReportsApiContractTest {
    private class FakeAuth(val currency: String) : AuthApi {
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
            AuthPayload("reports-access", "reports-refresh", expiry, expiry.plusSeconds(1000),
                AuthUser(3, "Reports", "reports@example.test", currency, listOf("User"))))
        override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeHttp : FinanceHttpEngine {
        val requests = mutableListOf<FinanceHttpRequest>()
        var respond: (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, emptyReport(monthOf(it))) }
        override suspend fun get(request: FinanceHttpRequest): FinanceHttpResponse { requests += request; return respond(request) }
    }

    private suspend fun fixture(currency: String = "PHP"): Pair<FinanceRepositories, FakeHttp> {
        val auth = InMemoryAuthRepository(FakeAuth(currency), MutableAuthClock(expiry.minusSeconds(60)))
        val http = FakeHttp()
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, http)
        assertEquals(AuthResult.Success, auth.login("reports@example.test", "fixture-only"))
        return repos to http
    }

    private val nov = YearMonth.of(2026, 11)

    @Test fun reportShowsTheServersTotalsSavingsAndBudgetsForEveryTrendMonth() = runTest {
        val (repos, http) = fixture()
        http.respond = { req -> FinanceHttpResponse(200, if (monthOf(req) == "2026-11-01") NOV_REPORT else emptyReport(monthOf(req))) }
        val result = LoadServerReport(repos.reports)(nov) as FinancialReportResult.Success
        assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01", "2026-11-01"), http.requests.map { monthOf(it) })
        http.requests.forEach {
            assertEquals("/api/reports/monthly", it.url.path)
            assertEquals("Bearer reports-access", it.authorization())
        }
        val selected = result.report.selected
        assertEquals("PHP", result.report.currency)
        assertEquals(0, BigDecimal("50000.00").compareTo(selected.totalIncome))
        assertEquals(0, BigDecimal("1781.73").compareTo(selected.totalExpenses))
        assertEquals(0, BigDecimal("48218.27").compareTo(selected.netBalance))
        assertEquals(0, BigDecimal("2500.00").compareTo(selected.savingsContributed))
        assertEquals(3, selected.transactionCount)
        assertEquals(listOf("Food", "Transport"), selected.expenseCategories.map { it.categoryName })
        val budget = selected.budgets.single()
        assertEquals("Food", budget.categoryName)
        assertEquals(0, BigDecimal("1500.00").compareTo(budget.spentAmount))
        assertEquals(BudgetStatus.Exceeded, budget.status)
        assertEquals(6, result.report.trend.size)
        assertEquals(0, result.report.trend.first().transactionCount)
    }

    @Test fun inconsistentReportsAreRejected() = runTest {
        val (repos, http) = fixture()
        for (bad in listOf(
            NOV_REPORT.replace("\"netCashFlow\":48218.27", "\"netCashFlow\":48218.28"), // net != income − expenses
            NOV_REPORT.replace("\"total\":1200.50", "\"total\":1200.00"),              // categories don't add up
            NOV_REPORT.replace("\"transactionCount\":3", "\"transactionCount\":4"),    // counts don't add up
            NOV_REPORT.replace("\"month\":\"2026-11-01\"", "\"month\":\"2026-10-01\""), // wrong month
            NOV_REPORT.replace("\"savingsContributed\":2500.00", "\"savingsContributed\":-1"),
            NOV_REPORT.replace("\"remainingAmount\":-500.00", "\"remainingAmount\":500.00"), // budget remaining
            NOV_REPORT.replace("\"periodMonth\":\"2026-11-01\"", "\"periodMonth\":\"2026-10-01\""),
            NOV_REPORT.replace("\"budgets\":[", "\"other\":["), // missing budgets
        )) {
            http.respond = { req -> FinanceHttpResponse(200, if (monthOf(req) == "2026-11-01") bad else emptyReport(monthOf(req))) }
            assertTrue(bad, LoadServerReport(repos.reports)(nov) is FinancialReportResult.InvalidData)
        }
    }

    @Test fun mixedCurrenciesAcrossTrendMonthsAreWithheld() = runTest {
        val (repos, http) = fixture()
        http.respond = { req -> FinanceHttpResponse(200, emptyReport(monthOf(req), if (monthOf(req) == "2026-06-01") "USD" else "PHP")) }
        assertTrue(LoadServerReport(repos.reports)(nov) is FinancialReportResult.InvalidData)
    }

    @Test fun badRequestUnauthorizedAndUnavailableMapToReportFailures() = runTest {
        val (repos, http) = fixture()
        http.respond = { FinanceHttpResponse(400, "private") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable), LoadServerReport(repos.reports)(nov))
        http.respond = { FinanceHttpResponse(503, "private") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable), LoadServerReport(repos.reports)(nov))
        http.respond = { throw IOException("offline") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable), LoadServerReport(repos.reports)(nov))
        http.respond = { FinanceHttpResponse(401, "private") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.SessionExpired), LoadServerReport(repos.reports)(nov))
    }

    @Test fun summariesAreReadNewestFirstAndValidated() = runTest {
        val (repos, http) = fixture()
        http.respond = { FinanceHttpResponse(200, summaries(snapshot("2026-10-01", "100.00", "40.00", "60.00"), snapshot("2026-09-01", "0", "0", "0"))) }
        val items = (repos.reports.summaries() as FinanceResult.Success).value
        assertEquals("/api/reports/summaries", http.requests.single().url.path)
        assertEquals("page=1&pageSize=12", http.requests.single().url.query)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), items.map { it.month })
        assertEquals(0, BigDecimal("12.50").compareTo(items.first().savingsAllocated))
        assertEquals(Instant.parse("2026-11-01T00:30:00Z"), items.first().generatedAtUtc)
        http.respond = { FinanceHttpResponse(200, summaries()) }
        assertEquals(emptyList<Any>(), (repos.reports.summaries() as FinanceResult.Success).value)
        for (bad in listOf(
            summaries(snapshot("2026-10-01", "100.00", "40.00", "61.00")),                       // net mismatch
            summaries(snapshot("2026-09-01", "1", "1", "0"), snapshot("2026-10-01", "1", "1", "0")), // oldest first
            summaries(snapshot("2026-10-02", "1", "1", "0")),                                    // not a month start
        )) {
            http.respond = { FinanceHttpResponse(200, bad) }
            assertEquals(FinanceResult.Failure(FinanceFailure.InvalidResponse), repos.reports.summaries())
        }
        http.respond = { FinanceHttpResponse(503, "") }
        assertEquals(FinanceResult.Failure(FinanceFailure.Unavailable), repos.reports.summaries())
    }

    companion object {
        private val expiry = Instant.parse("2030-01-01T00:15:00Z")
        private fun monthOf(req: FinanceHttpRequest) = Regex("month=([0-9-]+)").find(req.url.query)!!.groupValues[1]
        private fun emptyReport(month: String, currency: String = "PHP") = """{"month":"$month","baseCurrency":"$currency",
            "totalIncome":0,"totalExpenses":0,"netCashFlow":0,"transactionCount":0,"savingsContributed":0,
            "expenseByCategory":[],"incomeByCategory":[],"budgets":[]}"""
        private val NOV_REPORT = """{"month":"2026-11-01","baseCurrency":"PHP","totalIncome":50000.00,"totalExpenses":1781.73,
            "netCashFlow":48218.27,"transactionCount":3,"savingsContributed":2500.00,
            "expenseByCategory":[{"categoryId":7,"categoryName":"Food","total":1200.50,"transactionCount":1},
                {"categoryId":8,"categoryName":"Transport","total":581.23,"transactionCount":1}],
            "incomeByCategory":[{"categoryId":50,"categoryName":"Salary","total":50000.00,"transactionCount":1}],
            "budgets":[{"budgetId":4,"categoryId":7,"categoryName":"Food","isCategoryActive":true,"periodMonth":"2026-11-01",
                "limitAmount":1000.00,"alertThresholdPct":80,"spentAmount":1500.00,"remainingAmount":-500.00,
                "status":"Exceeded","baseCurrency":"PHP"}]}"""
        private fun snapshot(month: String, income: String, expense: String, net: String) = """{"summaryMonth":"$month",
            "totalIncome":$income,"totalExpense":$expense,"netCashFlow":$net,"savingsAllocated":12.50,
            "generatedAtUtc":"2026-11-01T00:30:00","baseCurrency":"PHP"}"""
        private fun summaries(vararg rows: String) = """{"items":[${rows.joinToString(",")}],"page":1,"pageSize":12,"totalCount":${rows.size}}"""
    }
}
