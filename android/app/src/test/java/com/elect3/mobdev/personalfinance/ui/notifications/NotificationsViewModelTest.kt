package com.elect3.mobdev.personalfinance.ui.notifications

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.AppNotification
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.NotificationKind
import com.elect3.mobdev.personalfinance.domain.repository.NotificationPage
import com.elect3.mobdev.personalfinance.domain.repository.NotificationRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()

    private class FakeRepo : NotificationRepository {
        var all = (1..25).map { n(it, read = it % 2 == 0) }
        val listCalls = mutableListOf<Triple<Boolean, Int, Int>>()
        var listResult: FinanceResult<NotificationPage>? = null
        var markResult: FinanceResult<Unit> = FinanceResult.Success(Unit)
        val marked = mutableListOf<Int?>()
        override suspend fun list(unreadOnly: Boolean, page: Int, pageSize: Int): FinanceResult<NotificationPage> {
            listCalls += Triple(unreadOnly, page, pageSize)
            listResult?.let { return it }
            val rows = all.filter { !unreadOnly || !it.isRead }
            return FinanceResult.Success(NotificationPage(rows.drop((page - 1) * pageSize).take(pageSize), page, pageSize, rows.size.toLong()))
        }
        override suspend fun markRead(notificationId: Int): FinanceResult<Unit> {
            marked += notificationId
            if (markResult is FinanceResult.Success) all = all.map { if (it.notificationId == notificationId) it.copy(isRead = true) else it }
            return markResult
        }
        override suspend fun markAllRead(): FinanceResult<Unit> {
            marked += null
            if (markResult is FinanceResult.Success) all = all.map { it.copy(isRead = true) }
            return markResult
        }
    }

    private fun model(repo: FakeRepo, expired: () -> Unit = {}): NotificationsViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, NotificationsViewModel.Factory(repo, onSessionExpired = expired))[NotificationsViewModel::class.java]
    }
    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally { stores.forEach { it.clear() }; runCurrent() }
    }

    @Test fun loadsTheFirstPageThenMore() = verify {
        val repo = FakeRepo(); val vm = model(repo)
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertEquals(Triple(false, 1, 20), repo.listCalls.single { it.third == 20 })
        assertEquals(20, vm.state.value.items.size)
        assertTrue(vm.state.value.canLoadMore)
        vm.loadMore(); runCurrent()
        assertEquals(25, vm.state.value.items.size)
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test fun unreadCountUsesServerTotalAndRefreshesAfterConfirmedReadAndNewEvents() = verify {
        val repo = FakeRepo(); val vm = model(repo); runCurrent()
        assertEquals(13L, vm.state.value.unreadCount) // not the unread rows on the loaded 20-item page
        repo.markResult = FinanceResult.Failure(FinanceFailure.Unavailable)
        vm.markRead(1); runCurrent(); assertEquals(13L, vm.state.value.unreadCount)
        repo.markResult = FinanceResult.Success(Unit)
        vm.markRead(1); runCurrent(); assertEquals(12L, vm.state.value.unreadCount)
        repo.all = listOf(n(30, read = false)) + repo.all
        vm.refresh(); runCurrent()
        assertEquals(13L, vm.state.value.unreadCount)
        assertEquals(30, vm.state.value.items.first().notificationId)
        vm.markAllRead(); runCurrent(); assertEquals(0L, vm.state.value.unreadCount)
        vm.invalidateSession(); assertNull(vm.state.value.unreadCount); assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun markAllRemainsAvailableWhenUnreadItemsAreBeyondTheLoadedPage() = verify {
        val repo = FakeRepo().apply { all = (1..25).map { n(it, read = it <= 20) } }
        val vm = model(repo); runCurrent()
        assertTrue(vm.state.value.items.all { it.isRead })
        assertTrue(vm.state.value.hasUnread)
        vm.markAllRead(); runCurrent()
        assertEquals(0L, vm.state.value.unreadCount)
    }

    @Test fun unreadPagingDoesNotSkipRowsAfterAReadMutation() = verify {
        val repo = FakeRepo().apply { all = (1..25).map { n(it, read = false) } }
        val vm = model(repo); runCurrent(); vm.setUnreadOnly(true); runCurrent()
        vm.markRead(1); runCurrent(); vm.loadMore(); runCurrent()
        assertEquals((2..25).toList(), vm.state.value.items.map { it.notificationId })
    }

    @Test fun emptyInbox() = verify {
        val repo = FakeRepo().apply { all = emptyList() }; val vm = model(repo); runCurrent()
        assertTrue(vm.state.value.isEmpty)
        assertFalse(vm.state.value.hasUnread)
    }

    @Test fun unreadFilterReloadsFromPageOne() = verify {
        val repo = FakeRepo(); val vm = model(repo); runCurrent()
        vm.setUnreadOnly(true); runCurrent()
        assertEquals(Triple(true, 1, 20), repo.listCalls.last())
        assertTrue(vm.state.value.unreadOnly)
        assertTrue(vm.state.value.items.none { it.isRead })
        assertEquals(13L, vm.state.value.totalCount)
    }

    @Test fun markReadChangesStateOnlyAfterSuccess() = verify {
        val repo = FakeRepo(); val vm = model(repo); runCurrent()
        repo.markResult = FinanceResult.Failure(FinanceFailure.NotFound)
        vm.markRead(1); runCurrent()
        assertFalse(vm.state.value.items.first { it.notificationId == 1 }.isRead)
        assertEquals(NotificationsError.NotFound, vm.state.value.actionError)
        assertTrue(vm.state.value.markingIds.isEmpty())
        repo.markResult = FinanceResult.Success(Unit)
        vm.markRead(1)
        assertNull(vm.state.value.actionError)
        assertTrue(1 in vm.state.value.markingIds)
        assertFalse(vm.state.value.items.first { it.notificationId == 1 }.isRead) // not optimistic before 204
        runCurrent()
        assertTrue(vm.state.value.items.first { it.notificationId == 1 }.isRead)
        assertEquals(listOf<Int?>(1, 1), repo.marked)
        vm.markRead(1); runCurrent() // already read: nothing sent
        assertEquals(2, repo.marked.size)
    }

    @Test fun markAllReadSuccessAndFailure() = verify {
        val repo = FakeRepo(); val vm = model(repo); runCurrent()
        repo.markResult = FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true)
        vm.markAllRead(); runCurrent()
        assertTrue(vm.state.value.hasUnread)
        assertEquals(NotificationsError.Offline, vm.state.value.actionError)
        repo.markResult = FinanceResult.Success(Unit)
        vm.markAllRead(); runCurrent()
        assertFalse(vm.state.value.hasUnread)
        assertFalse(vm.state.value.markingAll)
        assertNull(vm.state.value.actionError)
    }

    @Test fun loadFailuresAndSessionExpiry() = verify {
        val repo = FakeRepo().apply { listResult = FinanceResult.Failure(FinanceFailure.InvalidResponse) }
        var expired = 0
        val vm = model(repo) { expired++ }; runCurrent()
        assertEquals(NotificationsError.InvalidData, vm.state.value.loadError)
        assertFalse(vm.state.value.isEmpty)
        repo.listResult = FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true)
        vm.refresh(); runCurrent()
        assertEquals(NotificationsError.Offline, vm.state.value.loadError)
        repo.listResult = FinanceResult.Failure(FinanceFailure.SessionExpired)
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.sessionExpired)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(1, expired)
    }

    @Test fun manilaTimeAndTypeLabels() {
        assertEquals("Nov 27, 2026, 1:30 AM", formatNotificationTime(Instant.parse("2026-11-26T17:30:00Z"), Locale.US)
            .replace(' ', ' '))
        assertEquals(R.string.notifications_type_bill, notificationTypeLabel(NotificationKind.BillReminder))
        assertEquals(R.string.notifications_type_budget, notificationTypeLabel(NotificationKind.BudgetAlert))
        assertEquals(R.string.notifications_type_summary, notificationTypeLabel(NotificationKind.MonthlySummary))
        assertEquals(R.string.notifications_type_other, notificationTypeLabel(NotificationKind.Other))
        assertEquals(R.string.notifications_type_goal_milestone, notificationTypeLabel(NotificationKind.GoalMilestone))
        assertEquals(R.string.notifications_type_goal_completed, notificationTypeLabel(NotificationKind.GoalCompleted))
        assertEquals(R.string.notifications_type_bill_overdue, notificationTypeLabel(NotificationKind.BillOverdue))
        assertEquals(R.string.notifications_type_bill_paid, notificationTypeLabel(NotificationKind.BillPaymentRecorded))
        assertEquals(R.string.notifications_type_password, notificationTypeLabel(NotificationKind.PasswordChanged))
        // Every kind has its own label and an icon.
        assertEquals(NotificationKind.entries.size, NotificationKind.entries.map(::notificationTypeLabel).toSet().size)
        NotificationKind.entries.forEach { notificationTypeIcon(it) }
    }

    private companion object {
        fun n(id: Int, read: Boolean) = AppNotification(id, NotificationKind.BudgetAlert, "Message $id", read,
            Instant.parse("2026-11-26T00:00:00Z").minusSeconds(id * 60L))
    }
}
