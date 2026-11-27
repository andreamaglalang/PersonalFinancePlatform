package com.elect3.mobdev.personalfinance

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnostics
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticIds
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecyclePhase
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecycleSnapshot
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecycleDiagnosticRecord
import com.elect3.mobdev.personalfinance.data.auth.BackDiagnosticSource
import com.elect3.mobdev.personalfinance.data.auth.observeBackDispatch
import com.elect3.mobdev.personalfinance.data.remote.ApiEnvironment
import com.elect3.mobdev.personalfinance.data.remote.FinanceRepositories
import com.elect3.mobdev.personalfinance.data.mock.MockScenario
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.ui.app.OwliFinanceContent
import com.elect3.mobdev.personalfinance.ui.app.OwliEntryGate
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardRoute
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardSource
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import androidx.lifecycle.lifecycleScope
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.ui.auth.EntryMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Activity-retained holder for the Mock Demo session; cleared with the activity's ViewModels. */
class DemoSessionHolder : ViewModel() { val session = DemoSession() }

class MainActivity : ComponentActivity() {
    private val diagnosticId = AuthDiagnosticIds.next()
    private var diagnosticViewModelId: Int? = null
    private var diagnosticSavedStatePresent = false

    private fun recordLifecycle(phase: ActivityLifecyclePhase, flags: Int = intent?.flags ?: 0) {
        if (!AuthDiagnostics.enabled) return
        AuthDiagnostics.recordLifecycle(ActivityLifecycleDiagnosticRecord(phase, diagnosticId,
            ActivityLifecycleSnapshot(isFinishing, isChangingConfigurations, taskId, isTaskRoot,
                diagnosticSavedStatePresent, flags, diagnosticViewModelId)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticSavedStatePresent = savedInstanceState != null
        recordLifecycle(ActivityLifecyclePhase.CREATED)
        // Build-variant wiring: approved localhost in debug; unconfigured in release.
        val authConfiguration = ApiEnvironment.configuration()
        // Provider retains the ViewModel across configuration changes. Repositories are
        // created only when its factory is invoked, never during recomposition.
        val factory = object : ViewModelProvider.Factory {
            /** Mock Demo session store, retained with the activity's ViewModels (memory only). */
            fun demoLedger() = ViewModelProvider(this@MainActivity, this)[DemoSessionHolder::class.java].session.ledger
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                LoginViewModel::class.java -> {
                    val repository = InMemoryAuthRepository(
                        com.elect3.mobdev.personalfinance.data.remote.AuthApiClient(authConfiguration, AuthDiagnostics), diagnostics = AuthDiagnostics,
                        store = com.elect3.mobdev.personalfinance.data.auth.ProtectedSessionStore(applicationContext, authConfiguration.sessionNamespace()))
                    val finance = FinanceRepositories(authConfiguration, repository)
                    LoginViewModel.Factory(
                        repository = repository,
                        diagnostics = AuthDiagnostics,
                        authenticatedTransactions = finance.transactions,
                        authenticatedCategories = finance.categories,
                        authenticatedCurrency = finance.baseCurrency,
                        authenticatedCreation = finance.creation,
                        authenticatedCategoryWrites = finance.categoryWrites,
                        authenticatedAccountId = finance.accountId,
                        authenticatedBudgets = finance.budgets,
                        authenticatedUpdates = finance.transactionUpdates,
                        authenticatedQuotes = finance.exchangeRateQuotes,
                        authenticatedNotifications = finance.notifications,
                        authenticatedReports = finance.reports,
                        authenticatedLive = com.elect3.mobdev.personalfinance.data.remote.LiveAccountPlanRepository(authConfiguration, repository,
                            com.elect3.mobdev.personalfinance.data.local.RoomPendingRequestStore(
                                com.elect3.mobdev.personalfinance.data.local.room.OwliDatabase.get(applicationContext).pendingRequests())),
                    ).create(modelClass)
                }
                DashboardViewModel::class.java -> DashboardViewModel.Factory(
                    loader = LoadDashboardSummary(MockTransactionRepository(MockScenario.Normal, demoLedger())),
                    // Used only for an empty demo; nonempty data selects its latest month.
                    fallbackMonth = YearMonth.of(2026, 11),
                ).create(modelClass)
                // This branch creates nothing until Transactions is first visited.
                TransactionListViewModel::class.java -> TransactionListViewModel.Factory(
                    repository = MockTransactionRepository(MockScenario.Normal, demoLedger()),
                    replacementFactory = { MockTransactionRepository(MockScenario.Normal, demoLedger()) },
                    // Activity opens on the current month (read completely), like a signed-in account.
                    initialFilter = com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter().forMonth(YearMonth.now()),
                ).create(modelClass)
                DemoSessionHolder::class.java -> modelClass.cast(DemoSessionHolder())!!
                else -> throw IllegalArgumentException("Unsupported ViewModel class")
            }
        }
        val provider = ViewModelProvider(this, factory)
        val loginViewModel = provider.get(LoginViewModel::class.java)
        val sessionKeys = loginViewModel.state.map { it.authenticatedContentKey }
        diagnosticViewModelId = loginViewModel.diagnosticId
        recordLifecycle(ActivityLifecyclePhase.VIEW_MODEL_ATTACHED)
        val dashboardViewModel = provider
            .get(DashboardViewModel::class.java)
        val demoSession = provider[DemoSessionHolder::class.java].session
        val refreshDemoData = {
            dashboardViewModel.refresh()
            provider.get(TransactionListViewModel::class.java).refreshAfterConfirmedCreation()
        }
        // Leaving Mock Demo discards everything added during it (memory only), then reloads the sample views.
        lifecycleScope.launch {
            var previous: EntryMode? = null
            loginViewModel.state.map { it.entryMode }.distinctUntilChanged().collect { mode ->
                if (previous == EntryMode.MockDemo && mode != EntryMode.MockDemo) { demoSession.reset(); refreshDemoData() }
                previous = mode
            }
        }
        // Account > Appearance: device-local theme choice (System keeps the previous behaviour).
        val localDatabase = com.elect3.mobdev.personalfinance.data.local.room.OwliDatabase.get(this)
        val offlineLedger = com.elect3.mobdev.personalfinance.data.local.RoomLedger(localDatabase.transactions())
        val categoryCache = com.elect3.mobdev.personalfinance.data.local.RoomCategoryCache(localDatabase.categories())
        val appearanceStore = com.elect3.mobdev.personalfinance.data.local.AppearanceStore(
            getSharedPreferences(com.elect3.mobdev.personalfinance.data.local.AppearanceStore.FILE, MODE_PRIVATE))
        setContent {
            val appearance by appearanceStore.preference.collectAsState()
            val dark = when (appearance) {
                com.elect3.mobdev.personalfinance.data.local.AppearancePreference.Light -> false
                com.elect3.mobdev.personalfinance.data.local.AppearancePreference.Dark -> true
                com.elect3.mobdev.personalfinance.data.local.AppearancePreference.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // System bars follow the app theme (same colours as values/ and values-night/ themes.xml).
            androidx.compose.runtime.SideEffect {
                val bar = if (dark) 0xFF0C0E21.toInt() else 0xFFF7F4FD.toInt()
                window.statusBarColor = bar
                window.navigationBarColor = bar
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            PersonalFinanceTheme(darkTheme = dark) {
                LaunchedEffect(Unit) {
                    ApiEnvironment.checkConnectionIfRequested(
                        intent.getBooleanExtra("owli.debug.CHECK_HTTPS", false),
                    )
                }
                val financeContent: @androidx.compose.runtime.Composable (String?) -> Unit = { contentKey ->
                    OwliFinanceContent(
                        authenticatedContentKey = contentKey,
                        authenticatedRepository = loginViewModel.authenticatedTransactions,
                        authenticatedCategories = loginViewModel.authenticatedCategories,
                        authenticatedCurrency = loginViewModel.authenticatedCurrency,
                        authenticatedCreation = loginViewModel.authenticatedCreation,
                        authenticatedUpdates = loginViewModel.authenticatedUpdates,
                        authenticatedQuotes = loginViewModel.authenticatedQuotes,
                        authenticatedPreferenceNamespace = authConfiguration.sessionNamespace(),
                        authenticatedLive = loginViewModel.authenticatedLive,
                        sessionKeys = sessionKeys,
                        isSessionCurrent = { loginViewModel.state.value.authenticatedContentKey == it },
                        onPasswordChanged = { confirmed -> if (loginViewModel.state.value.authenticatedContentKey == contentKey) loginViewModel.passwordChangeCompleted(confirmed) },
                        onSessionExpired = {
                            if (loginViewModel.state.value.authenticatedContentKey == it) loginViewModel.sessionExpired()
                        },
                        diagnostics = AuthDiagnostics,
                        diagnosticInstanceId = loginViewModel.diagnosticId,
                        // Demo: activity-scoped sample Dashboard. Signed in: this login's real data only.
                        dashboardContent = { DashboardRoute(viewModel = dashboardViewModel) },
                        accountDashboard = { DashboardRoute(viewModel = it, source = DashboardSource.Account) },
                        demoTransactions = { provider.get(TransactionListViewModel::class.java) },
                        authenticatedCategoryWrites = loginViewModel.authenticatedCategoryWrites,
                        authenticatedAccountId = loginViewModel.authenticatedAccountId,
                        demoSession = demoSession,
                        onDemoDataChanged = refreshDemoData,
                        appearance = appearance,
                        onAppearance = appearanceStore::set,
                        accountProfile = loginViewModel::accountProfile,
                        authenticatedBudgets = loginViewModel.authenticatedBudgets,
                        authenticatedNotifications = loginViewModel.authenticatedNotifications,
                        authenticatedReports = loginViewModel.authenticatedReports,
                        // Room (app-private, this device): signed-in offline-first recording and pending sync.
                        offlineLedger = offlineLedger,
                        offlineCategoryCache = categoryCache,
                        // Signed in: the existing logout (server revocation attempt + local session clear). Demo: the existing exit.
                        onLogout = {
                            if (loginViewModel.state.value.isSignedIn) loginViewModel.logout() else loginViewModel.returnToLogin()
                        },
                    )
                }
                OwliEntryGate(viewModel = loginViewModel,
                    authenticatedContent = { financeContent(it) }) { financeContent(null) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        recordLifecycle(ActivityLifecyclePhase.STARTED)
    }

    override fun onResume() {
        super.onResume()
        recordLifecycle(ActivityLifecyclePhase.RESUMED)
        ViewModelProvider(this)[LoginViewModel::class.java].onForeground()
    }

    override fun onPause() {
        recordLifecycle(ActivityLifecyclePhase.PAUSED)
        super.onPause()
    }

    override fun onStop() {
        recordLifecycle(ActivityLifecyclePhase.STOPPED)
        super.onStop()
    }

    override fun onDestroy() {
        recordLifecycle(ActivityLifecyclePhase.DESTROYED)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recordLifecycle(ActivityLifecyclePhase.NEW_INTENT, intent.flags)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            observeBackDispatch(AuthDiagnostics, diagnosticId, BackDiagnosticSource.KEY_UP) {
                super.dispatchKeyEvent(event)
            }
        } else super.dispatchKeyEvent(event)

    // Observe the legacy callback used on the ASUS/API 28; do not install a consuming callback.
    @Suppress("DEPRECATION")
    @Deprecated("Delegates unchanged to ComponentActivity; retained only for bounded diagnostics")
    override fun onBackPressed() {
        observeBackDispatch(AuthDiagnostics, diagnosticId, BackDiagnosticSource.ACTIVITY_CALLBACK) {
            super.onBackPressed()
        }
    }
}
