package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.auth.LoginRoute
import com.elect3.mobdev.personalfinance.ui.auth.EntryMode
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Login is the default of a new activity-owned model. Demo state is never authentication. */
@Composable
fun OwliEntryGate(
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier,
    authenticatedContent: (@Composable (String) -> Unit)? = null,
    demoContent: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, state.entryMode) { viewModel.recordGateTransition(state.entryMode) }
    // Retain the existing tab/navigation and lazy-list saveable state when visiting Login.
    // Authenticated financial content owns its own disposable, per-login state.
    val demoState = rememberSaveableStateHolder()
    if (state.entryMode != EntryMode.SignedOut) {
        val authenticated = state.entryMode == EntryMode.Authenticated
        // Registered before child navigation: Transactions handles Back first; Dashboard exits demo.
        BackHandler(enabled = !authenticated) { viewModel.returnToLoginFromBack() }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                // Owlight session strip: who/what is shown, plus the only exit (Log out / Return to Login).
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(
                                if (authenticated && authenticatedContent != null) LocalOwliColors.current.income else LocalOwliColors.current.accent))
                            Text(stringResource(if (authenticated && authenticatedContent != null) R.string.auth_signed_in_title
                                else if (authenticated) R.string.auth_authenticated_sample_title else R.string.auth_demo_title),
                                style = LocalOwliTypography.current.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { if (authenticated) viewModel.logout() else viewModel.returnToLogin() },
                                shape = LocalOwliShapes.current.chip,
                                border = BorderStroke(1.dp, LocalOwliColors.current.hairline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.ic_owli_logout), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (authenticated) R.string.auth_logout else R.string.auth_return_to_login),
                                    style = LocalOwliTypography.current.label)
                            }
                        }
                        HorizontalDivider(color = LocalOwliColors.current.hairline)
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (authenticated) {
                    // Fresh Dashboard for each login. Never retain this navigation stack after logout/expiry.
                    val contentKey = checkNotNull(state.authenticatedContentKey)
                    key(contentKey) { if (authenticatedContent != null) authenticatedContent(contentKey) else demoContent() }
                } else {
                    demoState.SaveableStateProvider("mock-demo") { demoContent() }
                }
            }
        }
    } else {
        LoginRoute(viewModel, modifier, onExploreMockDemo = viewModel::exploreMockDemo)
    }
}
