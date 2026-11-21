package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import kotlinx.coroutines.CancellationException

/** The HTTP client supplies success only for its expected 204 response. No token is accepted here. */
internal suspend fun <T> observeLogoutRequest(
    diagnostics: AuthDiagnosticSink,
    instanceId: Int,
    request: suspend () -> ApiResult<T>,
): ApiResult<T> {
    fun record(event: AuthDiagnosticEvent) = diagnostics.record(event, instanceId, null)
    record(AuthDiagnosticEvent.LOGOUT_REQUEST_INITIATED)
    return try {
        request().also {
            record(if (it is ApiResult.Success) AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED
                else AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED)
        }
    } catch (cancelled: CancellationException) {
        record(AuthDiagnosticEvent.LOGOUT_REQUEST_INTERRUPTED)
        throw cancelled
    } catch (failure: Exception) {
        record(AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED)
        throw failure
    } finally {
        // The endpoint is idempotent; acknowledgement does not prove a database row changed.
        record(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED)
    }
}
