package com.elect3.mobdev.personalfinance.ui.transactions

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
class TransactionListViewModel @MainThread constructor(
    initialRepository: TransactionRepository,
    private val replacementFactory: (() -> TransactionRepository)? = null,
) : ViewModel() {
    private var repository = initialRepository
    private val mutableState = MutableStateFlow<TransactionListUiState>(TransactionListUiState.InitialLoading)
    val state: StateFlow<TransactionListUiState> = mutableState.asStateFlow()

    private var generation = 0L
    private var request: Job? = null

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
        val current = mutableState.value
        if (current is TransactionListUiState.SessionExpired) return
        if (current == TransactionListUiState.InitialLoading) return
        val content = current as? TransactionListUiState.Content
        if (content?.isRefreshing == true) return
        supersedeRequest()
        loadFirstPage(content)
    }

    /** Only this explicit action invokes replacement. The supplier must be fast and nonblocking. */
    @MainThread
    fun restartDemoSession() {
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
                        TransactionListUiState.Content(items, page.page, page.totalCount, appendState(items, page))
                    }
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
        val token = generation
        val source = repository
        // Assign before starting: an immediate mock response cannot leave a stale active-job handle.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    source.readPage(page, PAGE_SIZE).also { response ->
                        if (response is TransactionPageResult.Success) {
                            require(response.page.page == page && response.page.pageSize == PAGE_SIZE)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    TransactionPageResult.Failure(TransactionFailure.Unavailable)
                }
                coroutineContext.ensureActive()
                if (token == generation) onResult(result)
            } finally {
                if (token == generation) request = null
            }
        }
        request = job
        job.start()
    }

    private fun expiredState() = TransactionListUiState.SessionExpired(
        if (replacementFactory == null) DemoRestartState.Unsupported else DemoRestartState.Available,
    )

    private fun orderedUnique(items: List<Transaction>): List<Transaction> =
        items.distinctBy { it.transactionId }
            .sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId })

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
        content.items, content.lastSuccessfulPage, content.totalCount, append, isRefreshing, refreshError,
    )

    class Factory(
        private val repository: TransactionRepository,
        private val replacementFactory: (() -> TransactionRepository)? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TransactionListViewModel::class.java) { "Unsupported ViewModel class" }
            @Suppress("UNCHECKED_CAST")
            return TransactionListViewModel(repository, replacementFactory) as T
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
