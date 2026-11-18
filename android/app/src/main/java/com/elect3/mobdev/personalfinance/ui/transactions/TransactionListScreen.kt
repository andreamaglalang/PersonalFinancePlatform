package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.painterResource
import com.elect3.mobdev.personalfinance.ui.components.OwliOwlAvatar
import com.elect3.mobdev.personalfinance.ui.components.OwliRibbon
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
fun TransactionListRoute(viewModel: TransactionListViewModel, modifier: Modifier = Modifier, isAuthenticated: Boolean = false,
    onAddTransaction: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransactionListScreen(
        state = state,
        onRetryInitialLoad = viewModel::retryInitialLoad,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onRetryAppend = viewModel::retryAppend,
        onRestartDemoSession = viewModel::restartDemoSession,
        modifier = modifier,
        isAuthenticated = isAuthenticated,
        onAddTransaction = onAddTransaction,
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
    isAuthenticated: Boolean = false,
    onAddTransaction: (() -> Unit)? = null,
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwliOwlAvatar(size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = LocalOwliTypography.current.wordmark,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(stringResource(R.string.app_tagline), style = LocalOwliTypography.current.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (onAddTransaction != null && state !is TransactionListUiState.SessionExpired) item(key = "add") {
                Button(onClick = onAddTransaction, shape = LocalOwliShapes.current.button,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.ic_owli_plus), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.form_title), style = LocalOwliTypography.current.button)
                }
            }
            item(key = "title") {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val headingText = stringResource(R.string.transactions_title)
                val refreshText = stringResource(R.string.transactions_refresh)
                val headingStyle = LocalOwliTypography.current.screenTitle
                val title: @Composable (Modifier) -> Unit = { titleModifier ->
                    Text(
                        headingText,
                        style = headingStyle,
                        modifier = titleModifier.semantics { heading() },
                    )
                }
                val refresh: @Composable () -> Unit = {
                    if (state is TransactionListUiState.Content) {
                        OutlinedButton(onClick = onRefresh, enabled = !state.isRefreshing,
                            shape = LocalOwliShapes.current.chip,
                            border = BorderStroke(1.dp, LocalOwliColors.current.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_owli_refresh), contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(refreshText, style = LocalOwliTypography.current.label)
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
            item(key = "ribbon") {
                OwliRibbon(stringResource(if (isAuthenticated) R.string.transactions_account_data else R.string.transactions_demo_data), null)
            }
            when (state) {
                TransactionListUiState.InitialLoading -> item(key = "initial-loading") {
                    LoadingPanel(stringResource(R.string.transactions_loading))
                }
                TransactionListUiState.Empty -> item(key = "empty") {
                    MessagePanel(stringResource(R.string.transactions_empty_title), stringResource(
                        if (isAuthenticated) R.string.transactions_account_empty_message else R.string.transactions_empty_message)) {
                        ActionButton(stringResource(R.string.transactions_refresh), onRefresh)
                    }
                }
                is TransactionListUiState.InitialError -> item(key = "initial-error") {
                    MessagePanel(stringResource(R.string.transactions_unavailable_title), failureMessage(state.reason, isAuthenticated), isError = true) {
                        ActionButton(stringResource(R.string.transactions_retry), onRetryInitialLoad)
                    }
                }
                is TransactionListUiState.SessionExpired -> item(key = "session-expired") {
                    if (isAuthenticated) MessagePanel(stringResource(R.string.transactions_account_session_title),
                        stringResource(R.string.auth_session_expired), isError = true) {}
                    else SessionExpiredPanel(state.restart, onRestartDemoSession)
                }
                is TransactionListUiState.Content -> {
                    if (state.isRefreshing) item(key = "refresh-loading") {
                        LoadingPanel(stringResource(R.string.transactions_refreshing))
                    }
                    state.refreshError?.let { reason ->
                        item(key = "refresh-error") {
                            MessagePanel(
                                stringResource(R.string.transactions_refresh_error_title),
                                stringResource(R.string.transactions_refresh_error_message, failureMessage(reason, isAuthenticated)),
                                isError = true,
                            ) { ActionButton(stringResource(R.string.transactions_retry_refresh), onRefresh, enabled = !state.isRefreshing) }
                        }
                    }
                    items(state.items, key = { it.transactionId }) { transaction ->
                        TransactionRow(transaction)
                    }
                    item(key = "pagination") {
                        PaginationFooter(state.append, !state.isRefreshing, onLoadMore, onRetryAppend, isAuthenticated)
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
    isAuthenticated: Boolean = false,
) {
    when (append) {
        TransactionAppendState.Ready -> ActionButton(stringResource(R.string.transactions_load_more), onLoadMore, enabled)
        TransactionAppendState.Loading -> LoadingPanel(stringResource(R.string.transactions_loading_more))
        is TransactionAppendState.Error -> MessagePanel(
            stringResource(R.string.transactions_append_error_title),
            failureMessage(append.reason, isAuthenticated),
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
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
        shape = LocalOwliShapes.current.group,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        border = if (isError) null else BorderStroke(1.dp, LocalOwliColors.current.hairline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = LocalOwliTypography.current.sectionTitle, modifier = Modifier.semantics { heading() })
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
    Button(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.button, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = LocalOwliTypography.current.bodyStrong)
    }
}

@Composable
private fun failureMessage(reason: TransactionFailure, isAuthenticated: Boolean = false): String = when (reason) {
    TransactionFailure.Unavailable -> stringResource(if (isAuthenticated) R.string.transactions_account_unavailable_message else R.string.transactions_unavailable_message)
    TransactionFailure.SessionExpired -> stringResource(if (isAuthenticated) R.string.auth_session_expired else R.string.transactions_session_message)
}
