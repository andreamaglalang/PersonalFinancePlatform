package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises diagnostic outcomes with synthetic transport results, never an HTTP connection. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutRequestObservationTest {
    @Test fun pendingRequestIsNotAcknowledgedAndSimulated204DoesNotProveRevocation() = runTest {
        val diagnostics = RecordingAuthDiagnostics()
        val reply = CompletableDeferred<ApiResult<Unit>>()
        val request = async { observeLogoutRequest(diagnostics, 42) { reply.await() } }
        runCurrent()
        assertEquals(listOf(AuthDiagnosticEvent.LOGOUT_REQUEST_INITIATED), diagnostics.events)
        reply.complete(ApiResult.Success(Unit))
        assertTrue(request.await() is ApiResult.Success)
        assertEquals(listOf(AuthDiagnosticEvent.LOGOUT_REQUEST_INITIATED,
            AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED,
            AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED), diagnostics.events)
        assertTrue(diagnostics.entries.all { it.instanceId == 42 && it.relatedId == null })
    }

    @Test fun rejectedRequestCannotBeReportedAsAcknowledged() = runTest {
        val diagnostics = RecordingAuthDiagnostics()
        val result = observeLogoutRequest<Unit>(diagnostics, 42) { ApiResult.Failure(AuthFailure.Unavailable) }
        assertTrue(result is ApiResult.Failure)
        assertTrue(AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED in diagnostics.events)
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
        assertTrue(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED in diagnostics.events)
    }

    @Test fun cancellationIsDistinguishedFromRejectionAndNeverAcknowledged() = runTest {
        val diagnostics = RecordingAuthDiagnostics()
        val reply = CompletableDeferred<ApiResult<Unit>>()
        val request = async { observeLogoutRequest(diagnostics, 42) { reply.await() } }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(AuthDiagnosticEvent.LOGOUT_REQUEST_INTERRUPTED in diagnostics.events)
        assertFalse(AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED in diagnostics.events)
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
        assertTrue(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED in diagnostics.events)
    }

    @Test fun exceptionDetailsCannotEnterTheDiagnosticRecord() = runTest {
        val diagnostics = RecordingAuthDiagnostics()
        val failure = IllegalStateException("synthetic-private-detail")
        try {
            observeLogoutRequest<Unit>(diagnostics, 42) { throw failure }
            fail("Expected the original exception")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertTrue(AuthDiagnosticEvent.LOGOUT_REQUEST_FAILED in diagnostics.events)
        assertFalse(diagnostics.entries.toString().contains("synthetic-private-detail"))
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
    }
}
