package com.elect3.mobdev.personalfinance.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.ui.components.OwliBrand
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** The entry owner supplies PersonalFinanceTheme and explicit mock-demo navigation. */
@Composable
fun LoginRoute(
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier,
    onExploreMockDemo: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelLogin() } }
    LoginScreen(state, viewModel::login, modifier, onExploreMockDemo)
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onLogin: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onExploreMockDemo: (() -> Unit)? = null,
) {
    // Never saved to instance state. Password is removed from the field on submission/disposal.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val focus = LocalFocusManager.current
    val enabled = !state.isLoading && !state.isSignedIn
    fun submit() {
        if (!enabled) return
        val submittedPassword = password
        password = ""
        passwordVisible = false
        focus.clearFocus()
        onLogin(email, submittedPassword)
    }
    LaunchedEffect(state.isSignedIn) {
        if (state.isSignedIn) { email = ""; password = ""; passwordVisible = false }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                OwliBrand()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.auth_welcome), style = typography.screenTitle,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.app_tagline), style = typography.bodyStrong,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.auth_intro), style = typography.body)
                }
                Surface(shape = shapes.hero, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                            label = { Text(stringResource(R.string.auth_email)) }, textStyle = typography.bodyStrong,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                            label = { Text(stringResource(R.string.auth_password)) }, textStyle = typography.bodyStrong,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val visibilityLabel = stringResource(if (passwordVisible)
                                    R.string.auth_hide_password else R.string.auth_show_password)
                                TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = enabled,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                        .semantics { contentDescription = visibilityLabel }) {
                                    Text(stringResource(if (passwordVisible) R.string.auth_hide else R.string.auth_show),
                                        style = typography.caption)
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                        )
                        state.error?.let {
                            Text(stringResource(authErrorResource(it)), style = typography.body,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (state.revocationUnconfirmed) Text(stringResource(R.string.auth_logout_unconfirmed),
                            style = typography.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = { submit() }, enabled = enabled && email.isNotBlank() && password.isNotBlank(),
                            shape = shapes.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }) {
                            if (state.isLoading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(stringResource(when {
                                state.isLoading -> R.string.auth_signing_in
                                state.isSignedIn -> R.string.auth_signed_in
                                else -> R.string.auth_sign_in
                            }), style = typography.bodyStrong)
                        }
                    }
                }
                onExploreMockDemo?.let { explore ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                password = ""
                                passwordVisible = false
                                focus.clearFocus()
                                explore()
                            },
                            enabled = enabled,
                            shape = shapes.button,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text(stringResource(R.string.auth_explore_mock_demo), style = typography.bodyStrong)
                        }
                        Text(stringResource(R.string.auth_demo_note), style = typography.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.auth_session_note), style = typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun authErrorResource(failure: AuthFailure): Int = when (failure) {
    AuthFailure.NotConfigured -> R.string.auth_not_configured
    AuthFailure.InvalidInput -> R.string.auth_invalid_input
    AuthFailure.InvalidCredentials -> R.string.auth_invalid_credentials
    AuthFailure.RateLimited -> R.string.auth_rate_limited
    AuthFailure.Timeout -> R.string.auth_timeout
    AuthFailure.SecureConnection -> R.string.auth_secure_connection
    AuthFailure.Unavailable -> R.string.auth_unavailable
    AuthFailure.InvalidResponse -> R.string.auth_invalid_response
    AuthFailure.AccessDenied -> R.string.auth_access_denied
    AuthFailure.SessionExpired -> R.string.auth_session_expired
}
