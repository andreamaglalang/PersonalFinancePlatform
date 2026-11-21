package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Server conversion snapshots only; no HTTP, SQL, credentials or rate provider. */
class PhpAccountBaseAmountTest {
    private val month = YearMonth.of(2026, 11)
    private fun row(id: Int, amount: String = "10.00", base: String = "560.00", currency: String = "USD",
        rate: String = "56.00000000", type: TransactionType = TransactionType.Expense,
        date: String = "2026-11-03", fxDate: String = date, category: Int = 3): Transaction =
        TransactionFixtures.items.first().copy(transactionId = id, amount = BigDecimal(amount), amountBase = BigDecimal(base),
            currencyCode = currency, fxRate = BigDecimal(rate), type = type, transactionDate = LocalDate.parse(date),
            fxRateDate = LocalDate.parse(fxDate), categoryId = category, categoryName = if (category == 3) "Food" else "Transport")
    private class Scripted(private val response: suspend (Int, Int) -> TransactionPageResult) : TransactionRepository {
        val requests = mutableListOf<Pair<Int, Int>>()
        override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
            requests += page to pageSize
            return response(page, pageSize)
        }
    }
    private fun pages(rows: List<Transaction>) = Scripted { p, s ->
        TransactionPageResult.Success(TransactionPage(rows.drop((p - 1) * s).take(s), p, s, rows.size.toLong()))
    }
    private fun money(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun phpOnlyAccountAndMockContractHaveTheSameFigures() = runTest {
        val rows = listOf(row(1, "1000.25", "1000.25", "PHP", "1", TransactionType.Income),
            row(2, "20.10", "20.10", "PHP", "1"))
        val account = (LoadDashboardSummary(pages(rows), accountCurrency = { "PHP" })(month) as DashboardSummaryResult.Success).summary
        val mock = DashboardSummaryCalculator().calculate(rows, month)
        money("1000.25", account.totalIncome); money("20.10", account.totalExpenses)
        assertEquals(mock.totalIncome, account.totalIncome); assertEquals(mock.totalExpenses, account.totalExpenses)
        assertEquals("PHP", account.reportingCurrency)
    }

    @Test fun mixedSourceCurrenciesUseBaseAmountsAcrossPagesMonthsAndCategoryRankings() = runTest {
        val rows = listOf(
            row(1, "1000.25", "1000.25", "PHP", "1", TransactionType.Income),
            row(2, "10.00", "560.00", "USD", "56", TransactionType.Income),
            row(3, "2.50", "160.00", "EUR", "64"),
            row(4, "20.00", "20.00", "PHP", "1"),
            row(5, "1.00", "56.00", "USD", "56", date = "2026-10-31", category = 4),
        )
        val original = rows.toList()
        val repo = pages(rows)
        val summary = (LoadDashboardSummary(repo, pageSize = 2, accountCurrency = { "PHP" })(month) as DashboardSummaryResult.Success).summary
        assertEquals(listOf(1 to 2, 2 to 2, 3 to 2), repo.requests)
        money("1560.25", summary.totalIncome); money("236.00", summary.totalExpenses)
        money("1324.25", summary.netTransactionFlow); money("180.00", summary.monthlyExpenses)
        money("1380.25", summary.monthlyNetTransactionFlow)
        assertEquals(5L, summary.recordCount); assertEquals(4L, summary.monthlyRecordCount)
        assertEquals(listOf(3, 4), summary.expenseCategoryTotals.map { it.categoryId })
        money("180.00", summary.expenseCategoryTotals.first().total)
        money("56.00", summary.expenseCategoryTotals.last().total)
        money("180.00", summary.topExpenseCategories.single().total)
        money("236.00", summary.expenseCategoryTotals.fold(BigDecimal.ZERO) { a, b -> a + b.total })
        assertEquals(original, rows)
        summary.recentTransactions.forEach { recent -> assertSame(rows.single { it.transactionId == recent.transactionId }, recent) }
        assertEquals("EUR", summary.recentTransactions.single { it.transactionId == 3 }.currencyCode)
    }

    @Test fun serverHalfUpRoundingAndRoundedZeroBaseAmountAreAcceptedWithoutChangingSourceValues() = runTest {
        val half = row(1, "0.01", "0.01", rate = "0.5")
        val zero = row(2, "0.01", "0.00", rate = "0.1")
        val summary = (LoadDashboardSummary(pages(listOf(half, zero)), accountCurrency = { "PHP" })(month) as DashboardSummaryResult.Success).summary
        money("0.01", summary.totalExpenses)
        money("0.01", zero.amount); money("0.00", zero.amountBase)
        assertEquals(2L, summary.recordCount)
    }

    @Test fun historicalFxDateIsAcceptedForForeignTransactionsButNoNewRateIsCalculated() = runTest {
        val foreign = row(1, fxDate = "2026-11-02")
        val summary = (LoadDashboardSummary(pages(listOf(foreign)), accountCurrency = { "PHP" })(month) as DashboardSummaryResult.Success).summary
        money("560.00", summary.totalExpenses)
        assertSame(foreign, summary.recentTransactions.single())
        assertEquals(LocalDate.of(2026, 11, 2), foreign.fxRateDate)
    }

    @Test fun invalidAmountsRatesAndSnapshotRelationshipsFailClosed() = runTest {
        val valid = row(1)
        val invalid = listOf(
            valid.copy(amountBase = BigDecimal("-0.01")),
            valid.copy(amountBase = BigDecimal("560.001")),
            valid.copy(amountBase = BigDecimal("10000000000000000.00")),
            valid.copy(amountBase = BigDecimal("561.00")),
            valid.copy(amount = BigDecimal.ZERO), valid.copy(amount = BigDecimal("-1.00")),
            valid.copy(amount = BigDecimal("10.001")),
            valid.copy(fxRate = BigDecimal.ZERO), valid.copy(fxRate = BigDecimal("-56")),
            valid.copy(fxRate = BigDecimal("56.000000001")),
            valid.copy(fxRate = BigDecimal("10000000000")),
            valid.copy(currencyCode = "usd"),
            valid.copy(fxRateDate = valid.transactionDate.plusDays(1)),
            valid.copy(currencyCode = "PHP"),
            row(1, "10.00", "10.00", "PHP", "1", fxDate = "2026-11-02"),
        )
        for (transaction in invalid) {
            assertTrue(LoadDashboardSummary(pages(listOf(transaction)), accountCurrency = { "PHP" })(month)
                is DashboardSummaryResult.InvalidSnapshot)
        }
    }

    @Test fun demoStillRejectsConvertedRowsAndDoesNotAdoptTheAccountPolicy() = runTest {
        assertThrows(IllegalArgumentException::class.java) { DashboardSummaryCalculator().calculate(listOf(row(1)), month) }
        assertTrue(LoadDashboardSummary(pages(listOf(row(1))))(month) is DashboardSummaryResult.InvalidSnapshot)
        val fixture = DashboardSummaryCalculator().calculate(TransactionFixtures.items, month)
        money("46250.75", fixture.totalIncome); money("10202.50", fixture.totalExpenses)
    }

    @Test fun duplicateIdsAcrossConvertedPagesNeverProducePartialTotals() = runTest {
        val repo = Scripted { p, s -> TransactionPageResult.Success(TransactionPage(listOf(row(1)), p, s, 2)) }
        assertTrue(LoadDashboardSummary(repo, pageSize = 1, accountCurrency = { "PHP" })(month) is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun laterPageSessionExpiryIsPreservedForConvertedTransactions() = runTest {
        val repo = Scripted { p, s -> if (p == 1) TransactionPageResult.Success(TransactionPage(listOf(row(1)), p, s, 2))
            else TransactionPageResult.Failure(TransactionFailure.SessionExpired) }
        assertEquals(DashboardSummaryResult.RepositoryFailure(TransactionFailure.SessionExpired),
            LoadDashboardSummary(repo, pageSize = 1, accountCurrency = { "PHP" })(month))
    }

    @Test fun accountCurrencyChangingDuringPaginationFailsClosed() = runTest {
        var currency = "PHP"
        val repo = Scripted { p, s ->
            if (p == 2) currency = "USD"
            TransactionPageResult.Success(TransactionPage(listOf(row(p)), p, s, 2))
        }
        assertEquals(DashboardSummaryResult.UnsupportedCurrency,
            LoadDashboardSummary(repo, pageSize = 1, accountCurrency = { currency })(month))
    }

    @Test fun cancellationDuringConvertedPaginationPropagates() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repo = Scripted { p, s -> if (p == 1) TransactionPageResult.Success(TransactionPage(listOf(row(1)), p, s, 2))
            else { entered.complete(Unit); CompletableDeferred<TransactionPageResult>().await() } }
        val job = async { LoadDashboardSummary(repo, pageSize = 1, accountCurrency = { "PHP" })(month) }
        entered.await(); job.cancel(); job.join()
        try { job.await(); fail("Cancelled read must not return a summary") } catch (_: CancellationException) { assertTrue(job.isCancelled) }
    }

    @Test fun missingNullMalformedOrInvalidBaseAmountsFromTheApiNeverProduceTotals() = runTest {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", Instant.parse("2030-01-01T00:15:00Z"),
                    Instant.parse("2030-01-01T00:30:00Z"), AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        })
        auth.login("offline@example.test", "fixture-only")
        val valid = """{"transactionId":1,"clientId":"00000000-0000-4000-8000-000000000001","type":"Expense",
            "categoryId":3,"categoryName":"Food","amount":10.00,"currencyCode":"USD","fxRate":56.00000000,
            "fxRateDate":"2026-11-03","amountBase":560.00,"transactionDate":"2026-11-03","description":null,
            "createdAtUtc":"2026-11-03T00:00:00Z","billId":null,"billDueDate":null}"""
        val variants = listOf(
            JsonParser.parseString(valid).asJsonObject.apply { remove("amountBase") },
            JsonParser.parseString(valid).asJsonObject.apply { add("amountBase", JsonNull.INSTANCE) },
            JsonParser.parseString(valid).asJsonObject.apply { add("amountBase", JsonPrimitive("560.00")) },
            JsonParser.parseString(valid).asJsonObject.apply { addProperty("amountBase", BigDecimal("-1")) },
            JsonParser.parseString(valid).asJsonObject.apply { addProperty("amountBase", BigDecimal("560.001")) },
        )
        for (item in variants) {
            val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
                FinanceHttpEngine { FinanceHttpResponse(200, """{"items":[$item],"page":1,"pageSize":20,"totalCount":1}""") })
            assertEquals(DashboardSummaryResult.RepositoryFailure(TransactionFailure.Unavailable),
                LoadDashboardSummary(finance.transactions, accountCurrency = finance.baseCurrency)(month))
        }
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { FinanceHttpResponse(200, """{"items":[$valid],"page":1,"pageSize":20,"totalCount":1}""") })
        val summary = (LoadDashboardSummary(finance.transactions, accountCurrency = finance.baseCurrency)(month) as DashboardSummaryResult.Success).summary
        money("560.00", summary.totalExpenses)
        assertEquals("USD", summary.recentTransactions.single().currencyCode)
    }
}
