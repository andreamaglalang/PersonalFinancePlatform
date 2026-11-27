package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.ui.categories.CategoryVisuals
import com.elect3.mobdev.personalfinance.ui.categories.categoryColor
import com.elect3.mobdev.personalfinance.ui.categories.isDarkSurface
import com.elect3.mobdev.personalfinance.ui.components.LocalOwliFilterRequest
import com.elect3.mobdev.personalfinance.ui.components.OwliMidnightPanel
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** The owner supplies a lifecycle-managed ViewModel; this route creates no repositories or models. */
@Composable
fun TransactionListRoute(viewModel: TransactionListViewModel, modifier: Modifier = Modifier, isAuthenticated: Boolean = false,
    onAddTransaction: (() -> Unit)? = null, filterCategories: CategoryRepository? = null, offlineQueue: OfflineQueueUi? = null,
    localHistory: (suspend () -> List<com.elect3.mobdev.personalfinance.data.local.LocalHistoryTransaction>)? = null,
    onEditTransaction: ((Transaction) -> Unit)? = null, showOriginalAmounts: Boolean = true, baseCurrency: String = "PHP") {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeFilter by viewModel.activeFilter.collectAsStateWithLifecycle()
    var localRows by remember(localHistory) { mutableStateOf(emptyList<com.elect3.mobdev.personalfinance.data.local.LocalHistoryTransaction>()) }
    val unavailable = state is TransactionListUiState.InitialError && (state as TransactionListUiState.InitialError).reason == TransactionFailure.Unavailable ||
        state is TransactionListUiState.Content && (state as TransactionListUiState.Content).refreshError == TransactionFailure.Unavailable
    LaunchedEffect(state, activeFilter, localHistory) {
        localRows = emptyList()
        if (isAuthenticated && unavailable && localHistory != null) {
            try { localRows = com.elect3.mobdev.personalfinance.data.local.filterLocalHistory(localHistory(), activeFilter) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the normal error UI if local metadata cannot be read. */ }
        }
    }
    val order by viewModel.amountOrder.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val canFilter = viewModel.supportsFiltering
    val today = remember { LocalDate.now() }
    val request = LocalOwliFilterRequest.current
    LaunchedEffect(request.pending) { if (request.pending) { if (canFilter) sheetOpen = true; request.consume() } }
    if (sheetOpen && canFilter) {
        TransactionFilterSheet(
            current = activeFilter, categories = filterCategories, today = today,
            onApply = { viewModel.applyFilter(it); sheetOpen = false },
            onReset = { viewModel.setAmountOrder(null); viewModel.applyFilter(TransactionFilter().forMonth(activeFilter.month ?: YearMonth.from(today))); sheetOpen = false },
            onDismiss = { sheetOpen = false },
            amountOrder = order, onApplyOrder = viewModel::setAmountOrder,
        )
    }
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
        activeFilter = activeFilter,
        onOpenFilter = if (canFilter) ({ sheetOpen = true }) else null,
        onClearFilter = { viewModel.setAmountOrder(null); viewModel.setSearch(""); viewModel.applyFilter(activeFilter.withoutCriteria()) },
        onApplyFilter = if (canFilter) viewModel::applyFilter else null,
        amountOrder = order,
        onAmountOrder = viewModel::setAmountOrder,
        searchQuery = query,
        onSearch = viewModel::setSearch,
        today = today,
        offlineQueue = offlineQueue,
        localHistory = if (unavailable) localRows else emptyList(),
        onEditTransaction = onEditTransaction,
        showOriginalAmounts = showOriginalAmounts, baseCurrency = baseCurrency,
    )
}

/**
 * Activity (approved redesign). Opens on one month, which is read completely when it has at most 500
 * transactions: only then are totals, day bars, search and amount order shown. All time and larger periods
 * stay paged from the server ("Show N more"). Adding is done with the central (+); the only Add button here
 * appears when there is no history at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    activeFilter: TransactionFilter = TransactionFilter(),
    onOpenFilter: (() -> Unit)? = null,
    onClearFilter: () -> Unit = {},
    onApplyFilter: ((TransactionFilter) -> Unit)? = null,
    amountOrder: AmountOrder? = null,
    onAmountOrder: (AmountOrder?) -> Unit = {},
    searchQuery: String = "",
    onSearch: (String) -> Unit = {},
    today: LocalDate = LocalDate.now(),
    offlineQueue: OfflineQueueUi? = null,
    localHistory: List<com.elect3.mobdev.personalfinance.data.local.LocalHistoryTransaction> = emptyList(),
    /** Signed-in only: opens the online edit for a server transaction (never shown in Mock Demo). */
    onEditTransaction: ((Transaction) -> Unit)? = null,
    showOriginalAmounts: Boolean = true, baseCurrency: String = "PHP",
) {
    val showingLocalHistory = isAuthenticated && localHistory.isNotEmpty()
    val content = if (showingLocalHistory) null else state as? TransactionListUiState.Content
    val complete = content?.complete == true
    var searchOpen by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var picker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val month = activeFilter.month
    val lines = remember(content, amountOrder, searchQuery, activeFilter) {
        if (content == null) emptyList() else activityLines(content.items, complete, amountOrder, searchQuery, monthHeaders = month == null)
    }
    val totals = remember(content) { if (complete) periodTotals(content!!.items) else null }
    val filterCount = listOfNotNull(activeFilter.type, activeFilter.categoryId, amountOrder,
        activeFilter.sort.takeIf { it != TransactionListSort.Newest }).size + (if (month == null) 1 else 0)
    val searching = searchQuery.isNotBlank()
    val refreshLabel = stringResource(R.string.act_refresh_action)
    val canRefresh = state is TransactionListUiState.Content || state == TransactionListUiState.Empty || state is TransactionListUiState.InitialError

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        val pullState = rememberPullToRefreshState()
        val refreshing = content?.isRefreshing == true
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (canRefresh) onRefresh() }, modifier = Modifier.fillMaxSize(), state = pullState,
            indicator = { PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).clearAndSetSemantics {}) }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics {
                    if (canRefresh) customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true })
                },
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    ActivityHeader(isAuthenticated, searchOpen && !showingLocalHistory, filterCount, enabled = state !is TransactionListUiState.SessionExpired && !showingLocalHistory,
                        onSearch = { searchOpen = !searchOpen; if (!searchOpen) onSearch("") }, onFilter = onOpenFilter)
                }
                if (offlineQueue != null && offlineQueue.items.isNotEmpty()) item(key = "offline-queue") { OfflineQueueBanner(offlineQueue) }
                if (state is TransactionListUiState.SessionExpired) {
                    item(key = "session-expired") {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            if (isAuthenticated) MessagePanel(stringResource(R.string.transactions_account_session_title),
                                stringResource(R.string.auth_session_expired), isError = true) {}
                            else SessionExpiredPanel(state.restart, onRestartDemoSession)
                        }
                    }
                    return@LazyColumn
                }
                if (searchOpen && !showingLocalHistory) item(key = "search") {
                    SearchField(searchQuery, onSearch, onClose = { searchOpen = false; onSearch("") })
                }
                if (onApplyFilter != null) item(key = "months") {
                    MonthRow(activeFilter, today, content?.totalCount,
                        onMonth = { onApplyFilter(activeFilter.forMonth(it)) },
                        onPick = { picker = true },
                        onToggle = { onApplyFilter(if (month != null) activeFilter.allTime() else activeFilter.forMonth(YearMonth.from(today))) })
                }
                if (searchOpen && searching && !complete && content != null) item(key = "search-scope") {
                    Notice(stringResource(R.string.act_search_needs_period),
                        stringResource(R.string.act_search_this_month, monthLabel(YearMonth.from(today))).takeIf { onApplyFilter != null }) {
                        onApplyFilter?.invoke(activeFilter.forMonth(YearMonth.from(today)))
                    }
                } else if (searching && complete && month != null) item(key = "search-scope") {
                    Notice(stringResource(R.string.act_search_scope, monthLabel(month)), null) {}
                }
                if (content != null && activeFilter.isBounded) item(key = "summary") {
                    SummaryPanel(content, totals, activeFilter, filtered = activeFilter.narrows && (activeFilter.type != null || activeFilter.categoryId != null) || searching,
                        query = searchQuery, onDay = { day ->
                            val index = lines.indexOfFirst { it is ActivityLine.DayHeader && it.date == day }
                            if (index >= 0) scope.launch { listState.animateScrollToItem(index + headerItems(searchOpen, onApplyFilter != null, searching, complete, month)) }
                        })
                }
                if (onApplyFilter != null) item(key = "type") {
                    TypeSegment(activeFilter.type) { onApplyFilter(activeFilter.copy(type = it, categoryId = null, categoryName = null)) }
                }
                item(key = "chips") {
                    ActiveChips(activeFilter, if (showingLocalHistory) null else amountOrder, onApplyFilter, onAmountOrder, onClearAll = { searchOpen = false; onClearFilter() })
                }
                if (showingLocalHistory) {
                    item(key = "local-history") {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.local_history_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.local_history_notice))
                            ActionButton(stringResource(R.string.transactions_retry_refresh), onRefresh)
                            localHistory.forEach { row ->
                                val status = when (row.state) {
                                    com.elect3.mobdev.personalfinance.data.local.LocalTransactionState.Pending -> R.string.local_history_pending
                                    com.elect3.mobdev.personalfinance.data.local.LocalTransactionState.Synced -> R.string.local_history_synced
                                    com.elect3.mobdev.personalfinance.data.local.LocalTransactionState.Rejected -> R.string.local_history_rejected
                                }
                                Text("${row.draft.transactionDate} · ${row.categoryName ?: row.draft.type.wireValue} · ${row.draft.amount.toPlainString()} ${row.draft.currencyCode}")
                                Text(stringResource(status), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else when (state) {
                    TransactionListUiState.InitialLoading -> item(key = "initial-loading") { LoadingPanel(stringResource(R.string.transactions_loading)) }
                    TransactionListUiState.Empty -> item(key = "empty") {
                        EmptyActivity(activeFilter, today, isAuthenticated, onAddTransaction, onApplyFilter, onClearFilter, onRefresh)
                    }
                    is TransactionListUiState.InitialError -> item(key = "initial-error") {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            MessagePanel(stringResource(R.string.transactions_unavailable_title), failureMessage(state.reason, isAuthenticated), isError = true) {
                                ActionButton(stringResource(R.string.transactions_retry), onRetryInitialLoad)
                            }
                        }
                    }
                    is TransactionListUiState.Content -> {
                        state.refreshError?.let { reason ->
                            item(key = "refresh-error") {
                                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    MessagePanel(stringResource(R.string.transactions_refresh_error_title),
                                        stringResource(R.string.transactions_refresh_error_message, failureMessage(reason, isAuthenticated)), isError = true) {
                                        ActionButton(stringResource(R.string.transactions_retry_refresh), onRefresh, enabled = !state.isRefreshing)
                                    }
                                }
                            }
                        }
                        if (state.isRefreshing) item(key = "refresh-loading") { LoadingPanel(stringResource(R.string.transactions_refreshing)) }
                        if (lines.isEmpty()) item(key = "no-match") {
                            EmptyMessage(R.drawable.ic_cat_info, stringResource(R.string.act_no_match_title), stringResource(R.string.act_no_match_message)) {
                                OutlinedAction(stringResource(R.string.act_clear_search_filters)) { searchOpen = false; onClearFilter() }
                            }
                        }
                        lines.forEach { line ->
                            item(key = line.key, contentType = line::class) {
                                when (line) {
                                    is ActivityLine.MonthHeader -> MonthHeader(line.month)
                                    is ActivityLine.DayHeader -> DayHeader(line)
                                    is ActivityLine.OrderHeader -> OrderHeader(line)
                                    is ActivityLine.Row -> ActivityRow(line, searchQuery, showOriginalAmounts, baseCurrency) { detail = line.transaction }
                                }
                            }
                        }
                        item(key = "end") {
                            ListEnd(state, activeFilter, today, onLoadMore, onRetryAppend, onApplyFilter, isAuthenticated)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null },
        onEdit = onEditTransaction?.takeIf { isAuthenticated }?.let { edit -> { t: Transaction -> detail = null; edit(t) } }) }
    if (picker && onApplyFilter != null) MonthPickerSheet(month ?: YearMonth.from(today), YearMonth.from(today),
        onPick = { picker = false; onApplyFilter(activeFilter.forMonth(it)) },
        onAllTime = { picker = false; onApplyFilter(activeFilter.allTime()) }, onDismiss = { picker = false })
}

/** Items rendered before the first list line (for scroll-to-day). */
private fun headerItems(search: Boolean, months: Boolean, searching: Boolean, complete: Boolean, month: YearMonth?): Int {
    var n = 1 // header
    if (search) n++
    if (months) n++
    if ((search && searching && !complete) || (searching && complete && month != null)) n++
    n++ // summary
    if (months) n++ // type
    n++ // chips
    return n
}

internal fun monthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.month.getDisplayName(TextStyle.SHORT, locale) + " " + month.year
internal fun monthLong(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year
private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
private fun signedPhp(t: Transaction) = (if (t.type == TransactionType.Income) "+" else "−") + formatTransactionAmount(t.amountBase, t.currencyCode.takeIf { isPlainPhp(t) } ?: "PHP")
private fun rowAmount(t: Transaction) = (if (t.type == TransactionType.Income) "+" else "−") + formatTransactionAmount(t.amount, t.currencyCode)
private fun signedNet(v: BigDecimal) = (if (v.signum() < 0) "−" else "+") + formatPhpAmount(v.abs())

@Composable
private fun ActivityHeader(isAuthenticated: Boolean, searchOpen: Boolean, filterCount: Int, enabled: Boolean, onSearch: () -> Unit, onFilter: (() -> Unit)?) {
    val owli = LocalOwliColors.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (isAuthenticated) R.string.transactions_account_data else R.string.transactions_demo_data),
                style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.transactions_title), style = LocalOwliTypography.current.screenTitle, modifier = Modifier.semantics { heading() })
        }
        if (enabled) {
            HeaderIcon(R.drawable.ic_cat_search, stringResource(R.string.act_search), pressed = searchOpen, onClick = onSearch)
            if (onFilter != null) Box {
                HeaderIcon(R.drawable.ic_cat_sliders, if (filterCount > 0) stringResource(R.string.act_filters_active, filterCount)
                    else stringResource(R.string.act_filters), pressed = false, onClick = onFilter)
                if (filterCount > 0) Text(filterCount.toString(), style = LocalOwliTypography.current.micro, color = MaterialTheme.colorScheme.surface,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(18.dp).clip(CircleShape)
                        .background(owli.accent).clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: Int, description: String, pressed: Boolean, onClick: () -> Unit) {
    val owli = LocalOwliColors.current
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (pressed) null else BorderStroke(1.dp, owli.hairline),
        modifier = Modifier.size(48.dp).semantics { contentDescription = description; selected = pressed }) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val owli = LocalOwliColors.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Opening search puts the cursor in the field.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface).border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_cat_search), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(R.string.act_search_hint), style = LocalOwliTypography.current.body) },
            textStyle = LocalOwliTypography.current.body, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.weight(1f).focusRequester(focus))
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_cat_x), stringResource(R.string.act_search_clear), modifier = Modifier.size(18.dp))
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.act_search_close), color = owli.accent, style = LocalOwliTypography.current.label)
        }
    }
}

@Composable
private fun MonthRow(filter: TransactionFilter, today: LocalDate, total: Long?, onMonth: (YearMonth) -> Unit, onPick: () -> Unit, onToggle: () -> Unit) {
    val owli = LocalOwliColors.current
    val month = filter.month
    val current = YearMonth.from(today)
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, owli.hairline, RoundedCornerShape(16.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (month != null) IconButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month.year > 2000, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.act_prev_month), modifier = Modifier.size(20.dp))
            }
            val title = when {
                month != null -> monthLabel(month)
                filter.isBounded || filter.fromDate != null || filter.toDate != null -> stringResource(R.string.act_custom_range)
                else -> stringResource(R.string.act_all_time)
            }
            val description = stringResource(R.string.act_choose_month, title)
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onPick)
                .semantics(mergeDescendants = true) { contentDescription = description }, horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LocalOwliTypography.current.bodyStrong)
                if (month == null && total != null && !filter.isBounded) Text("  " + stringResource(R.string.act_all_time_total, total.toInt()),
                    style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.ic_cat_chevd), null, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
            if (month != null) IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < current, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_cat_chevr), stringResource(R.string.act_next_month), modifier = Modifier.size(20.dp))
            }
        }
        val allTime = month == null
        Surface(onClick = onToggle, shape = RoundedCornerShape(16.dp), color = if (allTime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (allTime) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            border = if (allTime) null else BorderStroke(1.dp, owli.hairline), modifier = Modifier.heightIn(min = 54.dp).semantics { selected = allTime }) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (allTime) R.string.act_by_month else R.string.act_all_time), style = LocalOwliTypography.current.label)
            }
        }
    }
}

@Composable
private fun SummaryPanel(content: TransactionListUiState.Content, totals: PeriodTotals?, filter: TransactionFilter, filtered: Boolean, query: String, onDay: (LocalDate) -> Unit) {
    val owli = LocalOwliColors.current
    val month = filter.month
    val period = month?.let { monthLong(it) } ?: (formatTransactionDate(filter.fromDate!!) + " – " + formatTransactionDate(filter.toDate!!))
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        OwliMidnightPanel {
            Text(stringResource(R.string.act_net_flow).uppercase(), style = LocalOwliTypography.current.overline, color = owli.onPanelMuted)
            if (totals == null) {
                Text(stringResource(if (content.complete) R.string.act_totals_unavailable else R.string.act_totals_partial),
                    style = LocalOwliTypography.current.body, color = owli.onPanel, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                return@OwliMidnightPanel
            }
            val shown = if (query.isBlank()) totals else periodTotals(content.items.filter { matchesSearch(it, query) }) ?: totals
            Text(signedNet(shown.net), style = LocalOwliTypography.current.heroAmount.copy(fontSize = LocalOwliTypography.current.heroAmount.fontSize * 0.72f),
                color = owli.onPanel, modifier = Modifier.padding(top = 8.dp))
            val caption = (if (shown.count == 1) stringResource(R.string.act_summary_caption_one, period) else stringResource(R.string.act_summary_caption, period, shown.count)) +
                if (filtered) " · " + stringResource(R.string.act_filtered) else ""
            Text(caption, style = LocalOwliTypography.current.caption, color = owli.onPanelMuted)
            FlowRow18 {
                Column { Text(stringResource(R.string.act_income), style = LocalOwliTypography.current.caption, color = owli.onPanelMuted)
                    Text(formatPhpAmount(shown.income), style = LocalOwliTypography.current.amountRow, color = owli.panelIncome) }
                Column { Text(stringResource(R.string.act_expenses), style = LocalOwliTypography.current.caption, color = owli.onPanelMuted)
                    Text(formatPhpAmount(shown.expenses), style = LocalOwliTypography.current.amountRow, color = owli.panelExpense) }
            }
            if (month != null) DayBars(month, shown.volumeByDay, onDay)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FlowRow18(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun DayBars(month: YearMonth, byDay: Map<LocalDate, BigDecimal>, onDay: (LocalDate) -> Unit) {
    val owli = LocalOwliColors.current
    val max = byDay.values.maxOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    Row(Modifier.fillMaxWidth().height(40.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        (1..month.lengthOfMonth()).forEach { d ->
            val date = month.atDay(d)
            val v = byDay[date]
            val fraction = if (v == null) 0.1f else (v.toDouble() / max.toDouble()).toFloat().coerceIn(0.2f, 1f)
            val label = stringResource(R.string.act_bar_label, formatTransactionDate(date), formatPhpAmount(v ?: BigDecimal.ZERO))
            Box(Modifier.weight(1f).fillMaxHeight().then(if (v != null) Modifier.clickable(role = Role.Button) { onDay(date) } else Modifier)
                .semantics { contentDescription = label }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (v == null) Color.White.copy(alpha = 0.14f) else owli.panelIncome.copy(alpha = 0.75f)))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("1", style = LocalOwliTypography.current.micro, color = owli.onPanelMuted)
        Text(stringResource(R.string.act_bars_hint), style = LocalOwliTypography.current.micro, color = owli.onPanelMuted)
        Text(month.lengthOfMonth().toString(), style = LocalOwliTypography.current.micro, color = owli.onPanelMuted)
    }
}

@Composable
private fun TypeSegment(type: TransactionType?, onType: (TransactionType?) -> Unit) {
    val owli = LocalOwliColors.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).clip(CircleShape).background(owli.field)
        .border(1.dp, owli.hairline, CircleShape).padding(3.dp)) {
        listOf(null to R.string.act_type_all, TransactionType.Expense to R.string.act_type_expenses, TransactionType.Income to R.string.act_type_income).forEach { (t, label) ->
            val on = type == t
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(role = Role.Tab) { if (!on) onType(t) }.semantics { selected = on }, contentAlignment = Alignment.Center) {
                Text(stringResource(label), style = LocalOwliTypography.current.label,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveChips(filter: TransactionFilter, order: AmountOrder?, onApply: ((TransactionFilter) -> Unit)?, onOrder: (AmountOrder?) -> Unit, onClearAll: () -> Unit) {
    if (onApply == null) return
    val chips = buildList {
        if (filter.month == null && filter.isBounded) add(formatTransactionDate(filter.fromDate!!) + " → " + formatTransactionDate(filter.toDate!!) to { onApply(filter.forMonth(YearMonth.from(filter.toDate))) })
        filter.categoryName?.let { add(it to { onApply(filter.copy(categoryId = null, categoryName = null)) }) }
        if (filter.sort == TransactionListSort.Oldest) add("oldest" to { onApply(filter.copy(sort = TransactionListSort.Newest)) })
        order?.let { add((if (it == AmountOrder.Largest) "largest" else "smallest") to { onOrder(null) }) }
    }
    if (chips.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        chips.forEach { (raw, remove) ->
            val label = when (raw) { "oldest" -> stringResource(R.string.filter_sort_oldest); "largest" -> stringResource(R.string.act_largest_first)
                "smallest" -> stringResource(R.string.act_smallest_first); else -> raw }
            Chip(label, remove)
        }
        TextButton(onClick = onClearAll, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.act_clear_all), style = LocalOwliTypography.current.label, color = LocalOwliColors.current.accent)
        }
    }
}

@Composable
private fun Chip(label: String, onRemove: () -> Unit) {
    val owli = LocalOwliColors.current
    val description = stringResource(R.string.act_remove_filter, label)
    Row(Modifier.clip(CircleShape).background(owli.accentSoft).clickable(role = Role.Button, onClick = onRemove)
        .heightIn(min = 40.dp).padding(start = 12.dp, end = 8.dp).clearAndSetSemantics { contentDescription = description; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = LocalOwliTypography.current.label, color = if (isDarkSurface()) owli.tileContent else MaterialTheme.colorScheme.primary)
        Icon(painterResource(R.drawable.ic_cat_x), null, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp).clip(RoundedCornerShape(12.dp))
        .background(LocalOwliColors.current.accentSoft).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = LocalOwliTypography.current.caption, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(action, style = LocalOwliTypography.current.label, color = LocalOwliColors.current.accent)
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth) {
    Text(monthLong(month), style = LocalOwliTypography.current.bodyStrong,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp).semantics { heading() })
}

@Composable
private fun DayHeader(line: ActivityLine.DayHeader) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(line.date.format(dayFormat).uppercase(), style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
        line.net?.let { Text(signedNet(it), style = LocalOwliTypography.current.caption,
            color = if (it.signum() > 0) LocalOwliColors.current.income else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun OrderHeader(line: ActivityLine.OrderHeader) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(if (line.order == AmountOrder.Largest) R.string.act_largest_first else R.string.act_smallest_first).uppercase(),
            style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.act_count, line.count), style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rows of one group form a single rounded card (top/bottom corners on the first/last row). */
@Composable
private fun ActivityRow(line: ActivityLine.Row, query: String, showOriginalAmounts: Boolean, baseCurrency: String, onClick: () -> Unit) {
    val t = line.transaction
    val owli = LocalOwliColors.current
    val r = 18.dp
    val shape = RoundedCornerShape(topStart = if (line.first) r else 0.dp, topEnd = if (line.first) r else 0.dp,
        bottomStart = if (line.last) r else 0.dp, bottomEnd = if (line.last) r else 0.dp)
    val hex = CategoryVisuals.palette[Math.floorMod(t.categoryId, CategoryVisuals.palette.size)].hex
    val base = categoryColor(hex)
    val dark = isDarkSurface()
    val meta = listOfNotNull(t.description, if (line.showDate) formatTransactionDate(t.transactionDate) else null).joinToString(" · ")
    val display = com.elect3.mobdev.personalfinance.domain.currency.historicalAmountDisplay(t, showOriginalAmounts, baseCurrency)
    val sign = if (t.type == TransactionType.Income) "+" else "−"
    val amount = sign + formatTransactionAmount(display.primary, display.primaryCurrency)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(BorderStroke(1.dp, owli.hairline), shape)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 62.dp).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(lerp(MaterialTheme.colorScheme.surface, base, if (dark) 0.24f else 0.15f)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(CategoryVisuals.icon(CategoryVisuals.defaultIconKey(t.categoryName, t.type)).drawable), null,
                    tint = if (dark) lerp(base, Color.White, 0.52f) else lerp(base, MaterialTheme.colorScheme.onSurface, 0.22f), modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.categoryName, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.weight(1f, fill = false))
                    if (t.billId != null) Icon(painterResource(R.drawable.ic_cat_lock), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                }
                if (meta.isNotEmpty()) Text(meta, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                // Amounts are never truncated; they wrap if needed.
                Text(amount, style = LocalOwliTypography.current.amountRow, textAlign = TextAlign.End,
                    color = if (t.type == TransactionType.Income) owli.income else MaterialTheme.colorScheme.onSurface)
                if (display.secondary != null) Text(sign + formatTransactionAmount(display.secondary, checkNotNull(display.secondaryCurrency)), style = LocalOwliTypography.current.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ListEnd(state: TransactionListUiState.Content, filter: TransactionFilter, today: LocalDate, onLoadMore: () -> Unit, onRetry: () -> Unit,
                    onApply: ((TransactionFilter) -> Unit)?, isAuthenticated: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val remaining = (state.totalCount - state.items.size).coerceAtLeast(0)
        when (val append = state.append) {
            TransactionAppendState.Ready -> OutlinedAction(stringResource(R.string.act_show_more, minOf(20L, remaining).toInt(), remaining.toInt()),
                enabled = !state.isRefreshing, dashed = true, onClick = onLoadMore)
            TransactionAppendState.Loading -> LoadingPanel(stringResource(R.string.transactions_loading_more))
            is TransactionAppendState.Error -> MessagePanel(stringResource(R.string.transactions_append_error_title), failureMessage(append.reason, isAuthenticated), isError = true) {
                ActionButton(stringResource(R.string.transactions_retry_append), onRetry, enabled = !state.isRefreshing)
            }
            TransactionAppendState.End -> {
                val month = filter.month
                Text(when { month != null -> stringResource(R.string.act_end_month, monthLong(month))
                    filter.isBounded -> stringResource(R.string.act_end_range)
                    else -> stringResource(R.string.act_end_all, state.totalCount.toInt()) },
                    style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (month != null && onApply != null && filter.type == null && filter.categoryId == null) OutlinedAction(
                    stringResource(R.string.act_previous_month_button, monthLabel(month.minusMonths(1)))) { onApply(filter.forMonth(month.minusMonths(1))) }
            }
        }
    }
}

@Composable
private fun EmptyActivity(filter: TransactionFilter, today: LocalDate, isAuthenticated: Boolean, onAdd: (() -> Unit)?, onApply: ((TransactionFilter) -> Unit)?,
                          onClear: () -> Unit, onRefresh: () -> Unit) {
    val month = filter.month
    val criteria = filter.type != null || filter.categoryId != null
    when {
        criteria -> EmptyMessage(R.drawable.ic_cat_search, stringResource(R.string.act_no_match_title), stringResource(R.string.act_no_match_message)) {
            OutlinedAction(stringResource(R.string.act_clear_search_filters), onClick = onClear)
        }
        month != null && onApply != null -> EmptyMessage(R.drawable.ic_cat_info, stringResource(R.string.act_empty_month_title, monthLong(month)),
            stringResource(R.string.act_empty_month_message)) {
            OutlinedAction(stringResource(R.string.act_show_all_time)) { onApply(filter.allTime()) }
            OutlinedAction(stringResource(R.string.act_previous_month_button, monthLabel(month.minusMonths(1)))) { onApply(filter.forMonth(month.minusMonths(1))) }
        }
        filter.isBounded && onApply != null -> EmptyMessage(R.drawable.ic_cat_info, stringResource(R.string.act_no_match_title), stringResource(R.string.act_no_match_message)) {
            OutlinedAction(stringResource(R.string.act_show_all_time)) { onApply(filter.allTime()) }
        }
        // No history at all: the only Add entry on this screen besides the central (+).
        else -> EmptyMessage(R.drawable.ic_owli_mark, stringResource(R.string.act_empty_none_title), stringResource(
            if (isAuthenticated) R.string.transactions_account_empty_message else R.string.transactions_empty_message)) {
            if (onAdd != null) Button(onClick = onAdd, shape = LocalOwliShapes.current.button, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.act_add_first), style = LocalOwliTypography.current.button)
            } else OutlinedAction(stringResource(R.string.transactions_refresh), onClick = onRefresh)
        }
    }
}

@Composable
private fun EmptyMessage(icon: Int, title: String, message: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(LocalOwliColors.current.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = LocalOwliColors.current.accent, modifier = Modifier.size(32.dp))
        }
        Text(title, style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(message, style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun OutlinedAction(label: String, enabled: Boolean = true, dashed: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (dashed) LocalOwliColors.current.accent.copy(alpha = 0.5f) else LocalOwliColors.current.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dashed) LocalOwliColors.current.accent else MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.then(if (dashed) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp)) {
        Text(label, style = LocalOwliTypography.current.label, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(t: Transaction, onDismiss: () -> Unit, onEdit: ((Transaction) -> Unit)? = null) {
    val owli = LocalOwliColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val hex = CategoryVisuals.palette[Math.floorMod(t.categoryId, CategoryVisuals.palette.size)].hex
            com.elect3.mobdev.personalfinance.ui.categories.CategoryDisc(CategoryVisuals.defaultIconKey(t.categoryName, t.type), hex, size = 56.dp, iconSize = 26.dp)
            Text(t.categoryName, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.semantics { heading() })
            Text(rowAmount(t), style = LocalOwliTypography.current.heroAmount.copy(fontSize = LocalOwliTypography.current.heroAmount.fontSize * 0.72f),
                color = if (t.type == TransactionType.Income) owli.income else MaterialTheme.colorScheme.onSurface)
            Text(if (isPlainPhp(t)) t.currencyCode else stringResource(R.string.act_detail_converted, formatTransactionAmount(t.amount, t.currencyCode), signedPhp(t)),
                style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.billId != null) Row(Modifier.clip(CircleShape).background(owli.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_cat_lock), null, modifier = Modifier.size(13.dp))
                Text(stringResource(R.string.act_detail_bill), style = LocalOwliTypography.current.label)
            }
            Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = LocalOwliShapes.current.group, border = BorderStroke(1.dp, owli.hairline),
                color = MaterialTheme.colorScheme.surface) {
                Column {
                    DetailRow(stringResource(R.string.act_detail_type), stringResource(if (t.type == TransactionType.Income) R.string.transaction_income else R.string.transaction_expense))
                    HorizontalDivider(color = owli.hairline)
                    DetailRow(stringResource(R.string.act_detail_date), formatTransactionDate(t.transactionDate))
                    HorizontalDivider(color = owli.hairline)
                    DetailRow(stringResource(R.string.act_detail_description), t.description ?: stringResource(R.string.act_detail_none))
                }
            }
            // Stored server snapshot only (rate with 8 decimals and its date); nothing is recalculated here.
            if (!isPlainPhp(t)) com.elect3.mobdev.personalfinance.ui.transactions.currency.ConversionDetailsCard(t, "PHP",
                Modifier.padding(top = 6.dp))
            if (onEdit != null && t.billId == null && t.billDueDate == null)
                Button(onClick = { onEdit(t) }, shape = LocalOwliShapes.current.button, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp)) {
                    Text(stringResource(R.string.fx_edit_action), style = LocalOwliTypography.current.label)
                }
            OutlinedAction(stringResource(R.string.act_close), onClick = onDismiss)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** Month grid without per-month counts: the API offers no count per month without one request each. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MonthPickerSheet(chosen: YearMonth, current: YearMonth, onPick: (YearMonth) -> Unit, onAllTime: () -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableIntStateOf(chosen.year) }
    val owli = LocalOwliColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.act_jump_title), style = LocalOwliTypography.current.sectionTitle, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.act_close), color = owli.accent) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }, enabled = year > 2000, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.act_year_prev), modifier = Modifier.size(20.dp)) }
                Text(year.toString(), style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { year++ }, enabled = year < current.year, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_cat_chevr), stringResource(R.string.act_year_next), modifier = Modifier.size(20.dp)) }
            }
            (1..12).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        val ym = YearMonth.of(year, m)
                        val on = ym == chosen
                        val enabled = ym <= current
                        Box(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) owli.accentSoft else owli.field)
                            .border(if (on) 1.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else owli.hairline, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled, role = Role.Button) { onPick(ym) }.semantics { selected = on; contentDescription = monthLong(ym) },
                            contentAlignment = Alignment.Center) {
                            Text(ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = LocalOwliTypography.current.bodyStrong,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
            OutlinedButton(onClick = onAllTime, shape = LocalOwliShapes.current.button, border = BorderStroke(1.dp, owli.hairline),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.act_show_all_time)) }
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
private fun LoadingPanel(label: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessagePanel(title: String, message: String, isError: Boolean = false, actions: @Composable () -> Unit) {
    Surface(shape = LocalOwliShapes.current.group, color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(message, style = LocalOwliTypography.current.body)
            actions()
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.chip,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline), modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun failureMessage(reason: TransactionFailure, isAuthenticated: Boolean = false): String = when (reason) {
    TransactionFailure.Unavailable -> stringResource(if (isAuthenticated) R.string.transactions_account_unavailable_message else R.string.transactions_unavailable_message)
    TransactionFailure.SessionExpired -> stringResource(if (isAuthenticated) R.string.auth_session_expired else R.string.transactions_session_message)
}
