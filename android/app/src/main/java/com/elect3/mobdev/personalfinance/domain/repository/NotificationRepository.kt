package com.elect3.mobdev.personalfinance.domain.repository

import java.time.Instant

/**
 * Known server notification types: scheduled (FinanceJobs: BillReminder, BillOverdue, BudgetAlert, MonthlySummary) and
 * event-driven (FinanceNotifications: GoalMilestone, GoalCompleted, BillPaymentRecorded, PasswordChanged). Anything
 * else is shown as a generic account update.
 */
enum class NotificationKind {
    BillReminder, BillOverdue, BillPaymentRecorded, BudgetAlert, GoalMilestone, GoalCompleted, MonthlySummary, PasswordChanged, Other;
    companion object { fun fromWire(value: String) = entries.firstOrNull { it != Other && it.name == value } ?: Other }
}

data class AppNotification(
    val notificationId: Int,
    val kind: NotificationKind,
    val message: String,
    val isRead: Boolean,
    val createdAtUtc: Instant,
)

data class NotificationPage(val items: List<AppNotification>, val page: Int, val pageSize: Int, val totalCount: Long)

/** The signed-in user's in-app notifications (`api/notifications`) and their read state (C3). */
interface NotificationRepository {
    suspend fun list(unreadOnly: Boolean, page: Int, pageSize: Int): FinanceResult<NotificationPage>
    /** `POST api/notifications/{id}/read` → 204; idempotent. */
    suspend fun markRead(notificationId: Int): FinanceResult<Unit>
    /** `POST api/notifications/read-all` → 204. */
    suspend fun markAllRead(): FinanceResult<Unit>
}
