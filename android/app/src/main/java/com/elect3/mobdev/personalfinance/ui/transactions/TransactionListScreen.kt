package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure

/** The owner supplies a lifecycle-managed ViewModel; this route creates no repositories or models. */
@Composable
fun TransactionListRoute(viewModel: TransactionListViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransactionListScreen(
        state = state,
        onRetryInitialLoad = viewModel::retryInitialLoad,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onRetryAppend = viewModel::retryAppend,
        onRestartDemoSession = viewModel::restartDemoSession,
        modifier = modifier,
    )
}

/** Stateless rendering with localized UI copy and owner-supplied actions. */
@Composable
fun TransactionListScreen(
    state: TransactionListUiState,
    onRetryInitialLoad: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    onRestartDemoSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        // Header and panels scroll too, so large fonts and short windows remain usable.
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "brand") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(stringResource(R.string.transactions_demo_data),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            item(key = "title") {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val headingText = stringResource(R.string.transactions_title)
                val refreshText = stringResource(R.string.transactions_refresh)
                val headingStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                val title: @Composable (Modifier) -> Unit = { titleModifier ->
                    Text(
                        headingText,
                        style = headingStyle,
                        modifier = titleModifier.semantics { heading() },
                    )
                }
                val refresh: @Composable () -> Unit = {
                    if (state is TransactionListUiState.Content) {
                        FilledTonalButton(onClick = onRefresh, enabled = !state.isRefreshing,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(refreshText)
                        }
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val headingWidth = textMeasurer.measure(headingText, headingStyle, softWrap = false).size.width
                    val refreshWidth = textMeasurer.measure(refreshText, MaterialTheme.typography.labelLarge, softWrap = false).size.width
                    // Include button padding/minimum width and the inter-item gap.
                    val actionWidth = if (state is TransactionListUiState.Content)
                        maxOf(with(density) { 64.dp.roundToPx() }, refreshWidth + with(density) { 48.dp.roundToPx() })
                    else 0
                    if (headingWidth.toLong() + actionWidth + with(density) { 12.dp.roundToPx() } <= constraints.maxWidth) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            title(Modifier.weight(1f))
                            refresh()
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            title(Modifier.fillMaxWidth())
                            Column(modifier = Modifier.align(Alignment.End)) { refresh() }
                        }
                    }
                }
            }
            when (state) {
                TransactionListUiState.InitialLoading -> item(key = "initial-loading") {
                    LoadingPanel(stringResource(R.string.transactions_loading))
                }
                TransactionListUiState.Empty -> item(key = "empty") {
                    MessagePanel(stringResource(R.string.transactions_empty_title), stringResource(R.string.transactions_empty_message)) {
                        ActionButton(stringResource(R.string.transactions_refresh), onRefresh)
                    }
                }
                is TransactionListUiState.InitialError -> item(key = "initial-error") {
                    MessagePanel(stringResource(R.string.transactions_unavailable_title), failureMessage(state.reason), isError = true) {
                        ActionButton(stringResource(R.string.transactions_retry), onRetryInitialLoad)
                    }
                }
                is TransactionListUiState.SessionExpired -> item(key = "session-expired") {
                    SessionExpiredPanel(state.restart, onRestartDemoSession)
                }
                is TransactionListUiState.Content -> {
                    if (state.isRefreshing) item(key = "refresh-loading") {
                        LoadingPanel(stringResource(R.string.transactions_refreshing))
                    }
                    state.refreshError?.let { reason ->
                        item(key = "refresh-error") {
                            MessagePanel(
                                stringResource(R.string.transactions_refresh_error_title),
                                stringResource(R.string.transactions_refresh_error_message, failureMessage(reason)),
                                isError = true,
                            ) { ActionButton(stringResource(R.string.transactions_retry_refresh), onRefresh, enabled = !state.isRefreshing) }
                        }
                    }
                    items(state.items, key = { it.transactionId }) { transaction ->
                        TransactionRow(transaction)
                    }
                    item(key = "pagination") {
                        PaginationFooter(state.append, !state.isRefreshing, onLoadMore, onRetryAppend)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionExpiredPanel(restart: DemoRestartState, onRestart: () -> Unit) {
    val detail = when (restart) {
        DemoRestartState.Available -> stringResource(R.string.transactions_restart_available)
        DemoRestartState.Unsupported -> stringResource(R.string.transactions_restart_unsupported)
        DemoRestartState.ReplacementFailed -> stringResource(R.string.transactions_restart_failed)
    }
    MessagePanel(stringResource(R.string.transactions_session_title), detail, isError = true) {
        when (restart) {
            DemoRestartState.Available -> ActionButton(stringResource(R.string.transactions_restart), onRestart)
            DemoRestartState.ReplacementFailed -> ActionButton(stringResource(R.string.transactions_retry_restart), onRestart)
            DemoRestartState.Unsupported -> Unit
        }
    }
}

@Composable
private fun PaginationFooter(
    append: TransactionAppendState,
    enabled: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    when (append) {
        TransactionAppendState.Ready -> ActionButton(stringResource(R.string.transactions_load_more), onLoadMore, enabled)
        TransactionAppendState.Loading -> LoadingPanel(stringResource(R.string.transactions_loading_more))
        is TransactionAppendState.Error -> MessagePanel(
            stringResource(R.string.transactions_append_error_title),
            failureMessage(append.reason),
            isError = true,
        ) { ActionButton(stringResource(R.string.transactions_retry_append), onRetry, enabled) }
        TransactionAppendState.End -> Text(
            stringResource(R.string.transactions_end),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun LoadingPanel(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    isError: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            actions()
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label)
    }
}

@Composable
private fun failureMessage(reason: TransactionFailure): String = when (reason) {
    TransactionFailure.Unavailable -> stringResource(R.string.transactions_unavailable_message)
    TransactionFailure.SessionExpired -> stringResource(R.string.transactions_session_message)
}
