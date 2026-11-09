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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R

private enum class OwliDestination(val label: Int, val icon: Int) {
    Dashboard(R.string.navigation_dashboard, R.drawable.ic_dashboard),
    Transactions(R.string.navigation_transactions, R.drawable.ic_transactions),
}

/** Two peer destinations; owners supply lifecycle-managed routes, not eagerly created models. */
@Composable
fun OwliApp(
    dashboardContent: @Composable () -> Unit,
    transactionsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedKey by rememberSaveable { mutableStateOf(OwliDestination.Dashboard.name) }
    val selected = OwliDestination.valueOf(selectedKey)
    val destinationState = rememberSaveableStateHolder()

    BackHandler(enabled = selected == OwliDestination.Transactions) {
        selectedKey = OwliDestination.Dashboard.name
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                OwliDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selected,
                        onClick = { if (destination != selected) selectedKey = destination.name },
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
            destinationState.SaveableStateProvider(selected.name) {
                when (selected) {
                    OwliDestination.Dashboard -> dashboardContent()
                    OwliDestination.Transactions -> transactionsContent()
                }
            }
        }
    }
}
