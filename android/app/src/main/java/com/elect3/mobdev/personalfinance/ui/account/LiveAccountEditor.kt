package com.elect3.mobdev.personalfinance.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.auth.PasswordChecklist
import com.elect3.mobdev.personalfinance.ui.auth.passwordRequirementsMessage
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.launch

/** Passwords exist only in this secure dialog's memory, never saved across recreation. */
@Composable
internal fun LiveAccountEditor(live: LiveAccountPlanRepository, password: Boolean, onClose: () -> Unit, onSessionEnded: () -> Unit, onPasswordChanged: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(!password) }
    var loaded by remember { mutableStateOf(password) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // Field messages for the password form (index 0 current, 1 new, 2 confirm); cleared when that field changes.
    var fieldErrors by remember { mutableStateOf(mapOf<Int, String>()) }
    val resources = LocalContext.current.resources
    var finished by remember { mutableStateOf(false) }
    suspend fun load() {
        busy = true
        when (val result = live.get("account/profile")) {
            is FinanceResult.Success -> {
                name = result.value.get("fullName").asString; email = result.value.get("email").asString
                version = result.value.get("version").asString; loaded = true
            }
            is FinanceResult.Failure -> message = liveFailure(result.reason, account = true, message = result.message)
        }
        busy = false
    }
    LaunchedEffect(live, password) { if (!password) load() }
    Dialog(onDismissRequest = { if (!busy) { onClose(); if (password && finished) onSessionEnded() } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (password) "Change password" else "Edit profile", style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!finished && password) {
                    listOf("Current password", "New password", "Confirm password").forEachIndexed { index, label ->
                        val fieldError = fieldErrors[index]
                        OutlinedTextField(value = when(index) { 0 -> current; 1 -> fresh; else -> confirm },
                            onValueChange = { if (it.length <= 128) { when(index) { 0 -> current = it; 1 -> fresh = it; else -> confirm = it }; fieldErrors = fieldErrors - index } },
                            enabled = !busy, label = { Text(label) }, singleLine = true, isError = fieldError != null,
                            supportingText = fieldError?.let { { Text(it) } },
                            modifier = if (fieldError != null) Modifier.semantics { error(fieldError) } else Modifier,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        if (index == 1) PasswordChecklist(fresh)
                    }
                } else if (!finished && loaded) {
                    Text(email)
                    OutlinedTextField(name, { if (it.length <= 100) name = it }, enabled = !busy, label = { Text("Display name") }, singleLine = true)
                }
                message?.let { Text(it, color = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { current = ""; fresh = ""; confirm = ""; onClose(); if (password && finished) onSessionEnded() }) { Text("Close") }
                    if (!finished && !password) TextButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text("Reload") }
                    if (!finished && loaded) Button(enabled = !busy, onClick = {
                        // Every unmet rule at once, beside its field; the server checks the same policy again.
                        if (password) fieldErrors = buildMap {
                            if (current.isEmpty()) put(0, resources.getString(R.string.password_error_current_empty))
                            val unmet = passwordRequirementsMessage(resources, fresh)
                            when {
                                fresh.isEmpty() -> put(1, resources.getString(R.string.password_error_new_empty))
                                unmet != null -> put(1, unmet)
                                fresh == current -> put(1, resources.getString(R.string.password_error_same))
                            }
                            if (confirm != fresh) put(2, resources.getString(R.string.register_error_password_mismatch))
                        }
                        val valid = if (password) fieldErrors.isEmpty() else name.trim().isNotEmpty() && name.none(Char::isISOControl)
                        message = null
                        if (!valid) message = if (password) null else "Enter your full name."
                        else scope.launch {
                            busy = true; message = null
                            val result = if (password) live.changePassword(current, fresh, confirm) else live.updateProfile(name, version)
                            when(result) {
                                is FinanceResult.Success -> { finished = true; message = if (password) "Password changed. Sign in again." else "Profile saved." }
                                is FinanceResult.Failure -> {
                                    message = liveFailure(result.reason, true, account = true, message = result.message)
                                    if (password && result.reason in uncertainFailures) { finished = true; message = "Password change could not be confirmed. Sign in again to check; do not resubmit automatically." }
                                }
                            }
                            if (password) { current = ""; fresh = ""; confirm = ""; if (finished) onPasswordChanged(result is FinanceResult.Success) }
                            busy = false
                        }
                    }) { Text(if (password) "Change password" else "Save") }
                }
            }
        }
    }
}
