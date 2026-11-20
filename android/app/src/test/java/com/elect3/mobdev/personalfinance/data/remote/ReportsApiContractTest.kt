package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportResult
import com.elect3.mobdev.personalfinance.domain.reports.LoadFinancialReport
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reports end to end over the real authenticated transport and JSON parser, with an in-memory HTTP engine:
 * the exact `GET api/transactions` query, response parsing, base-currency totals and failure mapping.
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
        var respond: (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, page(emptyList(), 1, 0)) }
        override suspend fun get(request: FinanceHttpRequest): FinanceHttpResponse { requests += request; return respond(request) }
    }

    private suspend fun fixture(currency: String = "PHP"): Pair<FinanceRepositories, FakeHttp> {
        val auth = InMemoryAuthRepository(FakeAuth(currency), MutableAuthClock(expiry.minusSeconds(60)))
        val http = FakeHttp()
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, http)
        assertEquals(AuthResult.Success, auth.login("reports@example.test", "fixture-only"))
        return repos to http
    }

    private fun load(repos: FinanceRepositories) = LoadFinancialReport(repos.transactions, repos.baseCurrency)

    @Test fun sendsTheExactWindowQueryAndTotalsServerBaseAmounts() = runTest {
        val (repos, http) = fixture()
        http.respond = { FinanceHttpResponse(200, page(listOf(
            row(1, "Income", 50, "Salary", "50000.00", "PHP", "1.00000000", "2026-10-25", "50000.00", "2026-10-25"),
            row(2, "Expense", 7, "Food", "1200.50", "PHP", "1.00000000", "2026-11-03", "1200.50", "2026-11-03"),
            // 10 USD stored at 58.12345678 → 581.23 PHP by the server (FX rate date precedes the transaction).
            row(3, "Expense", 7, "Food", "10.00", "USD", "58.12345678", "2026-11-09", "581.23", "2026-11-10"),
        ), 1, 3)) }
        val result = load(repos)(YearMonth.of(2026, 11)) as FinancialReportResult.Success
        val request = http.requests.single()
        assertEquals("/api/transactions", request.url.path)
        assertEquals("page=1&pageSize=100&fromDate=2026-06-01&toDate=2026-11-30&sortBy=date&sortDirection=asc", request.url.query)
        assertEquals("Bearer reports-access", request.authorization())
        val nov = result.report.selected
        assertEquals("PHP", result.report.currency)
        assertEquals(0, BigDecimal("1781.73").compareTo(nov.totalExpenses))
        assertEquals(0, BigDecimal("0").compareTo(nov.totalIncome))
        assertEquals(listOf("USD"), nov.foreignCurrencies)
        assertEquals(0, BigDecimal("50000.00").compareTo(result.report.trend[4].income))
        assertEquals(1, nov.expenseCategories.size)
        assertEquals(2, nov.expenseCategories.single().transactionCount)
    }

    @Test fun reportUsesTheSignedInAccountsBaseCurrency() = runTest {
        val (repos, http) = fixture("USD")
        http.respond = { FinanceHttpResponse(200, page(listOf(
            row(1, "Expense", 7, "Food", "1000.00", "PHP", "0.01712345", "2026-11-02", "17.12", "2026-11-03")), 1, 1)) }
        val result = load(repos)(YearMonth.of(2026, 11)) as FinancialReportResult.Success
        assertEquals("USD", result.report.currency)
        assertEquals(0, BigDecimal("17.12").compareTo(result.report.selected.totalExpenses))
    }

    @Test fun multiPageResponsesAreFollowed() = runTest {
        val (repos, http) = fixture()
        http.respond = { req ->
            val p = Regex("page=(\\d+)").find(req.url.query)!!.groupValues[1].toInt()
            val ids = if (p == 1) 1..100 else 101..101
            FinanceHttpResponse(200, page(ids.map { row(it, "Expense", 7, "Food", "1.00", "PHP", "1.00000000", "2026-11-05", "1.00", "2026-11-05") }, p, 101))
        }
        val result = load(repos)(YearMonth.of(2026, 11)) as FinancialReportResult.Success
        assertEquals(2, http.requests.size)
        assertEquals(101, result.report.selected.transactionCount)
    }

    @Test fun unauthorizedAndServerErrorsMapToSessionExpiryAndUnavailable() = runTest {
        val (repos, http) = fixture()
        http.respond = { FinanceHttpResponse(500, "private") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable), load(repos)(YearMonth.of(2026, 11)))
        http.respond = { FinanceHttpResponse(401, "private") }
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.SessionExpired), load(repos)(YearMonth.of(2026, 11)))
    }

    @Test fun malformedOrOutOfContractResponsesAreRejected() = runTest {
        val (repos, http) = fixture()
        // Row outside the requested window: the parser rejects it, so no partial report is produced.
        http.respond = { FinanceHttpResponse(200, page(listOf(
            row(1, "Expense", 7, "Food", "1.00", "PHP", "1.00000000", "2026-12-01", "1.00", "2026-12-01")), 1, 1)) }
        assertTrue(load(repos)(YearMonth.of(2026, 11)) is FinancialReportResult.RepositoryFailure)
        // A base amount that does not match amount × stored rate is withheld, not repaired.
        http.respond = { FinanceHttpResponse(200, page(listOf(
            row(1, "Expense", 7, "Food", "10.00", "USD", "58.00000000", "2026-11-09", "10.00", "2026-11-10")), 1, 1)) }
        assertTrue(load(repos)(YearMonth.of(2026, 11)) is FinancialReportResult.InvalidData)
    }

    companion object {
        private val expiry = Instant.parse("2030-01-01T00:15:00Z")
        private fun row(id: Int, type: String, categoryId: Int, category: String, amount: String, currency: String, rate: String,
                        rateDate: String, base: String, date: String) = """{"transactionId":$id,
            "clientId":"00000000-0000-4000-8000-${id.toString().padStart(12, '0')}","type":"$type","categoryId":$categoryId,
            "categoryName":"$category","amount":$amount,"currencyCode":"$currency","fxRate":$rate,"fxRateDate":"$rateDate",
            "amountBase":$base,"transactionDate":"$date","description":null,"createdAtUtc":"2026-11-15T01:02:03Z",
            "billId":null,"billDueDate":null}"""
        private fun page(rows: List<String>, page: Int, total: Int) =
            """{"items":[${rows.joinToString(",")}],"page":$page,"pageSize":100,"totalCount":$total}"""
    }
}
