package com.elect3.mobdev.personalfinance.data.auth

import java.util.concurrent.atomic.AtomicInteger

/** Closed vocabulary: callers cannot submit credentials, account data, URLs or exception text. */
enum class AuthDiagnosticEvent {
    ACTIVITY_CREATED, ACTIVITY_RESTORED, ACTIVITY_VIEW_MODEL_ATTACHED,
    ACTIVITY_STARTED, ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED, ACTIVITY_NEW_INTENT,
    ACTIVITY_DESTROYED, ACTIVITY_DESTROYED_FOR_CONFIGURATION,
    BACK_KEY_UP, BACK_ACTIVITY_CALLBACK, BACK_TRANSACTIONS_TO_DASHBOARD, BACK_DEMO_TO_LOGIN,
    NAV_DASHBOARD_SELECTED, NAV_TRANSACTIONS_SELECTED,
    VIEW_MODEL_CREATED, VIEW_MODEL_CLEARED, LOGIN_SUCCEEDED,
    EXPLICIT_LOGOUT_INITIATED, LOCAL_SESSION_CLEARED, ACCESS_TOKEN_EXPIRED,
    LOGOUT_NO_LOCAL_SESSION, LOGOUT_REQUEST_INITIATED,
    LOGOUT_HTTP_204_ACKNOWLEDGED, LOGOUT_REQUEST_FAILED, LOGOUT_REQUEST_INTERRUPTED,
    SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED,
    SESSION_RESET, PENDING_LOGIN_CANCELLED,
    REFRESH_SUCCEEDED, REFRESH_REJECTED, REFRESH_NOT_COMPLETED, REFRESH_RESULT_DISCARDED,
    GATE_SIGNED_OUT, GATE_AUTHENTICATED, GATE_MOCK_DEMO,
}

fun interface AuthDiagnosticSink {
    val enabled: Boolean get() = false
    fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?)
    fun recordLifecycle(record: ActivityLifecycleDiagnosticRecord) = Unit
}

object NoAuthDiagnostics : AuthDiagnosticSink {
    override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) = Unit
}

/** Process-local counters are unrelated to account IDs, tokens, or server session families. */
object AuthDiagnosticIds {
    private val sequence = AtomicInteger()
    fun next(): Int = sequence.incrementAndGet()
}

/** Formatting accepts no payload strings, account IDs, intent objects or exception messages. */
fun formatAuthDiagnosticEvent(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?): String =
    "event=${event.name} instance=$instanceId related=${relatedInstanceId ?: 0}"
