package com.elect3.mobdev.personalfinance.ui.notifications

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.AppNotification
import com.elect3.mobdev.personalfinance.domain.repository.NotificationKind
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupCard
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupDivider
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val MANILA: ZoneId = ZoneId.of("Asia/Manila")

/** Notification times are always shown in Manila local time, whatever the device zone. */
fun formatNotificationTime(instant: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(MANILA).format(instant)

fun notificationTypeLabel(kind: NotificationKind): Int = when (kind) {
    NotificationKind.BillReminder -> R.string.notifications_type_bill
    NotificationKind.BillOverdue -> R.string.notifications_type_bill_overdue
    NotificationKind.BillPaymentRecorded -> R.string.notifications_type_bill_paid
    NotificationKind.BudgetAlert -> R.string.notifications_type_budget
    NotificationKind.GoalMilestone -> R.string.notifications_type_goal_milestone
    NotificationKind.GoalCompleted -> R.string.notifications_type_goal_completed
    NotificationKind.MonthlySummary -> R.string.notifications_type_summary
    NotificationKind.PasswordChanged -> R.string.notifications_type_password
    NotificationKind.Other -> R.string.notifications_type_other
}

/** One existing outlined icon per type (decorative; the type is always stated in text). */
fun notificationTypeIcon(kind: NotificationKind): Int = when (kind) {
    NotificationKind.BillReminder -> R.drawable.ic_owli_bell
    NotificationKind.BillOverdue -> R.drawable.ic_owli_alert
    NotificationKind.BillPaymentRecorded -> R.drawable.ic_cat_check
    NotificationKind.BudgetAlert -> R.drawable.ic_cat_wallet
    NotificationKind.GoalMilestone -> R.drawable.ic_cat_trend
    NotificationKind.GoalCompleted -> R.drawable.ic_cat_trophy
    NotificationKind.MonthlySummary -> R.drawable.ic_owli_insights
    NotificationKind.PasswordChanged -> R.drawable.ic_acc_shield
    NotificationKind.Other -> R.drawable.ic_cat_info
}

/** Full-screen inbox over the app shell. The account owns the model and its unread count. */
@Composable
fun NotificationsDialog(model: NotificationsViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BackHandler { onClose() }
            NotificationsScreen(state, onBack = onClose, onRefresh = model::refresh, onFilter = model::setUnreadOnly,
                onMarkRead = model::markRead, onMarkAll = model::markAllRead, onLoadMore = model::loadMore,
                onDismissError = model::dismissActionError)
        }
    }
}

@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilter: (Boolean) -> Unit,
    onMarkRead: (Int) -> Unit,
    onMarkAll: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissError: () -> Unit,
) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.notifications_back))
            }
            Text(stringResource(R.string.notifications_title), style = typography.screenTitle,
                modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onRefresh, enabled = !state.loading && !state.sessionExpired, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_owli_refresh), stringResource(R.string.notifications_refresh), modifier = Modifier.size(20.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.sessionExpired) {
                item { Box(width) { Message(stringResource(R.string.notifications_session)) } }
                return@LazyColumn
            }
            item(key = "filters") {
                Row(width, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.unreadOnly, onClick = { onFilter(false) },
                        label = { Text(stringResource(R.string.notifications_filter_all)) }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = state.unreadOnly, onClick = { onFilter(true) },
                        label = { Text(stringResource(R.string.notifications_filter_unread)) }, modifier = Modifier.heightIn(min = 48.dp))
                    Box(Modifier.weight(1f))
                    if (state.hasUnread) TextButton(onClick = onMarkAll, enabled = !state.markingAll, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.notifications_mark_all), style = typography.label)
                    }
                }
            }
            state.actionError?.let { error ->
                item(key = "action-error") {
                    Box(width) {
                        Message(stringResource(R.string.notifications_action_failed, stringResource(errorText(error)))) {
                            TextButton(onClick = onDismissError, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notifications_dismiss)) }
                        }
                    }
                }
            }
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading") {
                    Row(width.padding(vertical = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.notifications_loading), style = typography.bodyStrong)
                    }
                }
                state.loadError != null && state.items.isEmpty() -> item(key = "error") {
                    Box(width) {
                        Message(stringResource(errorText(state.loadError)), title = stringResource(R.string.notifications_error_title)) {
                            FilledTonalButton(onClick = onRefresh, shape = LocalOwliShapes.current.button, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.notifications_retry))
                            }
                        }
                    }
                }
                state.isEmpty -> item(key = "empty") {
                    Box(width) {
                        if (state.unreadOnly) Message(stringResource(R.string.notifications_empty_unread))
                        else Message(stringResource(R.string.notifications_empty_message), title = stringResource(R.string.notifications_empty_title))
                    }
                }
                else -> {
                    if (state.loadError != null) item(key = "banner") {
                        Box(width) {
                            Message(stringResource(errorText(state.loadError))) {
                                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notifications_retry)) }
                            }
                        }
                    }
                    item(key = "list") {
                        OwliGroupCard(width) {
                            state.items.forEachIndexed { index, item ->
                                if (index > 0) OwliGroupDivider()
                                NotificationRow(item, locale, marking = item.notificationId in state.markingIds || state.markingAll,
                                    onMarkRead = { onMarkRead(item.notificationId) })
                            }
                        }
                    }
                    item(key = "footer") {
                        Column(width, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            else if (state.canLoadMore) FilledTonalButton(onClick = onLoadMore, shape = LocalOwliShapes.current.button,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notifications_load_more)) }
                            Text(stringResource(R.string.notifications_time_note), style = typography.caption, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: AppNotification, locale: Locale, marking: Boolean, onMarkRead: () -> Unit) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val owli = LocalOwliColors.current
    val type = stringResource(notificationTypeLabel(item.kind))
    val time = formatNotificationTime(item.createdAtUtc, locale)
    val status = stringResource(if (item.isRead) R.string.notifications_read else R.string.notifications_unread)
    val description = stringResource(R.string.notifications_item_description, type, status, item.message, time)
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(owli.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(notificationTypeIcon(item.kind)), null, modifier = Modifier.size(18.dp), tint = owli.accent)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Unread is also stated in text for TalkBack and is not conveyed by colour alone (weight changes too).
                if (!item.isRead) Box(Modifier.size(8.dp).clip(CircleShape).background(owli.accent))
                Text(type, style = if (item.isRead) typography.label else typography.bodyStrong,
                    color = if (item.isRead) scheme.onSurfaceVariant else scheme.onSurface)
            }
            Text(item.message, style = typography.body, color = scheme.onSurface)
            Text(time, style = typography.caption, color = scheme.onSurfaceVariant)
        }
        if (!item.isRead) {
            val label = stringResource(R.string.notifications_mark_read_description, type, item.message)
            TextButton(onClick = onMarkRead, enabled = !marking,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                Text(stringResource(R.string.notifications_mark_read), style = typography.label)
            }
        }
    }
}

@Composable
private fun Message(message: String, title: String? = null, action: @Composable () -> Unit = {}) {
    val typography = LocalOwliTypography.current
    OwliGroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            Text(message, style = typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            action()
        }
    }
}

private fun errorText(error: NotificationsError): Int = when (error) {
    NotificationsError.Offline -> R.string.notifications_offline
    NotificationsError.Unavailable -> R.string.notifications_unavailable
    NotificationsError.InvalidData -> R.string.notifications_invalid
    NotificationsError.NotFound -> R.string.notifications_not_found
}
