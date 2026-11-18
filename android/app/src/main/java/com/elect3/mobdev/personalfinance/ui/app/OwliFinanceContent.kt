package com.elect3.mobdev.personalfinance.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.ui.transactions.add.AddTransactionDialog
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
) {
    var formOpen by remember(authenticatedContentKey) { mutableStateOf(false) }
    var formInitialType by remember(authenticatedContentKey) { mutableStateOf<TransactionType?>(null) }
    val demoCategories = remember { DemoCategoryRepository() }
    // Demo filtering uses categories derived from the demo records so IDs match the filtered data.
    val demoFilterCategories = remember { com.elect3.mobdev.personalfinance.data.mock.DemoFixtureCategoryRepository() }
    val account = if (authenticatedContentKey != null) {
        val key = authenticatedContentKey
        val repository = checkNotNull(authenticatedRepository)
        val scope = remember(key, repository) {
            AccountTransactionStore(repository, { isSessionCurrent(key) }, {
                if (isSessionCurrent(key)) onSessionExpired(key)
            }, accountCurrency = authenticatedCurrency)
        }
        DisposableEffect(scope) { onDispose { scope.close() } }
        LaunchedEffect(scope, sessionKeys) { scope.invalidateOnSessionChange(sessionKeys, key) }
        scope
    } else null
    // Same rule as before for every Add entry point: Mock Demo, or a signed-in account with categories.
    val canAdd = account == null || authenticatedCategories != null
    val openAdd: ((TransactionType?) -> Unit)? = if (canAdd) ({ type -> formInitialType = type; formOpen = true }) else null
    OwliApp(
        // Demo (no account scope) always uses the owner's sample Dashboard; it never reads account data.
        dashboardContent = if (account != null && accountDashboard != null) ({ accountDashboard(account.dashboardModel()) })
            else dashboardContent,
        transactionsContent = {
            TransactionListRoute(account?.model() ?: demoTransactions(), isAuthenticated = account != null,
                filterCategories = if (account == null) demoFilterCategories else authenticatedCategories,
                onAddTransaction = openAdd?.let { add -> { add(null) } })
        },
        diagnostics = diagnostics,
        diagnosticInstanceId = diagnosticInstanceId,
        onAddTransaction = openAdd,
    )
    if (formOpen) {
        val key = authenticatedContentKey
        AddTransactionDialog(
            repository = if (key == null) demoCategories else checkNotNull(authenticatedCategories),
            workflow = if (key == null) FormWorkflow.MockDemo else FormWorkflow.Authenticated,
            currency = if (key == null) "PHP" else authenticatedCurrency(),
            sessionKey = key, sessionKeys = sessionKeys,
            isCurrent = { key == null || isSessionCurrent(key) },
            onExpired = { if (key != null && isSessionCurrent(key)) onSessionExpired(key) },
            onClose = { formOpen = false; formInitialType = null },
            initialType = formInitialType,
            // Confirmed server save only: reload this account's list, and its Dashboard if already opened.
            onSaved = { account?.model()?.refreshAfterConfirmedCreation(); account?.refreshDashboard() },
            // Real API writes exist only for an authenticated session; Mock Demo never receives a creator.
            accountCreation = if (key == null) null else authenticatedCreation,
        )
    }
}
