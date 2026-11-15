package com.elect3.mobdev.personalfinance.ui.auth

import android.util.Patterns
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Public state contains no credentials, tokens, or user profile. */
data class LoginUiState(
    val isLoading: Boolean = false,
    val isSignedIn: Boolean = false,
    val error: AuthFailure? = null,
    val revocationUnconfirmed: Boolean = false,
    val isExploringDemo: Boolean = false,
)

@MainThread
class LoginViewModel(private val repository: AuthRepository = InMemoryAuthRepository()) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var expiryWatcher: Job? = null
    private var generation = 0L

    fun login(email: String, password: String) {
        if (request?.isActive == true || mutableState.value.isSignedIn || mutableState.value.isExploringDemo) return
        val trimmedEmail = email.trim()
        if (trimmedEmail.length !in 1..256 || !Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches() ||
            password.isBlank() || password.length > 128) {
            mutableState.value = LoginUiState(error = AuthFailure.InvalidInput)
            return
        }
        val attempt = ++generation
        mutableState.value = LoginUiState(isLoading = true)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.login(trimmedEmail, password)
                coroutineContext.ensureActive()
                if (attempt != generation) return@launch
                when (result) {
                    AuthResult.Success -> {
                        mutableState.value = LoginUiState(isSignedIn = true)
                        watchExpiry()
                    }
                    is AuthResult.Failure -> mutableState.value = LoginUiState(error = result.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt == generation) mutableState.value = LoginUiState(error = AuthFailure.Unavailable)
            } finally {
                if (attempt == generation) {
                    request = null
                    if (mutableState.value.isLoading) mutableState.value = LoginUiState()
                }
            }
        }
        request = job
        job.start()
    }

    fun logout() {
        val attempt = ++generation
        request?.cancel()
        expiryWatcher?.cancel()
        mutableState.value = LoginUiState(isLoading = true)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.logout()
                coroutineContext.ensureActive()
                if (attempt == generation) mutableState.value = LoginUiState(
                    revocationUnconfirmed = result is AuthResult.Failure,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt == generation) mutableState.value = LoginUiState(revocationUnconfirmed = true)
            } finally {
                if (attempt == generation) {
                    request = null
                    if (mutableState.value.isLoading) mutableState.value = LoginUiState(revocationUnconfirmed = true)
                }
            }
        }
        request = job
        job.start()
    }

    /** Demo access is local navigation only and never creates or retains an authenticated session. */
    fun exploreMockDemo() {
        ++generation
        request?.cancel()
        request = null
        expiryWatcher?.cancel()
        repository.clearSession()
        mutableState.value = LoginUiState(isExploringDemo = true)
    }

    fun returnToLogin() {
        if (mutableState.value.isExploringDemo) mutableState.value = LoginUiState()
    }

    /** Future protected API integration calls this on 401; no automatic credential retry. */
    fun sessionExpired() {
        ++generation
        request?.cancel()
        request = null
        expiryWatcher?.cancel()
        repository.clearSession()
        mutableState.value = LoginUiState(error = AuthFailure.SessionExpired)
    }

    /** Called when the isolated login route leaves composition; does not revoke a successful session. */
    fun cancelLogin() {
        if (!mutableState.value.isLoading || mutableState.value.isSignedIn) return
        ++generation
        request?.cancel()
        request = null
        repository.clearSession()
        mutableState.value = LoginUiState()
    }

    private fun watchExpiry() {
        expiryWatcher?.cancel()
        expiryWatcher = viewModelScope.launch {
            while (repository.hasActiveSession()) delay(1000)
            sessionExpired()
        }
    }

    override fun onCleared() {
        repository.clearSession()
        super.onCleared()
    }

    class Factory(private val repository: AuthRepository = InMemoryAuthRepository()) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LoginViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(repository) as T
        }
    }
}
