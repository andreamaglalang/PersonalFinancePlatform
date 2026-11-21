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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.elect3.mobdev.personalfinance.ui.components.OwliCrest
import com.elect3.mobdev.personalfinance.ui.components.OwliStarfield
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
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
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
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
    Surface(modifier.fillMaxSize(), color = scheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Midnight header: stars, moon, owl crest, spaced wordmark, tagline ----
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(owli.panelStart, owli.panelEnd))),
                contentAlignment = Alignment.TopCenter,
            ) {
                OwliStarfield(Modifier.matchParentSize(), seed = 53, count = 26)
                Column(
                    Modifier.statusBarsPadding().padding(top = 40.dp, bottom = 58.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OwliCrest()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.app_name), style = typography.crestWordmark, color = owli.onPanel,
                        modifier = Modifier.padding(start = 9.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.app_tagline), style = typography.caption, color = owli.onPanelMuted,
                        textAlign = TextAlign.Center)
                }
            }
            // ---- Sign-in sheet overlapping the header ----
            Surface(
                Modifier.offset(y = (-30).dp).widthIn(max = 520.dp).fillMaxWidth(),
                shape = shapes.sheetTop,
                color = scheme.surface,
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 18.dp)) {
                    Text(stringResource(R.string.auth_welcome), style = typography.screenTitle, color = scheme.onSurface,
                        modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.auth_intro), style = typography.body, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                        label = { Text(stringResource(R.string.auth_email)) }, textStyle = typography.bodyStrong,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_owli_mail), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = shapes.field, colors = owliFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                        label = { Text(stringResource(R.string.auth_password)) }, textStyle = typography.bodyStrong,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_owli_lock), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = shapes.field, colors = owliFieldColors(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val visibilityLabel = stringResource(if (passwordVisible)
                                R.string.auth_hide_password else R.string.auth_show_password)
                            TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = enabled,
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .semantics { contentDescription = visibilityLabel }) {
                                Text(stringResource(if (passwordVisible) R.string.auth_hide else R.string.auth_show),
                                    style = typography.label, color = owli.accent)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    state.error?.let {
                        Spacer(Modifier.height(14.dp))
                        LoginNotice(stringResource(authErrorResource(it)), isError = true)
                    }
                    if (state.revocationUnconfirmed) {
                        Spacer(Modifier.height(14.dp))
                        LoginNotice(stringResource(R.string.auth_logout_unconfirmed), isError = false)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { submit() }, enabled = enabled && email.isNotBlank() && password.isNotBlank(),
                        shape = shapes.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = LocalContentColor.current)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(stringResource(when {
                            state.isLoggingOut -> R.string.auth_signing_out
                            state.isLoading -> R.string.auth_signing_in
                            state.isSignedIn -> R.string.auth_signed_in
                            else -> R.string.auth_sign_in
                        }), style = typography.button)
                    }
                    onExploreMockDemo?.let { explore ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(Modifier.weight(1f), color = owli.hairline)
                            Text(stringResource(R.string.auth_or), style = typography.caption, color = scheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.weight(1f), color = owli.hairline)
                        }
                        OutlinedButton(
                            onClick = {
                                password = ""
                                passwordVisible = false
                                focus.clearFocus()
                                explore()
                            },
                            enabled = enabled,
                            shape = shapes.button,
                            border = BorderStroke(1.dp, owli.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_owli_mark), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.auth_explore_mock_demo), style = typography.bodyStrong)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.auth_demo_note), style = typography.caption,
                            color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = owli.hairline)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_owli_lock), contentDescription = null,
                            tint = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(15.dp))
                        Text(stringResource(R.string.auth_session_note), style = typography.caption,
                            color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Error (container colors) or neutral notice, announced politely. */
@Composable
private fun LoginNotice(message: String, isError: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val owli = LocalOwliColors.current
    Row(
        Modifier.fillMaxWidth().clip(LocalOwliShapes.current.alert)
            .background(if (isError) scheme.errorContainer else owli.accentSoft)
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_owli_alert), contentDescription = null,
            tint = if (isError) scheme.onErrorContainer else owli.tileContent, modifier = Modifier.size(18.dp))
        Text(message, style = LocalOwliTypography.current.body,
            color = if (isError) scheme.onErrorContainer else scheme.onSurface)
    }
}

/** Owlight filled field: lavender container, hairline border, ink focus. */
@Composable
internal fun owliFieldColors(): TextFieldColors {
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surface,
        unfocusedContainerColor = owli.field,
        disabledContainerColor = owli.field,
        errorContainerColor = scheme.surface,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = owli.hairline,
        disabledBorderColor = owli.hairline,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        focusedLeadingIconColor = scheme.onSurfaceVariant,
        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
        cursorColor = scheme.primary,
    )
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
