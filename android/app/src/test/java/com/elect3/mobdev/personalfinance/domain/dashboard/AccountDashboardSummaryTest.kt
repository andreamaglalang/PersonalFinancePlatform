package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** M2 account mode: completeness, page size and currency safety. Fakes only; never opens a socket. */
class AccountDashboardSummaryTest {
    private val fallback = YearMonth.of(2026, 11)

    private class Scripted(private val response: suspend (Int, Int) -> TransactionPageResult) : TransactionRepository {
        val requests = mutableListOf<Pair<Int, Int>>()
        override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
            requests += page to pageSize
            return response(page, pageSize)
        }
    }

    private fun row(id: Int, date: String, amount: String, type: TransactionType = TransactionType.Expense): Transaction =
        TransactionFixtures.items.first().copy(transactionId = id, type = type, amount = BigDecimal(amount),
            amountBase = BigDecimal(amount), currencyCode = "PHP", fxRate = BigDecimal("1.00000000"),
            transactionDate = LocalDate.parse(date), fxRateDate = LocalDate.parse(date))

    private fun page(items: List<Transaction>, page: Int, size: Int, total: Long) =
        TransactionPageResult.Success(TransactionPage(items, page, size, total))

    private fun money(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun nonPhpAccountIsUnsupportedBeforeAnyRequest() = runTest {
        val repo = Scripted { _, _ -> error("must not read") }
        assertEquals(DashboardSummaryResult.UnsupportedCurrency,
            LoadDashboardSummary(repo, accountCurrency = { "USD" })(fallback))
        assertTrue(repo.requests.isEmpty())
    }

    @Test fun unknownAccountCurrencyIsUnsupported() = runTest {
        val repo = Scripted { _, _ -> error("must not read") }
        assertEquals(DashboardSummaryResult.UnsupportedCurrency, LoadDashboardSummary(repo, accountCurrency = { null })(fallback))
    }

    @Test fun convertedRecordUsesServerPhpBaseAmountAndRetainsOriginalRow() = runTest {
        val converted = row(2, "2026-11-03", "10.00").copy(currencyCode = "USD", fxRate = BigDecimal("56.00000000"),
            amountBase = BigDecimal("560.00"))
        val repo = Scripted { p, s -> page(listOf(row(1, "2026-11-02", "50.00"), converted), p, s, 2) }
        val summary = (LoadDashboardSummary(repo, accountCurrency = { "PHP" })(fallback)
            as DashboardSummaryResult.Success).summary
        money("610.00", summary.totalExpenses)
        assertSame(converted, summary.recentTransactions.first())
        money("10.00", converted.amount)
        money("560.00", converted.amountBase)
        assertEquals("USD", converted.currencyCode)
    }

    @Test fun phpAccountReadsEveryPageAtTheConfiguredSizeBeforeProducingTotals() = runTest {
        val rows = listOf(row(3, "2026-11-05", "1000.00", TransactionType.Income), row(2, "2026-11-04", "120.50"),
            row(1, "2026-10-30", "79.50"))
        val repo = Scripted { p, s -> page(rows.drop((p - 1) * s).take(s), p, s, 3) }
        val result = LoadDashboardSummary(repo, pageSize = 2, accountCurrency = { "PHP" })(fallback)
        val summary = (result as DashboardSummaryResult.Success).summary
        assertEquals(listOf(1 to 2, 2 to 2), repo.requests)
        assertEquals(3L, summary.recordCount); assertEquals(YearMonth.of(2026, 11), summary.reportingMonth)
        money("1000.00", summary.totalIncome); money("200.00", summary.totalExpenses); money("800.00", summary.netTransactionFlow)
        money("120.50", summary.monthlyExpenses); assertEquals(2L, summary.monthlyRecordCount)
    }

    @Test fun totalChangingDuringPaginationIsInvalidNeverPartial() = runTest {
        val rows = (1..4).map { row(it, "2026-11-0${it + 1}", "1.00") }
        val repo = Scripted { p, s -> page(rows.drop((p - 1) * s).take(s), p, s, if (p == 1) 3 else 4) }
        assertTrue(LoadDashboardSummary(repo, pageSize = 2, accountCurrency = { "PHP" })(fallback)
            is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun shortNonFinalPageIsInvalidNeverPartial() = runTest {
        val repo = Scripted { p, s -> page(if (p == 1) listOf(row(1, "2026-11-02", "1.00")) else emptyList(), p, s, 3) }
        assertTrue(LoadDashboardSummary(repo, pageSize = 2, accountCurrency = { "PHP" })(fallback)
            is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun pageSizeOutsideServerLimitsIsRejected() {
        val repo = Scripted { _, _ -> error("unused") }
        assertThrows(IllegalArgumentException::class.java) { LoadDashboardSummary(repo, pageSize = 101) }
        assertThrows(IllegalArgumentException::class.java) { LoadDashboardSummary(repo, pageSize = 0) }
    }

    @Test fun realRemoteRepositoryPagesAreSummedExactlyAndExpiryIsTyped() = runTest {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", Instant.parse("2030-01-01T00:15:00Z"),
                    Instant.parse("2030-01-01T00:30:00Z"), AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        })
        auth.login("offline@example.test", "fixture-only")
        val queries = mutableListOf<String>()
        var status = 200
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { request ->
            val query = request.url.query.orEmpty(); queries += query
            val pageNumber = Regex("(?:^|&)page=(\\d+)").find(query)!!.groupValues[1].toInt()
            val items = if (pageNumber == 1) listOf(json(3, "Income", "2500.00", "2026-11-06"), json(2, "Expense", "300.25", "2026-11-05"))
                else listOf(json(1, "Expense", "99.75", "2026-11-01"))
            FinanceHttpResponse(status, """{"items":[${items.joinToString(",")}],"page":$pageNumber,"pageSize":2,"totalCount":3}""")
        })
        val summary = (LoadDashboardSummary(finance.transactions, pageSize = 2, accountCurrency = finance.baseCurrency)(fallback)
            as DashboardSummaryResult.Success).summary
        assertEquals(2, queries.size); assertTrue(queries.all { it.contains("pageSize=2") })
        money("2500.00", summary.totalIncome); money("400.00", summary.totalExpenses); money("2100.00", summary.netTransactionFlow)
        assertEquals(3L, summary.recordCount)
        status = 401
        assertEquals(DashboardSummaryResult.RepositoryFailure(TransactionFailure.SessionExpired),
            LoadDashboardSummary(finance.transactions, pageSize = 2, accountCurrency = finance.baseCurrency)(fallback))
    }

    private fun json(id: Int, type: String, amount: String, date: String) =
        """{"transactionId":$id,"clientId":"00000000-0000-4000-8000-${id.toString().padStart(12, '0')}","type":"$type",""" +
            """"categoryId":7,"categoryName":"Food","amount":$amount,"currencyCode":"PHP","fxRate":1.00000000,""" +
            """"fxRateDate":"$date","amountBase":$amount,"transactionDate":"$date","description":null,""" +
            """"createdAtUtc":"2026-11-06T01:02:03Z","billId":null,"billDueDate":null}"""
}
