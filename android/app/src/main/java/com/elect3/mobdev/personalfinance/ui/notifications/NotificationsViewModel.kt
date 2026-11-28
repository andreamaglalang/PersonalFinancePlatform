package com.elect3.mobdev.personalfinance.ui.notifications

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.repository.AppNotification
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NotificationsError { Offline, Unavailable, InvalidData, NotFound }

data class NotificationsUiState(
    val unreadOnly: Boolean = false,
    val items: List<AppNotification> = emptyList(),
    val totalCount: Long = 0,
    val unreadCount: Long? = null,
    val loadedPages: Int = 0,
    /** A first page (or a refresh/filter change) is being read. */
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** Last read failure; shown in place of the list when nothing is loaded, otherwise as a banner. */
    val loadError: NotificationsError? = null,
    /** Last mark-read failure; the item keeps its previous state. */
    val actionError: NotificationsError? = null,
    val markingIds: Set<Int> = emptySet(),
    val markingAll: Boolean = false,
    val sessionExpired: Boolean = false,
) {
    val canLoadMore: Boolean get() = !loading && !loadingMore && loadedPages > 0 && items.size < totalCount
    val hasUnread: Boolean get() = unreadCount?.let { it > 0 } ?: items.any { !it.isRead }
    val isEmpty: Boolean get() = !loading && loadError == null && loadedPages > 0 && items.isEmpty()
}

/**
 * Signed-in notifications inbox. Read state changes only after the server confirms (204); a failure keeps the
 * item as it was and shows an error. Confirmed read mutations restart paging from page one so removing
 * a row from the unread filter cannot shift offsets and skip an unseen notification.
 */
class NotificationsViewModel @MainThread constructor(
    private val repository: NotificationRepository,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {
    private val mutable = MutableStateFlow(NotificationsUiState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var countJob: Job? = null

    init { reload(); refreshUnreadCount() }

    @MainThread fun refresh() { reload(); refreshUnreadCount() }

    private fun refreshUnreadCount() {
        if (mutable.value.sessionExpired) return
        countJob?.cancel()
        countJob = viewModelScope.launch {
            val result = guarded { repository.list(true, 1, 1) }
            if (!accept(result)) return@launch
            mutable.update { it.copy(unreadCount = (result as? FinanceResult.Success)?.value?.totalCount) }
        }
    }

    @MainThread
    fun setUnreadOnly(unreadOnly: Boolean) {
        if (unreadOnly == mutable.value.unreadOnly) return
        mutable.update { it.copy(unreadOnly = unreadOnly) }
        reload()
    }

    @MainThread
    fun loadMore() {
        val current = mutable.value
        if (!current.canLoadMore || current.sessionExpired) return
        mutable.update { it.copy(loadingMore = true, loadError = null) }
        read(current.unreadOnly, current.loadedPages + 1)
    }

    @MainThread
    fun markRead(notificationId: Int) {
        val current = mutable.value
        if (current.sessionExpired || notificationId in current.markingIds || current.items.none { it.notificationId == notificationId && !it.isRead }) return
        mutable.update { it.copy(markingIds = it.markingIds + notificationId, actionError = null) }
        viewModelScope.launch {
            val result = guarded { repository.markRead(notificationId) }
            if (!accept(result)) return@launch
            mutable.update { s ->
                s.copy(markingIds = s.markingIds - notificationId,
                    items = if (result is FinanceResult.Success) s.items.map { if (it.notificationId == notificationId) it.copy(isRead = true) else it } else s.items,
                    actionError = (result as? FinanceResult.Failure)?.let(::error))
            }
            if (result is FinanceResult.Success) refresh()
        }
    }

    @MainThread
    fun markAllRead() {
        val current = mutable.value
        if (current.sessionExpired || current.markingAll || !current.hasUnread) return
        mutable.update { it.copy(markingAll = true, actionError = null) }
        viewModelScope.launch {
            val result = guarded { repository.markAllRead() }
            if (!accept(result)) return@launch
            mutable.update { s ->
                s.copy(markingAll = false,
                    items = if (result is FinanceResult.Success) s.items.map { it.copy(isRead = true) } else s.items,
                    actionError = (result as? FinanceResult.Failure)?.let(::error))
            }
            if (result is FinanceResult.Success) refresh()
        }
    }

    @MainThread fun dismissActionError() = mutable.update { it.copy(actionError = null) }

    /** Logout or account switch: drop every item and ignore late responses. */
    @MainThread
    fun invalidateSession() {
        listJob?.cancel()
        countJob?.cancel()
        mutable.value = NotificationsUiState(loading = false, sessionExpired = true)
    }

    private fun reload() {
        if (mutable.value.sessionExpired) return
        mutable.update { it.copy(loading = true, loadingMore = false, loadError = null) }
        read(mutable.value.unreadOnly, 1)
    }

    private fun read(unreadOnly: Boolean, page: Int) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val result = guarded { repository.list(unreadOnly, page, pageSize) }
            if (!accept(result)) return@launch
            mutable.update { s ->
                when (result) {
                    is FinanceResult.Failure -> s.copy(loading = false, loadingMore = false, loadError = error(result))
                    is FinanceResult.Success -> {
                        val known = if (page == 1) emptySet() else s.items.map { it.notificationId }.toSet()
                        // New notifications shift later pages; skip rows already shown rather than duplicate them.
                        val fresh = result.value.items.filter { it.notificationId !in known }
                        s.copy(items = if (page == 1) fresh else s.items + fresh, totalCount = result.value.totalCount,
                            loadedPages = page, loading = false, loadingMore = false, loadError = null,
                            actionError = if (page == 1) null else s.actionError)
                    }
                }
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> FinanceResult<T>): FinanceResult<T> =
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }

    /** False when the session ended: the inbox is cleared and the owner is told once. */
    private fun accept(result: FinanceResult<*>): Boolean {
        if (mutable.value.sessionExpired) return false
        val expired = (result as? FinanceResult.Failure)?.reason.let { it == FinanceFailure.MissingSession || it == FinanceFailure.SessionExpired }
        if (expired || !isSessionCurrent()) {
            invalidateSession()
            onSessionExpired()
            return false
        }
        return true
    }

    private fun error(failure: FinanceResult.Failure): NotificationsError = when {
        failure.isConnectivityFailure -> NotificationsError.Offline
        failure.reason == FinanceFailure.InvalidResponse -> NotificationsError.InvalidData
        failure.reason == FinanceFailure.NotFound -> NotificationsError.NotFound
        else -> NotificationsError.Unavailable
    }

    class Factory(
        private val repository: NotificationRepository,
        private val isSessionCurrent: () -> Boolean = { true },
        private val onSessionExpired: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NotificationsViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return NotificationsViewModel(repository, isSessionCurrent, onSessionExpired) as T
        }
    }

    companion object { const val PAGE_SIZE = 20 }
}
