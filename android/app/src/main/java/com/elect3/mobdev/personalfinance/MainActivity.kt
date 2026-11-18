package com.elect3.mobdev.personalfinance

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                LoginViewModel::class.java -> {
                    val repository = InMemoryAuthRepository(authConfiguration, AuthDiagnostics)
                    val finance = FinanceRepositories(authConfiguration, repository)
                    LoginViewModel.Factory(
                        repository = repository,
                        diagnostics = AuthDiagnostics,
                        authenticatedTransactions = finance.transactions,
                        authenticatedCategories = finance.categories,
                        authenticatedCurrency = finance.baseCurrency,
                        authenticatedCreation = finance.creation,
                    ).create(modelClass)
                }
                DashboardViewModel::class.java -> DashboardViewModel.Factory(
                    loader = LoadDashboardSummary(MockTransactionRepository(MockScenario.Normal)),
                    // Used only for an empty demo; nonempty data selects its latest month.
                    fallbackMonth = YearMonth.of(2026, 11),
                ).create(modelClass)
                // This branch creates nothing until Transactions is first visited.
                TransactionListViewModel::class.java -> TransactionListViewModel.Factory(
                    repository = MockTransactionRepository(MockScenario.Normal),
                    replacementFactory = { MockTransactionRepository(MockScenario.Normal) },
                ).create(modelClass)
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
        setContent {
            PersonalFinanceTheme {
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
                        sessionKeys = sessionKeys,
                        isSessionCurrent = { loginViewModel.state.value.authenticatedContentKey == it },
                        onSessionExpired = {
                            if (loginViewModel.state.value.authenticatedContentKey == it) loginViewModel.sessionExpired()
                        },
                        diagnostics = AuthDiagnostics,
                        diagnosticInstanceId = loginViewModel.diagnosticId,
                        // Demo: activity-scoped sample Dashboard. Signed in: this login's real data only.
                        dashboardContent = { DashboardRoute(viewModel = dashboardViewModel) },
                        accountDashboard = { DashboardRoute(viewModel = it, source = DashboardSource.Account) },
                        demoTransactions = { provider.get(TransactionListViewModel::class.java) },
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
