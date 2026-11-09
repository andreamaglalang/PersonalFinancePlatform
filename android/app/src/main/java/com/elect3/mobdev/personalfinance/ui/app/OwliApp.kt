package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elect3.mobdev.personalfinance.R

private enum class OwliDestination(val route: String, val label: Int, val icon: Int) {
    Dashboard("dashboard", R.string.navigation_dashboard, R.drawable.ic_dashboard),
    Transactions("transactions", R.string.navigation_transactions, R.drawable.ic_transactions),
}

/** Two peer destinations; owners supply lifecycle-managed routes, not eagerly created models. */
@Composable
fun OwliApp(
    dashboardContent: @Composable () -> Unit,
    transactionsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = backStackEntry?.destination?.route

    fun navigateTo(destination: OwliDestination) {
        if (destination.route == navController.currentDestination?.route) return
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                OwliDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination.route == selectedRoute,
                        onClick = { navigateTo(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = {
                            // Use the item's bounded width rather than a shrink-wrapped
                            // text width; retain natural wrapping at larger font scales.
                            Text(
                                stringResource(destination.label),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        alwaysShowLabel = true,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        // Consume host/system-bar space so the screens' existing safeDrawingPadding
        // does not add it again. The bar's height is excluded from the content viewport.
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
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
                        navigateTo(OwliDestination.Dashboard)
                    }
                }
            }
        }
    }
}
