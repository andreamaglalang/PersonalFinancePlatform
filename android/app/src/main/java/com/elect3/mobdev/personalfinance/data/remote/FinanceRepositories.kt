package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.net.URLEncoder

/**
 * Passive construction. Reads occur only through the authenticated Transactions route;
 * the single write path, [creation], is used only by the authenticated Add Transaction save.
 */
class FinanceRepositories private constructor(
    transport: AuthenticatedFinanceTransport,
    currency: () -> String?,
    /** Authenticated POST api/transactions. Never handed to Mock Demo. */
    val creation: TransactionCreationRepository,
    /** Authenticated member category writes (api/categories). Never handed to Mock Demo. */
    val categoryWrites: CategoryWriteRepository,
    /** Signed-in account ID for device-only presentation preferences; null without an active session. */
    val accountId: () -> Int?,
    /** Authenticated api/budgets (read and member writes). Never handed to Mock Demo. */
    val budgets: BudgetRepository,
    /** Authenticated PUT api/transactions/{id}. Online only; never handed to Mock Demo. */
    val transactionUpdates: TransactionUpdateRepository,
    /** Authenticated api/notifications reads and C3 mark-read. Never handed to Mock Demo. */
    val notifications: NotificationRepository,
) {
    constructor(configuration: ApiConfiguration, auth: InMemoryAuthRepository) : this(
        AuthenticatedFinanceTransport(configuration, auth, PlatformFinanceHttpEngine()),
        { auth.accountBaseCurrency() },
        RemoteTransactionCreationRepository(configuration, auth),
        RemoteCategoryWriteRepository(configuration, auth, PlatformFinanceWriteEngine()),
        { auth.accountId() },
        RemoteBudgetRepository(AuthenticatedFinanceTransport(configuration, auth, PlatformFinanceHttpEngine()), configuration, auth, PlatformFinanceWriteEngine()),
        RemoteTransactionUpdateRepository(configuration, auth, PlatformFinanceWriteEngine()),
        RemoteNotificationRepository(AuthenticatedFinanceTransport(configuration, auth, PlatformFinanceHttpEngine()), configuration, auth, PlatformFinanceWriteEngine()),
    )
    /** Test hosts: fake engines only. The default POST engine refuses to send, so no socket can open. */
    internal constructor(
        configuration: ApiConfiguration,
        auth: InMemoryAuthRepository,
        engine: FinanceHttpEngine,
        postEngine: FinancePostEngine = FinancePostEngine { throw IllegalStateException("No POST engine in this host") },
        writeEngine: FinanceWriteEngine = FinanceWriteEngine { throw IllegalStateException("No write engine in this host") },
    ) : this(
        AuthenticatedFinanceTransport(configuration, auth, engine),
        { auth.accountBaseCurrency() },
        RemoteTransactionCreationRepository(auth, postEngine, configuration),
        RemoteCategoryWriteRepository(configuration, auth, writeEngine),
        { auth.accountId() },
        RemoteBudgetRepository(AuthenticatedFinanceTransport(configuration, auth, engine), configuration, auth, writeEngine),
        RemoteTransactionUpdateRepository(configuration, auth, writeEngine),
        RemoteNotificationRepository(AuthenticatedFinanceTransport(configuration, auth, engine), configuration, auth, writeEngine),
    )
    val categories: CategoryRepository = RemoteCategoryRepository(transport)
    val transactions: RemoteTransactionRepository = RemoteTransactionRepository(transport)
    /** Authenticated read-only GET api/exchange-rates/quote (no writes). Never handed to Mock Demo. */
    val exchangeRateQuotes: com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository = RemoteExchangeRateQuoteRepository(transport)
    val baseCurrency: () -> String? = currency
    /** Authenticated read-only C6 monthly report and C4 stored summaries. Never handed to Mock Demo. */
    val reports: com.elect3.mobdev.personalfinance.domain.reports.ReportRepository = RemoteReportRepository(transport)
}
internal fun queryString(vararg pairs: Pair<String, Any?>) = pairs.filter { it.second != null }.joinToString("&") {
    URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second.toString(), "UTF-8")
}
internal class RemoteCategoryRepository(private val transport: AuthenticatedFinanceTransport) : CategoryRepository {
    override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> = transport.get(
        FinanceResource.Categories, queryString("type" to query.type?.wireValue,
            "includeInactive" to query.includeInactive, "page" to query.page, "pageSize" to query.pageSize),
    ) { FinanceJson.categories(it, query) }
}
class RemoteTransactionRepository internal constructor(private val transport: AuthenticatedFinanceTransport) : FilterableTransactionRepository {
    suspend fun list(query: TransactionQuery = TransactionQuery()): FinanceResult<TransactionPage> = transport.get(
        FinanceResource.Transactions, queryString("page" to query.page, "pageSize" to query.pageSize,
            "type" to query.type?.wireValue, "categoryId" to query.categoryId,
            "fromDate" to query.fromDate, "toDate" to query.toDate,
            "sortBy" to query.sortBy.wire, "sortDirection" to query.sortDirection.wire),
    ) { FinanceJson.transactions(it, query) }

    /** Compatibility adapter; new consumers retain richer typed errors via list(). */
    override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult = pageResult(list(TransactionQuery(page, pageSize)))

    /** Existing API query parameters only: type, categoryId, fromDate, toDate, sortBy=date, sortDirection. */
    override suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter): TransactionPageResult = pageResult(list(
        TransactionQuery(page, pageSize, type = filter.type, categoryId = filter.categoryId,
            fromDate = filter.fromDate, toDate = filter.toDate, sortBy = TransactionSort.Date,
            sortDirection = if (filter.sort == TransactionListSort.Oldest) SortDirection.Ascending else SortDirection.Descending)))

    private fun pageResult(result: FinanceResult<TransactionPage>): TransactionPageResult = when (result) {
        is FinanceResult.Success -> TransactionPageResult.Success(result.value)
        is FinanceResult.Failure -> TransactionPageResult.Failure(when (result.reason) {
            FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> TransactionFailure.SessionExpired
            else -> TransactionFailure.Unavailable
        })
    }
}
