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
    val demo = demoSession ?: remember { DemoSession() }
    val context = LocalContext.current
    // Signed-in icon/order choices: this device only, scoped to the account ID; never shared with Mock Demo.
    val accountPresentation: CategoryPresentationStore? = if (authenticatedContentKey == null) null else remember(authenticatedContentKey) {
        authenticatedAccountId()?.let { id ->
            DeviceCategoryPresentationStore(context.applicationContext.getSharedPreferences(DeviceCategoryPresentationStore.FILE, Context.MODE_PRIVATE), id)
        } ?: InMemoryCategoryPresentationStore()
    }
    val accountId = if (authenticatedContentKey != null) remember(authenticatedContentKey) { authenticatedAccountId() } else null
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
                    onSessionExpired = { if (key != null && isSessionCurrent(key)) onSessionExpired(key) }) as T
            }
        })[com.elect3.mobdev.personalfinance.ui.plan.BudgetsViewModel::class.java]
    }
    // Stable Plan content for this session (a new lambda each recomposition would churn navigation).
    val planContent: @Composable () -> Unit = remember(authenticatedContentKey, budgetsVm) {
        {
            val budgetState = budgetsVm?.state?.collectAsStateWithLifecycle()?.value
            // Re-read each time Plan opens, so budgets reflect transactions added elsewhere (demo or account).
            LaunchedEffect(budgetsVm) { budgetsVm?.consumeNotice(); budgetsVm?.refresh() }
            com.elect3.mobdev.personalfinance.ui.plan.PlanScreen(demo = authenticatedContentKey == null,
                month = java.time.YearMonth.now(), sections = com.elect3.mobdev.personalfinance.ui.plan.PlanSections(
                    budgets = if (budgetsVm != null && budgetState != null) ({
                        com.elect3.mobdev.personalfinance.ui.plan.BudgetsSection(budgetState, budgetsVm, demo = authenticatedContentKey == null)
                    }) else null,
                    budgetCount = budgetState?.budgets?.size ?: 0))
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
                offlineQueue = if (account != null && offlineLedger != null && accountId != null)
                    com.elect3.mobdev.personalfinance.ui.transactions.OfflineQueueUi(unsynced, syncing, runSync) { id ->
                        syncScope.launch { offlineLedger.discardRejected(accountId, id) }
                    } else null,
                onAddTransaction = openAdd?.let { add -> { add(null) } })
        },
        diagnostics = diagnostics,
        diagnosticInstanceId = diagnosticInstanceId,
        onAddTransaction = openAdd,
        onOpenCategories = openCategories,
        onOpenAccount = onLogout?.let { { accountOpen = true } },
        // Plan is offered where the rest of the finance features are (demo, or a signed-in account with categories).
        planContent = if (canAdd) planContent else null,
        dashboardRequests = dashboardRequests,
    )
    if (accountOpen && onLogout != null) {
        com.elect3.mobdev.personalfinance.ui.account.AccountDialog(
            mode = if (authenticatedContentKey == null) com.elect3.mobdev.personalfinance.ui.account.AccountMode.Demo
                else com.elect3.mobdev.personalfinance.ui.account.AccountMode.SignedIn(accountProfile()),
            appearance = appearance, onAppearance = onAppearance,
            // Opens the existing Category settings over Account; Back returns here.
            onManageCategories = openCategories,
            onLogout = onLogout,
            onClose = { accountOpen = false },
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
        )
    }
}
