package com.elect3.mobdev.personalfinance.ui.transactions

import com.elect3.mobdev.personalfinance.domain.repository.FilterableTransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Construction and actions are confined to the main thread, like viewModelScope state updates. */
/** Client-side amount order, only over a complete period; compares base-currency amounts. */
enum class AmountOrder { Largest, Smallest }

class TransactionListViewModel @MainThread constructor(
    initialRepository: TransactionRepository,
    private val replacementFactory: (() -> TransactionRepository)? = null,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    /** Starting filter. The app opens Activity on the current month; the default shows all time. */
    initialFilter: TransactionFilter = TransactionFilter(),
) : ViewModel() {
    private var invalidated = false
    private var repository = initialRepository
    private val mutableState = MutableStateFlow<TransactionListUiState>(TransactionListUiState.InitialLoading)
    val state: StateFlow<TransactionListUiState> = mutableState.asStateFlow()

    private var generation = 0L
    private var request: Job? = null

    /** Server-side filter applied to every page, refresh and load-more. Default: all transactions, newest first. */
    private var filter = initialFilter
    private val mutableFilter = MutableStateFlow(filter)
    val activeFilter: StateFlow<TransactionFilter> = mutableFilter.asStateFlow()

    /** Filtering is offered only for sources that filter the complete dataset before paging. */
    val supportsFiltering: Boolean get() = repository is FilterableTransactionRepository

    /**
     * Replaces the active filter and reloads from page one. Pending reads are superseded, so a late page for
     * the previous filter can never be merged into the new result set.
     */
    @MainThread
    fun applyFilter(newFilter: TransactionFilter) {
        if (invalidated || !supportsFiltering) return
        if (mutableState.value is TransactionListUiState.SessionExpired) return
        if (newFilter == filter) return
        filter = newFilter
        mutableFilter.value = newFilter
        supersedeRequest()
        loadFirstPage()
    }

    /** Client-side order and search, applied only to a complete period (see TransactionListUiState.Content.complete). */
    private val mutableAmountOrder = MutableStateFlow<AmountOrder?>(null)
    val amountOrder: StateFlow<AmountOrder?> = mutableAmountOrder.asStateFlow()
    private val mutableSearch = MutableStateFlow("")
    val searchQuery: StateFlow<String> = mutableSearch.asStateFlow()
    fun setAmountOrder(order: AmountOrder?) { mutableAmountOrder.value = order }
    fun setSearch(query: String) { mutableSearch.value = query.take(100) }

    init {
        loadFirstPage()
    }

    @MainThread
    fun retryInitialLoad() {
        if (mutableState.value is TransactionListUiState.InitialError && request?.isActive != true) {
            loadFirstPage()
        }
    }

    @MainThread
    fun loadMore() {
        val content = mutableState.value as? TransactionListUiState.Content ?: return
        if (content.append == TransactionAppendState.Ready) appendPage(content)
    }

    @MainThread
    fun retryAppend() {
        val content = mutableState.value as? TransactionListUiState.Content ?: return
        if (content.append is TransactionAppendState.Error) appendPage(content)
    }

    @MainThread
    fun refresh() {
        if (invalidated) return
        val current = mutableState.value
        if (current is TransactionListUiState.SessionExpired) return
        if (current == TransactionListUiState.InitialLoading) return
        val content = current as? TransactionListUiState.Content
        if (content?.isRefreshing == true) return
        supersedeRequest()
        loadFirstPage(content)
    }

    /** Creation completion requires a fresh page one even if an older initial read is pending. */
    @MainThread
    fun refreshAfterConfirmedCreation() {
        if (invalidated || mutableState.value is TransactionListUiState.SessionExpired) return
        if (!isSessionCurrent()) { invalidateSession(); return }
        val previous = mutableState.value as? TransactionListUiState.Content
        supersedeRequest()
        loadFirstPage(previous)
    }

    /** Only this explicit action invokes replacement. The supplier must be fast and nonblocking. */
    @MainThread
    fun restartDemoSession() {
        if (invalidated) return
        if (mutableState.value !is TransactionListUiState.SessionExpired) return
        supersedeRequest()
        val factory = replacementFactory
        if (factory == null) {
            mutableState.value = TransactionListUiState.SessionExpired(DemoRestartState.Unsupported)
            return
        }
        val replacement = try {
            factory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = TransactionListUiState.SessionExpired(DemoRestartState.ReplacementFailed)
            return
        }
        // A replacement callback must actually provide a fresh instance.
        if (replacement === repository) {
            mutableState.value = TransactionListUiState.SessionExpired(DemoRestartState.ReplacementFailed)
            return
        }
        repository = replacement
        loadFirstPage()
    }

    private fun supersedeRequest() {
        generation++
        request?.cancel()
        request = null
    }

    private fun loadFirstPage(previous: TransactionListUiState.Content? = null) {
        mutableState.value = if (previous == null) {
            TransactionListUiState.InitialLoading
        } else {
            snapshot(previous, append = settledAppend(previous), isRefreshing = true)
        }
        readPage(1) { result ->
            when (result) {
                is TransactionPageResult.Success -> {
                    val page = result.page
                    val items = orderedUnique(page.items)
                    mutableState.value = if (items.isEmpty()) {
                        TransactionListUiState.Empty
                    } else {
                        TransactionListUiState.Content(items, page.page, page.totalCount, appendState(items, page),
                            isRefreshing = snapshotWanted(page),
                            complete = isBoundedFilter() && items.size.toLong() == page.totalCount)
                    }
                    val content = mutableState.value as? TransactionListUiState.Content
                    if (content != null && !content.complete && snapshotWanted(page)) completePeriod(content, page.totalCount)
                }
                is TransactionPageResult.Failure -> {
                    mutableState.value = when {
                        result.reason == TransactionFailure.SessionExpired -> expiredState()
                        previous != null -> snapshot(
                            previous,
                            append = settledAppend(previous),
                            refreshError = result.reason,
                        )
                        else -> TransactionListUiState.InitialError(result.reason)
                    }
                }
            }
        }
    }

    private fun isBoundedFilter() = filter.isBounded && repository is FilterableTransactionRepository
    private fun snapshotWanted(page: TransactionPage) = isBoundedFilter() && page.totalCount in 1..SNAPSHOT_CAP &&
        page.items.size.toLong() < page.totalCount

    /**
     * Bounded period: read its remaining pages so totals and search cover the whole period. Each page must report
     * the same total; any change or failure leaves the normal paged list (never a partial complete view).
     */
    private fun completePeriod(first: TransactionListUiState.Content, expected: Long) {
        fun step(current: TransactionListUiState.Content) {
            readPage(current.lastSuccessfulPage + 1) { result ->
                if (result is TransactionPageResult.Failure && result.reason == TransactionFailure.SessionExpired) {
                    mutableState.value = expiredState(); return@readPage
                }
                val page = (result as? TransactionPageResult.Success)?.page
                if (page == null || page.totalCount != expected || page.items.isEmpty()) {
                    // Keep what was read, as an ordinary paged list.
                    mutableState.value = snapshot(current, append = if (current.items.size.toLong() < current.totalCount)
                        TransactionAppendState.Ready else TransactionAppendState.End,
                        refreshError = (result as? TransactionPageResult.Failure)?.reason)
                    return@readPage
                }
                val items = orderedUnique(current.items + page.items)
                val done = items.size.toLong() == expected
                val next = TransactionListUiState.Content(items, page.page, expected, appendState(items, page),
                    isRefreshing = !done, complete = done)
                mutableState.value = next
                if (!done) step(next)
            }
        }
        step(first)
    }

    private fun appendPage(previous: TransactionListUiState.Content) {
        if (previous.isRefreshing || request?.isActive == true) return
        if (previous.lastSuccessfulPage == Int.MAX_VALUE) {
            mutableState.value = snapshot(previous, append = TransactionAppendState.End)
            return
        }
        mutableState.value = snapshot(previous, append = TransactionAppendState.Loading)
        readPage(previous.lastSuccessfulPage + 1) { result ->
            mutableState.value = when (result) {
                is TransactionPageResult.Success -> {
                    val page = result.page
                    val items = orderedUnique(previous.items + page.items)
                    TransactionListUiState.Content(items, page.page, page.totalCount, appendState(items, page))
                }
                is TransactionPageResult.Failure -> {
                    if (result.reason == TransactionFailure.SessionExpired) expiredState()
                    else snapshot(previous, append = TransactionAppendState.Error(result.reason))
                }
            }
        }
    }

    private fun readPage(page: Int, onResult: (TransactionPageResult) -> Unit) {
        if (invalidated || !isSessionCurrent()) { invalidateSession(); return }
        val token = generation
        val source = repository
        val activeFilter = filter
        // Bounded periods use the largest API page so a whole month needs few requests.
        val size = if (activeFilter.isBounded && source is FilterableTransactionRepository) PERIOD_PAGE_SIZE else PAGE_SIZE
        // Assign before starting: an immediate mock response cannot leave a stale active-job handle.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    (if (!activeFilter.isDefault && source is FilterableTransactionRepository)
                        source.readPage(page, size, activeFilter) else source.readPage(page, size)).also { response ->
                        if (response is TransactionPageResult.Success) {
                            require(response.page.page == page && response.page.pageSize == size)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    TransactionPageResult.Failure(TransactionFailure.Unavailable)
                }
                coroutineContext.ensureActive()
                if (token == generation && !invalidated) {
                    if (isSessionCurrent()) onResult(result) else invalidateSession()
                }
            } finally {
                if (token == generation) request = null
            }
        }
        request = job
        job.start()
    }

    private fun expiredState(): TransactionListUiState.SessionExpired {
        if (!invalidated && isSessionCurrent()) onSessionExpired()
        return TransactionListUiState.SessionExpired(
            if (replacementFactory == null) DemoRestartState.Unsupported else DemoRestartState.Available,
        )
    }

    /** Dispose financial data without changing authentication ownership or revoking sessions. */
    fun invalidateSession() {
        invalidated = true
        supersedeRequest()
        mutableState.value = TransactionListUiState.SessionExpired(DemoRestartState.Unsupported)
    }

    override fun onCleared() {
        // AccountTransactionStore explicitly erases account data before clearing.
        // Preserve the existing disposed-demo snapshot contract.
        invalidated = true
        supersedeRequest()
        super.onCleared()
    }

    /** Merge pages in the same order the source uses (date, then ID, in the filter's direction). */
    private fun orderedUnique(items: List<Transaction>): List<Transaction> =
        items.distinctBy { it.transactionId }.sortedWith(when (filter.sort) {
            TransactionListSort.Newest -> compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId }
            TransactionListSort.Oldest -> compareBy<Transaction> { it.transactionDate }.thenBy { it.transactionId }
        })

    private fun appendState(items: List<Transaction>, page: TransactionPage): TransactionAppendState =
        if (page.items.isEmpty() || items.size.toLong() >= page.totalCount || page.page == Int.MAX_VALUE) {
            TransactionAppendState.End
        } else {
            TransactionAppendState.Ready
        }

    private fun settledAppend(content: TransactionListUiState.Content): TransactionAppendState =
        if (content.append == TransactionAppendState.Loading) TransactionAppendState.Ready else content.append

    private fun snapshot(
        content: TransactionListUiState.Content,
        append: TransactionAppendState,
        isRefreshing: Boolean = false,
        refreshError: TransactionFailure? = null,
    ) = TransactionListUiState.Content(
        content.items, content.lastSuccessfulPage, content.totalCount, append, isRefreshing, refreshError, content.complete,
    )

    class Factory(
        private val repository: TransactionRepository,
        private val replacementFactory: (() -> TransactionRepository)? = null,
        private val isSessionCurrent: () -> Boolean = { true },
        private val onSessionExpired: () -> Unit = {},
        private val initialFilter: TransactionFilter = TransactionFilter(),
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TransactionListViewModel::class.java) { "Unsupported ViewModel class" }
            @Suppress("UNCHECKED_CAST")
            return TransactionListViewModel(repository, replacementFactory, isSessionCurrent, onSessionExpired, initialFilter) as T
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        const val PERIOD_PAGE_SIZE = 100
        /** Largest period read completely (at most 5 requests). Bigger periods stay paged, without totals or search. */
        const val SNAPSHOT_CAP = 500L
    }
}
