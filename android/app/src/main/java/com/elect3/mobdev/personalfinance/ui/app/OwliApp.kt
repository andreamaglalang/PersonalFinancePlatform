package com.elect3.mobdev.personalfinance.ui.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elect3.mobdev.personalfinance.ui.components.LocalOwliFilterRequest
import com.elect3.mobdev.personalfinance.ui.components.OwliFilterRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.NoAuthDiagnostics
import com.elect3.mobdev.personalfinance.data.auth.BackDiagnosticSource
import com.elect3.mobdev.personalfinance.data.auth.observeBackDispatch
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.components.LocalOwliShellActions
import com.elect3.mobdev.personalfinance.ui.components.OwliShellActions
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

private enum class OwliDestination(val route: String, val label: Int, val icon: Int) {
    Dashboard("dashboard", R.string.navigation_dashboard, R.drawable.ic_owli_home),
    Transactions("transactions", R.string.navigation_transactions, R.drawable.ic_owli_transactions_list),
    Plan("plan", R.string.navigation_plan, R.drawable.ic_owli_plan),
    Insights("insights", R.string.navigation_insights, R.drawable.ic_owli_insights),
}

/**
 * Two peer destinations (Home, Activity) in the Owlight floating bar, with a centre quick-add action.
 * Owners supply lifecycle-managed routes, not eagerly created models. [onAddTransaction] opens the owner's
 * existing Add Transaction dialog; when null the centre action is not shown.
 */
@Composable
fun OwliApp(
    dashboardContent: @Composable () -> Unit,
    transactionsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    diagnosticInstanceId: Int = 0,
    onAddTransaction: ((TransactionType?) -> Unit)? = null,
    onOpenCategories: (() -> Unit)? = null,
    onOpenAccount: (() -> Unit)? = null,
    /** Plan destination content; null keeps Plan as an unavailable "Soon" item. */
    planContent: (@Composable () -> Unit)? = null,
    /** Each increment asks the shell to show the Dashboard tab (e.g. "Back to Dashboard" after a save). */
    dashboardRequests: Int = 0,
    /** Insights (Financial Reports) content; null keeps Insights as an unavailable "Soon" item. */
    insightsContent: (@Composable () -> Unit)? = null,
    /** Opens the notifications inbox from the Dashboard header; null hides the entry (Mock Demo). */
    onOpenNotifications: (() -> Unit)? = null,
    notificationUnreadCount: Long? = null,
    onRefreshNotifications: (() -> Unit)? = null,
) {
    val navController = rememberNavController()
    val currentPlan by androidx.compose.runtime.rememberUpdatedState(planContent)
    val currentInsights by androidx.compose.runtime.rememberUpdatedState(insightsContent)
    val insightsAvailable = insightsContent != null
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = backStackEntry?.destination?.route

    fun navigateTo(destination: OwliDestination) {
        if (destination.route == navController.currentDestination?.route) return
        if (diagnostics.enabled) when (destination) {
            OwliDestination.Dashboard -> AuthDiagnosticEvent.NAV_DASHBOARD_SELECTED
            OwliDestination.Transactions -> AuthDiagnosticEvent.NAV_TRANSACTIONS_SELECTED
            OwliDestination.Plan, OwliDestination.Insights -> null // no diagnostic events exist for these
        }?.let { diagnostics.record(it, diagnosticInstanceId, null) }
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    var filterRequested by remember { mutableStateOf(false) }
    val shellActions = remember(onAddTransaction, onOpenCategories, onOpenAccount, insightsAvailable, onOpenNotifications, notificationUnreadCount, onRefreshNotifications) {
        OwliShellActions(addTransaction = onAddTransaction, openActivity = { navigateTo(OwliDestination.Transactions) },
            openActivityFilter = { filterRequested = true; navigateTo(OwliDestination.Transactions) },
            openCategories = onOpenCategories, openAccount = onOpenAccount,
            openInsights = if (insightsAvailable) ({ navigateTo(OwliDestination.Insights) }) else null,
            openNotifications = onOpenNotifications, notificationUnreadCount = notificationUnreadCount, refreshNotifications = onRefreshNotifications)
    }
    LaunchedEffect(dashboardRequests) { if (dashboardRequests > 0) navigateTo(OwliDestination.Dashboard) }
    val filterRequest = OwliFilterRequest(pending = filterRequested, consume = { filterRequested = false })

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            OwliFloatingNavigation(
                selectedRoute = selectedRoute,
                onSelect = { navigateTo(it) },
                onAdd = onAddTransaction?.let { add -> { add(null) } },
                planAvailable = planContent != null,
                insightsAvailable = insightsAvailable,
            )
        },
    ) { innerPadding ->
        // Consume host/system-bar space so the screens' existing safeDrawingPadding
        // does not add it again. The bar's height is excluded from the content viewport.
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            CompositionLocalProvider(LocalOwliShellActions provides shellActions, LocalOwliFilterRequest provides filterRequest) {
                NavHost(
                    navController = navController,
                    startDestination = OwliDestination.Dashboard.route,
                    enterTransition = { androidx.compose.animation.EnterTransition.None },
                    exitTransition = { androidx.compose.animation.ExitTransition.None },
                    popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                    popExitTransition = { androidx.compose.animation.ExitTransition.None },
                ) {
                    composable(OwliDestination.Dashboard.route) { dashboardContent() }
                    composable(OwliDestination.Transactions.route) {
                        transactionsContent()
                        // Destination content composes after NavHost's internal Back handler.
                        // Register here (not beside Scaffold) so this destination owns Back.
                        // Keep the route guard while outgoing content awaits disposal.
                        BackHandler(enabled = selectedRoute == OwliDestination.Transactions.route) {
                            observeBackDispatch(diagnostics, diagnosticInstanceId, BackDiagnosticSource.TRANSACTIONS) {
                                navigateTo(OwliDestination.Dashboard)
                            }
                        }
                    }
                    // Always registered so the graph never changes shape between recompositions.
                    composable(OwliDestination.Plan.route) {
                        currentPlan?.invoke()
                        // Same rule as Activity: Back from Plan returns to Home.
                        BackHandler(enabled = selectedRoute == OwliDestination.Plan.route) { navigateTo(OwliDestination.Dashboard) }
                    }
                    composable(OwliDestination.Insights.route) {
                        currentInsights?.invoke()
                        BackHandler(enabled = selectedRoute == OwliDestination.Insights.route) { navigateTo(OwliDestination.Dashboard) }
                    }
                }
            }
        }
    }
}

/**
 * Owlight floating bar: Home · Activity · (+) · Plan · Insights (mockup order).
 * The raised (+) is drawn in the outer container, NOT inside the bar's rounded Surface: a Surface clips
 * its children to its shape, which previously cropped the top of the offset button.
 * Plan and Insights are destinations when the owner supplies their content; otherwise they are shown dimmed,
 * labelled "Soon" and are not actionable.
 */
@Composable
private fun OwliFloatingNavigation(
    selectedRoute: String?,
    onSelect: (OwliDestination) -> Unit,
    onAdd: (() -> Unit)?,
    planAvailable: Boolean = false,
    insightsAvailable: Boolean = false,
) {
    val owli = LocalOwliColors.current
    val background = MaterialTheme.colorScheme.background
    val raised = onAdd != null && LocalConfiguration.current.fontScale <= 1.3f
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.42f to background))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            // Room above the bar for the raised button's overhang (button 56dp, bar row 64dp).
            modifier = Modifier.padding(top = if (raised) 22.dp else 0.dp).widthIn(max = 520.dp).fillMaxWidth(),
            shape = LocalOwliShapes.current.bottomBar,
            color = owli.navBackground,
            border = BorderStroke(1.dp, owli.hairline),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(6.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                OwliNavItem(OwliDestination.Dashboard, selectedRoute, onSelect, Modifier.weight(1f))
                OwliNavItem(OwliDestination.Transactions, selectedRoute, onSelect, Modifier.weight(1f))
                if (onAdd != null) {
                    if (raised) Box(Modifier.width(68.dp)) else OwliAddButton(onAdd, raised = false)
                }
                if (planAvailable) OwliNavItem(OwliDestination.Plan, selectedRoute, onSelect, Modifier.weight(1f))
                else OwliUnavailableNavItem(R.string.navigation_plan, R.drawable.ic_owli_plan, Modifier.weight(1f))
                if (insightsAvailable) OwliNavItem(OwliDestination.Insights, selectedRoute, onSelect, Modifier.weight(1f))
                else OwliUnavailableNavItem(R.string.navigation_insights, R.drawable.ic_owli_insights, Modifier.weight(1f))
            }
        }
        if (raised && onAdd != null) OwliAddButton(onAdd, raised = true)
    }
}

@Composable
private fun OwliNavItem(destination: OwliDestination, selectedRoute: String?, onSelect: (OwliDestination) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val selected = destination.route == selectedRoute
    val color = if (selected) scheme.primary else LocalOwliColors.current.navUnselected
    Column(
        modifier.heightIn(min = 52.dp).clip(LocalOwliShapes.current.navigationIndicator)
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(destination.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        // Labels wrap naturally at large font scales instead of truncating.
        Text(stringResource(destination.label), style = LocalOwliTypography.current.micro, color = color,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (selected) color else Color.Transparent))
    }
}

/** Planned destination: visible for orientation, announced as unavailable, never navigates. */
@Composable
private fun OwliUnavailableNavItem(label: Int, icon: Int, modifier: Modifier) {
    val color = LocalOwliColors.current.navUnselected.copy(alpha = 0.55f)
    val name = stringResource(label)
    val description = stringResource(R.string.navigation_coming_soon_description, name)
    Column(
        modifier.heightIn(min = 52.dp).padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = description; disabled() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(name, style = LocalOwliTypography.current.micro, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.navigation_soon), style = LocalOwliTypography.current.micro.copy(fontSize = 9.sp),
            color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Round ink button that opens Add Transaction; raised above the bar unless large text is in use. */
@Composable
private fun OwliAddButton(onAdd: () -> Unit, raised: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.navigation_add)
    Surface(
        onClick = onAdd,
        modifier = Modifier.padding(horizontal = if (raised) 0.dp else 6.dp).size(56.dp).semantics { contentDescription = label },
        shape = CircleShape,
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        border = BorderStroke(4.dp, scheme.background),
        shadowElevation = if (raised) 8.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_owli_plus), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}
