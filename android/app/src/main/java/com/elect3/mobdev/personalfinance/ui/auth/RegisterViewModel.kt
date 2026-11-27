package com.elect3.mobdev.personalfinance.ui.auth

import android.util.Patterns
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.domain.auth.PasswordPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Client checks that mirror the API's RegisterRequest and password policy; the server stays authoritative. */
enum class RegisterInputError { FullName, Email, Password, PasswordMismatch }

/** Every failed check, in field order, so all of them can be shown at once. */
fun registerInputErrors(
    fullName: String, email: String, password: String, confirmPassword: String,
    isValidEmail: (String) -> Boolean = { Patterns.EMAIL_ADDRESS.matcher(it).matches() },
): Set<RegisterInputError> = buildSet {
    val trimmedEmail = email.trim()
    if (fullName.trim().length !in 1..100) add(RegisterInputError.FullName)
    if (trimmedEmail.length !in 1..256 || !isValidEmail(trimmedEmail)) add(RegisterInputError.Email)
    if (PasswordPolicy.unmet(password).isNotEmpty()) add(RegisterInputError.Password)
    if (password != confirmPassword) add(RegisterInputError.PasswordMismatch)
}

/** Public state holds no password. [registeredEmail] is a one-shot "go back to Login" event. */
data class RegisterUiState(
    val isLoading: Boolean = false,
    val inputErrors: Set<RegisterInputError> = emptySet(),
    val failure: AuthFailure? = null,
    val registeredEmail: String? = null,
)

@MainThread
class RegisterViewModel(
    private val repository: AuthRepository,
    private val isValidEmail: (String) -> Boolean = { Patterns.EMAIL_ADDRESS.matcher(it).matches() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RegisterUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun register(fullName: String, email: String, password: String, confirmPassword: String) {
        if (request?.isActive == true) return
        val name = fullName.trim()
        val trimmedEmail = email.trim()
        val inputErrors = registerInputErrors(fullName, email, password, confirmPassword, isValidEmail)
        if (inputErrors.isNotEmpty()) {
            mutableState.value = RegisterUiState(inputErrors = inputErrors)
            return
        }
        mutableState.value = RegisterUiState(isLoading = true)
        request = viewModelScope.launch {
            mutableState.value = try {
                when (val result = repository.register(name, trimmedEmail, password, confirmPassword)) {
                    AuthResult.Success -> RegisterUiState(registeredEmail = trimmedEmail)
                    is AuthResult.Failure -> RegisterUiState(failure = result.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RegisterUiState(failure = AuthFailure.Unavailable)
            }
        }
    }

    /** Called when the screen leaves composition or after navigating back to Login. */
    fun reset() {
        request?.cancel()
        request = null
        mutableState.value = RegisterUiState()
    }

    class Factory(private val repository: AuthRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == RegisterViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return RegisterViewModel(repository) as T
        }
    }
}
