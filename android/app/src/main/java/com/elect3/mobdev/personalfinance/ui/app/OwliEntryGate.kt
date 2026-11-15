package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.auth.LoginRoute
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Login is the default of a new activity-owned model. Demo state is never authentication. */
@Composable
fun OwliEntryGate(
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier,
    demoContent: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Retain the existing tab/navigation and lazy-list saveable state when visiting Login.
    // Actual transaction data/pagination remain in the unchanged activity-owned ViewModels.
    val demoState = rememberSaveableStateHolder()
    if (state.isExploringDemo) {
        // Registered before child navigation: Transactions handles Back first; Dashboard exits demo.
        BackHandler { viewModel.returnToLogin() }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.auth_demo_title),
                            style = LocalOwliTypography.current.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::returnToLogin,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.auth_return_to_login),
                                style = LocalOwliTypography.current.bodyStrong)
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                demoState.SaveableStateProvider("mock-demo") { demoContent() }
            }
        }
    } else {
        LoginRoute(viewModel, modifier, onExploreMockDemo = viewModel::exploreMockDemo)
    }
}
