package com.elect3.mobdev.personalfinance.ui.app

import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import com.elect3.mobdev.personalfinance.data.local.CategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.local.DeviceCategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.local.InMemoryCategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.domain.repository.CategoryWriteRepository
import com.elect3.mobdev.personalfinance.ui.transactions.add.AddTransactionFlow
import com.elect3.mobdev.personalfinance.ui.transactions.add.FlowStart
import com.elect3.mobdev.personalfinance.ui.transactions.add.FormWorkflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.NoAuthDiagnostics
import com.elect3.mobdev.personalfinance.domain.repository.TransactionCreationRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.AccountTransactionStore
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListRoute
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import com.elect3.mobdev.personalfinance.domain.model.TransactionType

/** Scope sits above navigation: tabs retain pagination, logout disposes account data. */
@Composable
fun OwliFinanceContent(
    authenticatedContentKey: String?,
    authenticatedRepository: TransactionRepository?,
    demoTransactions: () -> TransactionListViewModel,
    isSessionCurrent: (String) -> Boolean,
    onSessionExpired: (String) -> Unit,
    dashboardContent: @Composable () -> Unit,
    sessionKeys: Flow<String?> = emptyFlow(),
    authenticatedCategories: CategoryRepository? = null,
    authenticatedCurrency: () -> String? = { null },
    authenticatedCreation: TransactionCreationRepository? = null,
    /** Signed-in online edits (PUT api/transactions/{id}); null hides Edit. Never handed to Mock Demo. */
    authenticatedUpdates: com.elect3.mobdev.personalfinance.domain.repository.TransactionUpdateRepository? = null,
    /** Signed-in read-only live exchange-rate quotes for the Currency converter; null keeps the "no live quote" state. */
    onPasswordChanged: (Boolean) -> Unit = {},
    authenticatedLive: com.elect3.mobdev.personalfinance.data.remote.LiveAccountPlanRepository? = null,
    authenticatedPreferenceNamespace: String = "",
    authenticatedQuotes: com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository? = null,
    /** Signed-in Dashboard renderer for this login's real data. Null keeps [dashboardContent] for every mode. */
    accountDashboard: (@Composable (DashboardViewModel) -> Unit)? = null,
    diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    diagnosticInstanceId: Int = 0,
    /** Signed-in category management (api/categories); null leaves Category settings read-only. */
    authenticatedCategoryWrites: CategoryWriteRepository? = null,
    authenticatedAccountId: () -> Int? = { null },
    /** Mock Demo in-memory session (ledger, categories, icon/order). Owner-retained; a local one is used if absent. */
    demoSession: DemoSession? = null,
    /** Mock Demo data changed (a demo save): reload the demo Dashboard and Activity. */
    onDemoDataChanged: () -> Unit = {},
    /** Account: theme preference, the signed-in profile (null in demo) and the real logout / demo exit. Null hides Account. */
    appearance: com.elect3.mobdev.personalfinance.data.local.AppearancePreference = com.elect3.mobdev.personalfinance.data.local.AppearancePreference.System,
    onAppearance: (com.elect3.mobdev.personalfinance.data.local.AppearancePreference) -> Unit = {},
    accountProfile: () -> com.elect3.mobdev.personalfinance.data.auth.AccountProfile? = { null },
    onLogout: (() -> Unit)? = null,
    /** Signed-in budgets (api/budgets); Mock Demo uses the demo session's in-memory budgets instead. */
    authenticatedBudgets: com.elect3.mobdev.personalfinance.domain.repository.BudgetRepository? = null,
    /** Device-local (Room) ledger for signed-in offline-first recording. Never used by Mock Demo. */
    offlineLedger: com.elect3.mobdev.personalfinance.data.local.LocalLedger? = null,
    offlineCategoryCache: com.elect3.mobdev.personalfinance.data.local.LocalCategoryCache? = null,
) {
    var formOpen by remember(authenticatedContentKey) { mutableStateOf(false) }
    var formInitialType by remember(authenticatedContentKey) { mutableStateOf<TransactionType?>(null) }
    var flowStart by remember(authenticatedContentKey) { mutableStateOf(FlowStart.AddTransaction) }
    var dashboardRequests by remember(authenticatedContentKey) { mutableIntStateOf(0) }
    var accountOpen by remember(authenticatedContentKey) { mutableStateOf(false) }
    var converterOpen by remember(authenticatedContentKey) { mutableStateOf(false) }
    val demo = demoSession ?: remember { DemoSession() }
    val context = LocalContext.current
    // Signed-in icon/order choices: this device only, scoped to the account ID; never shared with Mock Demo.
    val accountPresentation: CategoryPresentationStore? = if (authenticatedContentKey == null) null else remember(authenticatedContentKey) {
        authenticatedAccountId()?.let { id ->
            DeviceCategoryPresentationStore(context.applicationContext.getSharedPreferences(DeviceCategoryPresentationStore.FILE, Context.MODE_PRIVATE), id)
        } ?: InMemoryCategoryPresentationStore()
    }
    val accountId = if (authenticatedContentKey != null) remember(authenticatedContentKey) { authenticatedAccountId() } else null
    val preferenceStore = remember(authenticatedContentKey, accountId, authenticatedPreferenceNamespace) {
        if (authenticatedContentKey == null || accountId == null || authenticatedPreferenceNamespace.isBlank()) null
        else com.elect3.mobdev.personalfinance.domain.currency.AccountCurrencyPreferences(
            com.elect3.mobdev.personalfinance.data.local.DeviceCurrencyPreferenceStorage(
                context.getSharedPreferences(com.elect3.mobdev.personalfinance.data.local.DeviceCurrencyPreferenceStorage.FILE, android.content.Context.MODE_PRIVATE)),
            authenticatedPreferenceNamespace, accountId)
    }
    val preferenceFlow = remember(preferenceStore) { preferenceStore?.preferences ?: kotlinx.coroutines.flow.MutableStateFlow(com.elect3.mobdev.personalfinance.domain.currency.CurrencyPreferences()) }
    val currencyPreferences by preferenceFlow.collectAsStateWithLifecycle()
    val categorySelection = remember(authenticatedContentKey, authenticatedCategories, offlineCategoryCache, accountId) {
        val key = authenticatedContentKey
        if (key == null || authenticatedCategories == null || offlineCategoryCache == null || accountId == null) null
        else com.elect3.mobdev.personalfinance.data.local.CachedCategoryRepository(
            authenticatedCategories, offlineCategoryCache, authenticatedAccountId,
            isSessionCurrent = { isSessionCurrent(key) },
            sessionIdentity = { if (isSessionCurrent(key)) key else null },
            // Debug builds log redacted category request/fallback events (OwliCategory); release is a no-op.
            diagnostics = com.elect3.mobdev.personalfinance.data.local.CategoryDiagnostics)
    }
    val account = if (authenticatedContentKey != null) {
        val key = authenticatedContentKey
        val remote = checkNotNull(authenticatedRepository)
        val repository = remember(key, remote, offlineLedger, accountId) {
            if (offlineLedger != null && accountId != null)
                com.elect3.mobdev.personalfinance.data.local.cacheTransactionHistory(remote, offlineLedger, accountId) { isSessionCurrent(key) }
            else remote
        }
        val scope = remember(key, repository) {
            AccountTransactionStore(repository, { isSessionCurrent(key) }, {
                if (isSessionCurrent(key)) onSessionExpired(key)
            }, accountCurrency = authenticatedCurrency,
                initialFilter = com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter().forMonth(java.time.YearMonth.now()))
        }
        DisposableEffect(scope) { onDispose { scope.close() } }
        LaunchedEffect(scope, sessionKeys) { scope.invalidateOnSessionChange(sessionKeys, key) }
        scope
    } else null
    // Offline-first (signed in only): queue on connectivity loss with the same ClientId, sync when back online.
    val offlineCreation = remember(authenticatedContentKey, offlineLedger, authenticatedCreation) {
        if (authenticatedContentKey == null || offlineLedger == null || authenticatedCreation == null) null
        else com.elect3.mobdev.personalfinance.data.local.OfflineFirstCreationRepository(authenticatedCreation, offlineLedger, { accountId })
    }
    val pendingSync = remember(authenticatedContentKey, offlineLedger, authenticatedCreation) {
        if (authenticatedContentKey == null || offlineLedger == null || authenticatedCreation == null) null
        else com.elect3.mobdev.personalfinance.data.local.PendingTransactionSync(authenticatedCreation, offlineLedger)
    }
    val localHistory: (suspend () -> List<com.elect3.mobdev.personalfinance.data.local.LocalHistoryTransaction>)? =
        remember(authenticatedContentKey, offlineLedger, accountId) {
            val key = authenticatedContentKey
            if (key == null || offlineLedger == null || accountId == null) null else suspend {
                if (!isSessionCurrent(key)) emptyList() else offlineLedger.history(accountId).let {
                    if (isSessionCurrent(key)) it else emptyList()
                }
            }
        }
    val unsynced = if (offlineLedger != null && accountId != null)
        androidx.compose.runtime.produceState(emptyList<com.elect3.mobdev.personalfinance.data.local.LocalPendingTransaction>(), offlineLedger, accountId) {
            offlineLedger.unsynced(accountId).collect { value = it }
        }.value else emptyList()
    var syncing by remember(authenticatedContentKey) { mutableStateOf(false) }
    val syncScope = androidx.compose.runtime.rememberCoroutineScope()
    val runSync: () -> Unit = {
        val key = authenticatedContentKey
        if (key != null && pendingSync != null && accountId != null && !syncing) {
            syncing = true
            syncScope.launch {
                try {
                    val outcome = pendingSync.sync(accountId) { isSessionCurrent(key) }
                    if (outcome is com.elect3.mobdev.personalfinance.data.local.SyncOutcome.SessionEnded && isSessionCurrent(key)) onSessionExpired(key)
                    if (outcome is com.elect3.mobdev.personalfinance.data.local.SyncOutcome.Done && outcome.sent > 0) {
                        account?.model()?.refreshAfterConfirmedCreation(); account?.refreshDashboard()
                    }
                } finally { syncing = false }
            }
        }
    }
    // Sync at sign-in and whenever the device regains a network connection.
    LaunchedEffect(authenticatedContentKey, pendingSync) { runSync() }
    val context2 = LocalContext.current
    DisposableEffect(authenticatedContentKey, pendingSync) {
        val cm = context2.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { syncScope.launch { runSync() } }
        }
        val registered = pendingSync != null && cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    // Online edit (signed in only). Confirmed edits also refresh this device's copy of the row (same ClientId).
    var editing by remember(authenticatedContentKey) { mutableStateOf<com.elect3.mobdev.personalfinance.domain.model.Transaction?>(null) }
    val accountUpdates = remember(authenticatedContentKey, authenticatedUpdates, offlineLedger, accountId) {
        val updates = authenticatedUpdates
        if (authenticatedContentKey == null || updates == null) null
        else if (offlineLedger != null && accountId != null)
            com.elect3.mobdev.personalfinance.data.local.LedgerRecordingUpdateRepository(updates, offlineLedger, { accountId })
        else updates
    }
    // Same rule as before for every Add entry point: Mock Demo, or a signed-in account with categories.
    val canAdd = account == null || authenticatedCategories != null
    val openAdd: ((TransactionType?) -> Unit)? = if (canAdd) ({ type -> formInitialType = type; flowStart = FlowStart.AddTransaction; formOpen = true }) else null
    val openCategories: (() -> Unit)? = if (canAdd) ({ formInitialType = null; flowStart = FlowStart.CategorySettings; formOpen = true }) else null
    // Budgets for this session only: demo -> in-memory demo store; signed in -> the account's API. Never mixed.
    val budgetStore = remember(authenticatedContentKey) { androidx.lifecycle.ViewModelStore() }
    DisposableEffect(budgetStore) { onDispose { budgetStore.clear() } }
    val budgetRepo = if (authenticatedContentKey == null) demo.budgets else authenticatedBudgets
    val budgetCategories = if (authenticatedContentKey == null) demo.categories else authenticatedCategories
    val budgetsVm = if (budgetRepo == null || budgetCategories == null) null else remember(budgetStore) {
        val key = authenticatedContentKey
        androidx.lifecycle.ViewModelProvider(budgetStore, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return com.elect3.mobdev.personalfinance.ui.plan.BudgetsViewModel(budgetRepo, budgetCategories, java.time.YearMonth.now(),
                    isSessionCurrent = { key == null || isSessionCurrent(key) },
                    onSessionExpired = { if (key != null && isSessionCurrent(key)) onSessionExpired(key) },
                    accountCurrency = if (key == null) ({ "PHP" }) else authenticatedCurrency) as T
            }
        })[com.elect3.mobdev.personalfinance.ui.plan.BudgetsViewModel::class.java]
    }
    // Unsynced local transactions (signed in): read through State so the stable Plan lambda sees changes.
    val pendingLocal = androidx.compose.runtime.rememberUpdatedState(if (authenticatedContentKey == null) 0 else unsynced.size)
    // Stable Plan content for this session (a new lambda each recomposition would churn navigation).
    val planContent: @Composable () -> Unit = remember(authenticatedContentKey, budgetsVm) {
        {
            val budgetState = budgetsVm?.state?.collectAsStateWithLifecycle()?.value
            val demoGoals = if (authenticatedContentKey == null) demo.plan.goals.collectAsStateWithLifecycle().value else emptyList()
            val demoBills = if (authenticatedContentKey == null) demo.plan.bills.collectAsStateWithLifecycle().value else emptyList()
            val pending = pendingLocal.value
            LaunchedEffect(budgetsVm) { budgetsVm?.consumeNotice() }
            // Re-read each time Plan opens (and when the unsynced queue changes, e.g. after a sync), so budgets
            // reflect transactions added elsewhere (demo or account).
            LaunchedEffect(budgetsVm, pending) { budgetsVm?.refresh() }
            com.elect3.mobdev.personalfinance.ui.plan.PlanScreen(demo = authenticatedContentKey == null,
                month = java.time.YearMonth.now(), sections = com.elect3.mobdev.personalfinance.ui.plan.PlanSections(
                    budgets = if (budgetsVm != null && budgetState != null) ({
                        com.elect3.mobdev.personalfinance.ui.plan.BudgetsSection(budgetState, budgetsVm, demo = authenticatedContentKey == null,
                            pendingLocalCount = pending)
                    }) else null,
                    budgetCount = budgetState?.budgets?.size ?: 0,
                    goals = { if (authenticatedContentKey != null && authenticatedLive != null) com.elect3.mobdev.personalfinance.ui.plan.LivePlanSection(authenticatedLive, false, authenticatedCategories) else com.elect3.mobdev.personalfinance.ui.plan.GoalsSection(authenticatedContentKey == null, demoGoals, demo.plan::contribute) },
                    goalCount = demoGoals.size,
                    bills = { if (authenticatedContentKey != null && authenticatedLive != null) com.elect3.mobdev.personalfinance.ui.plan.LivePlanSection(authenticatedLive, true, authenticatedCategories, onPaid = { budgetsVm?.refresh(); account?.model()?.refreshAfterConfirmedCreation(); account?.refreshDashboard() }) else com.elect3.mobdev.personalfinance.ui.plan.BillsSection(authenticatedContentKey == null, demoBills, demo.plan::markPaid) },
                    billCount = demoBills.size))
        }
    }
    // Insights (Financial Reports) for this session only: demo -> in-memory sample ledger (PHP); signed in -> this
    // account's GET api/transactions date-filtered reads, totalled from server base amounts. Never mixed.
    val demoReportsStore = remember(authenticatedContentKey) { androidx.lifecycle.ViewModelStore() }
    DisposableEffect(demoReportsStore) { onDispose { demoReportsStore.clear() } }
    val reportsVm: com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel? = if (account != null) {
        if (account.supportsReports) account.reportsModel() else null
    } else remember(demoReportsStore) {
        val loader = com.elect3.mobdev.personalfinance.domain.reports.LoadFinancialReport(
            com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository(
                com.elect3.mobdev.personalfinance.data.mock.MockScenario.Normal, demo.ledger),
            baseCurrency = { "PHP" })
        androidx.lifecycle.ViewModelProvider(demoReportsStore,
            com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel.Factory({ loader(it) }))[
            com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel::class.java]
    }
    val insightsContent: (@Composable () -> Unit)? = if (reportsVm == null) null else remember(authenticatedContentKey, reportsVm) {
        {
            // Re-read on each visit so figures reflect transactions saved elsewhere (no-op while a read is running).
            LaunchedEffect(reportsVm) { reportsVm.refresh() }
            com.elect3.mobdev.personalfinance.ui.reports.ReportsRoute(reportsVm, demo = authenticatedContentKey == null,
                currentMonth = { java.time.YearMonth.now() })
        }
    }
    OwliApp(
        // Demo (no account scope) always uses the owner's sample Dashboard; it never reads account data.
        dashboardContent = if (account != null && accountDashboard != null) ({ accountDashboard(account.dashboardModel()) })
            else dashboardContent,
        transactionsContent = {
            TransactionListRoute(account?.model() ?: demoTransactions(), isAuthenticated = account != null,
                filterCategories = if (account == null) demo.categories else authenticatedCategories,
                localHistory = localHistory,
                showOriginalAmounts = currencyPreferences.showOriginalAmounts,
                baseCurrency = authenticatedCurrency() ?: "PHP",
                offlineQueue = if (account != null && offlineLedger != null && accountId != null)
                    com.elect3.mobdev.personalfinance.ui.transactions.OfflineQueueUi(unsynced, syncing, runSync) { id ->
                        syncScope.launch { offlineLedger.discardUnsynced(accountId, id) }
                    } else null,
                onAddTransaction = openAdd?.let { add -> { add(null) } },
                onEditTransaction = if (account != null && accountUpdates != null) ({ editing = it }) else null)
        },
        diagnostics = diagnostics,
        diagnosticInstanceId = diagnosticInstanceId,
        onAddTransaction = openAdd,
        onOpenCategories = openCategories,
        onOpenAccount = onLogout?.let { { accountOpen = true } },
        // Plan is offered where the rest of the finance features are (demo, or a signed-in account with categories).
        planContent = if (canAdd) planContent else null,
        dashboardRequests = dashboardRequests,
        insightsContent = insightsContent,
    )
    val editTarget = editing
    val editKey = authenticatedContentKey
    if (editTarget != null && editKey != null && accountUpdates != null) {
        com.elect3.mobdev.personalfinance.ui.transactions.edit.EditTransactionDialog(
            transaction = editTarget,
            baseCurrency = authenticatedCurrency(),
            updates = accountUpdates,
            categories = categorySelection ?: authenticatedCategories,
            isCurrent = { isSessionCurrent(editKey) },
            onExpired = { if (isSessionCurrent(editKey)) onSessionExpired(editKey) },
            onClose = { editing = null },
            onSaved = { budgetsVm?.refresh(); account?.model()?.refreshAfterConfirmedCreation(); account?.refreshDashboard() },
        )
    }
    if (accountOpen && onLogout != null) {
        com.elect3.mobdev.personalfinance.ui.account.AccountDialog(
            mode = if (authenticatedContentKey == null) com.elect3.mobdev.personalfinance.ui.account.AccountMode.Demo
                else com.elect3.mobdev.personalfinance.ui.account.AccountMode.SignedIn(accountProfile()),
            appearance = appearance, onAppearance = onAppearance,
            // Opens the existing Category settings over Account; Back returns here.
            onManageCategories = openCategories,
            onLogout = onLogout,
            onClose = { accountOpen = false },
            // Read-only Currency converter, opened over Account; Back returns here.
            onOpenCurrencyConverter = { converterOpen = true },
            live = if (authenticatedContentKey == null) null else authenticatedLive,
            onSessionEnded = { authenticatedContentKey?.let(onSessionExpired) },
            onPasswordChanged = onPasswordChanged,
            currencyPreferences = preferenceStore,
            isCurrent = { authenticatedContentKey != null && isSessionCurrent(authenticatedContentKey) },
        )
    }
    if (converterOpen) {
        // Rates the server already confirmed on this account's loaded transactions (never in Mock Demo).
        val loaded = (account?.model()?.state?.value as? com.elect3.mobdev.personalfinance.ui.transactions.TransactionListUiState.Content)?.items.orEmpty()
        val base = if (authenticatedContentKey == null) "PHP" else authenticatedCurrency()
        com.elect3.mobdev.personalfinance.ui.currency.CurrencyConverterDialog(
            baseCurrency = base,
            demo = authenticatedContentKey == null,
            confirmed = if (account == null) emptyList() else com.elect3.mobdev.personalfinance.domain.currency.CurrencyConverter.confirmedRates(loaded, base),
            // Signed in only: hand off to the existing Add Transaction flow (the converter itself never writes).
            onRecordTransaction = if (account != null && openAdd != null) ({ converterOpen = false; accountOpen = false; openAdd(null) }) else null,
            onClose = { converterOpen = false },
            quotes = if (authenticatedContentKey == null) null else authenticatedQuotes,
            isCurrent = { authenticatedContentKey == null || isSessionCurrent(authenticatedContentKey) },
            onExpired = { authenticatedContentKey?.let { if (isSessionCurrent(it)) onSessionExpired(it) } },
        )
    }
    if (formOpen) {
        val key = authenticatedContentKey
        AddTransactionFlow(
            start = flowStart,
            initialType = formInitialType,
            categories = if (key == null) demo.categories else checkNotNull(authenticatedCategories),
            selectionCategories = if (key == null || flowStart == FlowStart.CategorySettings) null else categorySelection,
            // Signed-in writes go to the API only; Mock Demo writes stay in the in-memory demo store.
            categoryWrites = if (key == null) demo.categories else authenticatedCategoryWrites,
            presentation = if (key == null) demo.presentation else checkNotNull(accountPresentation),
            workflow = if (key == null) FormWorkflow.MockDemo else FormWorkflow.Authenticated,
            currency = if (key == null) "PHP" else authenticatedCurrency(),
            sessionKey = key, sessionKeys = sessionKeys,
            isCurrent = { key == null || isSessionCurrent(key) },
            onExpired = { if (key != null && isSessionCurrent(key)) onSessionExpired(key) },
            onClose = { formOpen = false; formInitialType = null },
            onSaved = {
                budgetsVm?.refresh()
                if (key == null) onDemoDataChanged()
                // Confirmed server save only: reload this account's list, and its Dashboard if already opened.
                else { account?.model()?.refreshAfterConfirmedCreation(); account?.refreshDashboard() }
            },
            onShowDashboard = { dashboardRequests++ },
            // Real API writes exist only for an authenticated session; Mock Demo never receives a creator.
            // Signed in: offline-first wrapper when a local ledger exists (queues on connectivity loss).
            accountCreation = if (key == null) null else (offlineCreation ?: authenticatedCreation),
            demoLedger = if (key == null) demo.ledger else null,
            defaultCurrency = if (key == null) null else currencyPreferences.defaultCurrency,
            reviewRateBeforeSave = key != null && currencyPreferences.reviewRateBeforeSave,
            historicalQuotes = if (key == null) null else authenticatedQuotes as? com.elect3.mobdev.personalfinance.domain.currency.HistoricalExchangeRateQuoteRepository,
        )
    }
}
