package com.elect3.mobdev.personalfinance.ui.auth

import android.util.Patterns
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticIds
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.NoAuthDiagnostics
import com.elect3.mobdev.personalfinance.data.auth.BackDiagnosticSource
import com.elect3.mobdev.personalfinance.data.auth.observeBackDispatch
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionCreationRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class EntryMode { SignedOut, Authenticated, MockDemo }

/** Public state contains no credentials, tokens, or user profile. */
data class LoginUiState(
    val isLoading: Boolean = false,
    val isSignedIn: Boolean = false,
    val error: AuthFailure? = null,
    val revocationUnconfirmed: Boolean = false,
    val isExploringDemo: Boolean = false,
    val isLoggingOut: Boolean = false,
    // UI restoration identity only; never an authentication token or persisted session.
    val authenticatedContentKey: String? = null,
) {
    init {
        require(!(isSignedIn && isExploringDemo))
        require(isSignedIn == (authenticatedContentKey != null))
    }

    val entryMode: EntryMode get() = when {
        isSignedIn -> EntryMode.Authenticated
        isExploringDemo -> EntryMode.MockDemo
        else -> EntryMode.SignedOut
    }
}

@MainThread
class LoginViewModel(
    private val repository: AuthRepository = InMemoryAuthRepository(),
    private val isValidEmail: (String) -> Boolean = { Patterns.EMAIL_ADDRESS.matcher(it).matches() },
    private val diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    val authenticatedTransactions: TransactionRepository? = null,
    val authenticatedCategories: CategoryRepository? = null,
    val authenticatedCurrency: () -> String? = { null },
    /** Authenticated save path; exposed only to authenticated content, never to Mock Demo. */
    val authenticatedCreation: TransactionCreationRepository? = null,
) : ViewModel() {
    val diagnosticId = AuthDiagnosticIds.next()
    private fun record(event: AuthDiagnosticEvent) = diagnostics.record(event, diagnosticId, null)
    init {
        diagnostics.record(AuthDiagnosticEvent.VIEW_MODEL_CREATED, diagnosticId,
            (repository as? InMemoryAuthRepository)?.diagnosticId)
    }
    private val mutableState = MutableStateFlow(LoginUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var expiryWatcher: Job? = null
    private var generation = 0L

    fun login(email: String, password: String) {
        if (request?.isActive == true || mutableState.value.isSignedIn || mutableState.value.isExploringDemo) return
        val trimmedEmail = email.trim()
        if (trimmedEmail.length !in 1..256 || !isValidEmail(trimmedEmail) ||
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
                        if (repository.hasActiveSession()) {
                            mutableState.value = LoginUiState(
                                isSignedIn = true,
                                authenticatedContentKey = UUID.randomUUID().toString(),
                            )
                            record(AuthDiagnosticEvent.LOGIN_SUCCEEDED)
                            watchExpiry()
                        } else {
                            repository.clearSession()
                            mutableState.value = LoginUiState(error = AuthFailure.SessionExpired)
                        }
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
        if (!mutableState.value.isSignedIn) return
        record(AuthDiagnosticEvent.EXPLICIT_LOGOUT_INITIATED)
        val attempt = ++generation
        request?.cancel()
        expiryWatcher?.cancel()
        mutableState.value = LoginUiState(isLoading = true, isLoggingOut = true)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.logout()
                coroutineContext.ensureActive()
                if (attempt == generation) mutableState.value = LoginUiState(
                    revocationUnconfirmed = result is AuthResult.Failure,
                )
            } catch (cancelled: CancellationException) {
                record(AuthDiagnosticEvent.LOGOUT_REQUEST_INTERRUPTED)
                record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
                throw cancelled
            } catch (_: Exception) {
                record(AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED)
                record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
                if (attempt == generation) mutableState.value = LoginUiState(revocationUnconfirmed = true)
            } finally {
                if (attempt == generation) {
                    repository.clearSession()
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
        if (mutableState.value.isSignedIn || mutableState.value.isLoading) return
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

    fun returnToLoginFromBack() {
        observeBackDispatch(diagnostics, diagnosticId, BackDiagnosticSource.DEMO) { returnToLogin() }
    }

    /** Future protected API integration calls this on 401; no automatic credential retry. */
    fun sessionExpired() {
        if (!mutableState.value.isSignedIn) return
        record(AuthDiagnosticEvent.SESSION_RESET)
        ++generation
        request?.cancel()
        request = null
        expiryWatcher?.cancel()
        repository.clearSession()
        mutableState.value = LoginUiState(error = AuthFailure.SessionExpired)
    }

    /** Called when the isolated login route leaves composition; does not revoke a successful session. */
    fun cancelLogin() {
        if (!mutableState.value.isLoading || mutableState.value.isSignedIn || mutableState.value.isLoggingOut) return
        record(AuthDiagnosticEvent.PENDING_LOGIN_CANCELLED)
        ++generation
        request?.cancel()
        request = null
        repository.clearSession()
        mutableState.value = LoginUiState()
    }

    private fun watchExpiry() {
        expiryWatcher?.cancel()
        expiryWatcher = viewModelScope.launch {
            while (true) {
                // Proactive, single-flight rotation lives in the repository; failures never extend the session.
                try {
                    repository.refreshIfDue()
                } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Refresh problems must not crash the app; expiry below still applies.
                }
                if (!repository.hasActiveSession()) break
                delay(1000)
            }
            sessionExpired()
        }
    }

    override fun onCleared() {
        record(AuthDiagnosticEvent.VIEW_MODEL_CLEARED)
        repository.clearSession()
        super.onCleared()
    }

    /** Called by the gate's effect, not during composition; never changes authentication state. */
    fun recordGateTransition(mode: EntryMode) = record(when (mode) {
        EntryMode.SignedOut -> AuthDiagnosticEvent.GATE_SIGNED_OUT
        EntryMode.Authenticated -> AuthDiagnosticEvent.GATE_AUTHENTICATED
        EntryMode.MockDemo -> AuthDiagnosticEvent.GATE_MOCK_DEMO
    })

    class Factory(
        private val repository: AuthRepository = InMemoryAuthRepository(),
        private val diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
        private val authenticatedTransactions: TransactionRepository? = null,
        private val authenticatedCategories: CategoryRepository? = null,
        private val authenticatedCurrency: () -> String? = { null },
        private val authenticatedCreation: TransactionCreationRepository? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LoginViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(repository, diagnostics = diagnostics,
                authenticatedTransactions = authenticatedTransactions, authenticatedCategories = authenticatedCategories,
                authenticatedCurrency = authenticatedCurrency, authenticatedCreation = authenticatedCreation) as T
        }
    }
}
