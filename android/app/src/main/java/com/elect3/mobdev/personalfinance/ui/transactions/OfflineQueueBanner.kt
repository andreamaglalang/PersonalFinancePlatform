package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.local.LocalPendingTransaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.util.UUID

/** Signed-in only: transactions stored on this device that the server has not confirmed yet. */
data class OfflineQueueUi(
    val items: List<LocalPendingTransaction>,
    val syncing: Boolean,
    val onSync: () -> Unit,
    val onDiscard: (UUID) -> Unit,
)

@Composable
fun OfflineQueueBanner(queue: OfflineQueueUi) {
    if (queue.items.isEmpty()) return
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val waiting = queue.items.filter { !it.rejected }
    val rejected = queue.items.filter { it.rejected }
    Surface(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp), shape = LocalOwliShapes.current.group,
        color = owli.accentSoft, border = BorderStroke(1.dp, owli.hairline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (waiting.isNotEmpty()) {
                Text(stringResource(R.string.offline_pending_title, waiting.size), style = typography.bodyStrong,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.offline_pending_message), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rejected.isNotEmpty()) Text(stringResource(R.string.offline_rejected_message, rejected.size), style = typography.caption,
                color = MaterialTheme.colorScheme.error)
            queue.items.forEach { item ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.categoryName ?: "#${item.draft.categoryId}", style = typography.label)
                        Text(formatTransactionDate(item.draft.transactionDate) + (if (item.rejected) " · " + stringResource(R.string.offline_rejected_label) else ""),
                            style = typography.caption, color = if (item.rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text((if (item.draft.type == TransactionType.Income) "+" else "−") + formatTransactionAmount(item.draft.amount, item.draft.currencyCode),
                        style = typography.amountRow)
                    if (item.rejected) TextButton(onClick = { queue.onDiscard(item.clientId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.offline_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (waiting.isNotEmpty()) OutlinedButton(onClick = queue.onSync, enabled = !queue.syncing, shape = LocalOwliShapes.current.chip,
                border = BorderStroke(1.dp, owli.hairline), modifier = Modifier.heightIn(min = 48.dp)) {
                if (queue.syncing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }
                Text(stringResource(if (queue.syncing) R.string.offline_syncing else R.string.offline_sync_now))
            }
        }
    }
}
