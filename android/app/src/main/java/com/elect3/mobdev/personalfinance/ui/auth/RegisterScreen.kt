package com.elect3.mobdev.personalfinance.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Registration never signs in: success returns to Login with the email prefilled. */
@Composable
fun RegisterRoute(
    viewModel: RegisterViewModel,
    onRegistered: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    LaunchedEffect(state.registeredEmail) { state.registeredEmail?.let { viewModel.reset(); onRegistered(it) } }
    BackHandler(onBack = onBack)
    RegisterScreen(state, viewModel::register, onBack, modifier)
}

@Composable
fun RegisterScreen(
    state: RegisterUiState,
    onRegister: (fullName: String, email: String, password: String, confirmPassword: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Never saved to instance state. Passwords are removed from the fields on submission.
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val enabled = !state.isLoading
    fun submit() {
        if (!enabled) return
        val submitted = password to confirmPassword
        // Cleared on every submit, so a rejected password is never shown again.
        password = ""
        confirmPassword = ""
        focus.clearFocus()
        onRegister(fullName, email, submitted.first, submitted.second)
    }
    val inputMessage = state.inputError?.let { stringResource(registerInputErrorResource(it)) }

    @Composable
    fun field(
        value: String, onChange: (String) -> Unit, label: Int, error: Boolean, keyboard: KeyboardOptions,
        secret: Boolean = false, onDone: (() -> Unit)? = null,
    ) = OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().then(if (error && inputMessage != null)
            Modifier.semantics { error(inputMessage) } else Modifier),
        enabled = enabled, singleLine = true, isError = error,
        label = { Text(stringResource(label)) }, textStyle = typography.bodyStrong,
        shape = shapes.field, colors = owliFieldColors(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { onDone?.invoke() }),
    )

    Surface(modifier.fillMaxSize(), color = scheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_owli_chevron_left), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.register_back_to_sign_in), style = typography.label, color = owli.accent)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.register_title), style = typography.screenTitle, color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.register_intro), style = typography.body, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                field(fullName, { fullName = it }, R.string.register_full_name, state.inputError == RegisterInputError.FullName,
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                Spacer(Modifier.height(12.dp))
                field(email, { email = it }, R.string.auth_email, state.inputError == RegisterInputError.Email,
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                Spacer(Modifier.height(12.dp))
                field(password, { password = it }, R.string.auth_password, state.inputError == RegisterInputError.PasswordLength,
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), secret = true)
                Text(stringResource(R.string.register_password_hint), style = typography.caption,
                    color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                Spacer(Modifier.height(12.dp))
                field(confirmPassword, { confirmPassword = it }, R.string.register_confirm_password,
                    state.inputError == RegisterInputError.PasswordMismatch,
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), secret = true,
                    onDone = { submit() })
                inputMessage?.let {
                    // Announced politely; the field itself is marked with error semantics.
                    Spacer(Modifier.height(14.dp))
                    LoginNotice(it, isError = true)
                }
                state.failure?.let {
                    Spacer(Modifier.height(14.dp))
                    LoginNotice(stringResource(registerFailureResource(it)), isError = true)
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = { submit() }, enabled = enabled,
                    shape = shapes.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(if (state.isLoading) R.string.register_creating else R.string.register_submit),
                        style = typography.button)
                }
            }
        }
    }
}

private fun registerInputErrorResource(error: RegisterInputError): Int = when (error) {
    RegisterInputError.FullName -> R.string.register_error_full_name
    RegisterInputError.Email -> R.string.register_error_email
    RegisterInputError.PasswordLength -> R.string.register_error_password_length
    RegisterInputError.PasswordMismatch -> R.string.register_error_password_mismatch
}

/** Mirrors the MVC registration messages; anything uncertain is "temporarily unavailable". */
internal fun registerFailureResource(failure: AuthFailure): Int = when (failure) {
    AuthFailure.InvalidInput -> R.string.register_error_invalid
    AuthFailure.AlreadyRegistered -> R.string.register_error_duplicate
    AuthFailure.RateLimited -> R.string.auth_rate_limited
    else -> R.string.register_error_unavailable
}
