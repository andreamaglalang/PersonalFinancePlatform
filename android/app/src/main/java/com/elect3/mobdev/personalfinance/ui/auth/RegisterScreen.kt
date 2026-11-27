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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
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
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmVisible by rememberSaveable { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val enabled = !state.isLoading
    fun submit() {
        if (!enabled) return
        val submitted = password to confirmPassword
        // Cleared once the password is sent, so a server-rejected password is never shown again. On-device check
        // failures keep what was typed (it never left the phone) so the user can correct it.
        if (registerInputErrors(fullName, email, password, confirmPassword).isEmpty()) {
            password = ""
            confirmPassword = ""
        }
        focus.clearFocus()
        onRegister(fullName, email, submitted.first, submitted.second)
    }
    val resources = LocalContext.current.resources
    // Messages reflect the last submission; each disappears as soon as its field is edited.
    var edited by remember(state.inputErrors) { mutableStateOf(emptySet<RegisterInputError>()) }
    fun messageFor(error: RegisterInputError, password: String): String? =
        if (error !in state.inputErrors || error in edited) null else when (error) {
            RegisterInputError.FullName -> resources.getString(R.string.register_error_full_name)
            RegisterInputError.Email -> resources.getString(R.string.register_error_email)
            RegisterInputError.Password -> passwordRequirementsMessage(resources, password)
                ?: resources.getString(R.string.password_error_new_empty)
            RegisterInputError.PasswordMismatch -> resources.getString(R.string.register_error_password_mismatch)
        }

    @Composable
    fun field(
        value: String, onChange: (String) -> Unit, label: Int, message: String?, keyboard: KeyboardOptions,
        secret: Boolean = false, visible: Boolean = false, onVisibleChange: ((Boolean) -> Unit)? = null,
        onFocus: ((Boolean) -> Unit)? = null, onDone: (() -> Unit)? = null,
    ) = OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().then(if (message != null) Modifier.semantics { error(message) } else Modifier)
            .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
        enabled = enabled, singleLine = true, isError = message != null,
        supportingText = message?.let { { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
        label = { Text(stringResource(label)) }, textStyle = typography.bodyStrong,
        shape = shapes.field, colors = owliFieldColors(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        // Same Show/Hide control as sign-in, on both the password and its confirmation.
        trailingIcon = onVisibleChange?.let { toggle -> {
            val visibilityLabel = stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password)
            TextButton(onClick = { toggle(!visible) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = visibilityLabel }) {
                Text(stringResource(if (visible) R.string.auth_hide else R.string.auth_show), style = typography.label, color = owli.accent)
            }
        } },
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
                field(fullName, { fullName = it; edited = edited + RegisterInputError.FullName }, R.string.register_full_name,
                    messageFor(RegisterInputError.FullName, password),
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                Spacer(Modifier.height(12.dp))
                field(email, { email = it; edited = edited + RegisterInputError.Email }, R.string.auth_email,
                    messageFor(RegisterInputError.Email, password),
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                Spacer(Modifier.height(12.dp))
                field(password, { password = it; edited = edited + RegisterInputError.Password }, R.string.auth_password,
                    messageFor(RegisterInputError.Password, password),
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), secret = true,
                    visible = passwordVisible, onVisibleChange = { passwordVisible = it }, onFocus = { passwordFocused = it })
                PasswordChecklist(password, expanded = passwordFocused || password.isNotEmpty() ||
                    messageFor(RegisterInputError.Password, password) != null)
                Spacer(Modifier.height(12.dp))
                field(confirmPassword, { confirmPassword = it; edited = edited + RegisterInputError.PasswordMismatch },
                    R.string.register_confirm_password, messageFor(RegisterInputError.PasswordMismatch, password),
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), secret = true,
                    visible = confirmVisible, onVisibleChange = { confirmVisible = it }, onDone = { submit() })
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

/** Mirrors the MVC registration messages; anything uncertain is "temporarily unavailable". */
internal fun registerFailureResource(failure: AuthFailure): Int = when (failure) {
    AuthFailure.InvalidInput -> R.string.register_error_invalid
    AuthFailure.AlreadyRegistered -> R.string.register_error_duplicate
    AuthFailure.RateLimited -> R.string.auth_rate_limited
    else -> R.string.register_error_unavailable
}
