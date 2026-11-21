package com.elect3.mobdev.personalfinance.data.auth

enum class ActivityLifecyclePhase {
    CREATED, VIEW_MODEL_ATTACHED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED, NEW_INTENT,
}

/** Bounded primitive-only snapshot. Flags are numeric; intent extras/data/actions are never read. */
data class ActivityLifecycleSnapshot(
    val isFinishing: Boolean,
    val isChangingConfigurations: Boolean,
    val taskId: Int,
    val isTaskRoot: Boolean,
    val savedInstanceStatePresent: Boolean,
    val intentFlags: Int,
    val viewModelInstanceId: Int?,
)

data class ActivityLifecycleDiagnosticRecord(
    val phase: ActivityLifecyclePhase,
    val activityInstanceId: Int,
    val snapshot: ActivityLifecycleSnapshot,
) {
    val event: AuthDiagnosticEvent get() = when (phase) {
        ActivityLifecyclePhase.CREATED -> if (snapshot.savedInstanceStatePresent)
            AuthDiagnosticEvent.ACTIVITY_RESTORED else AuthDiagnosticEvent.ACTIVITY_CREATED
        ActivityLifecyclePhase.VIEW_MODEL_ATTACHED -> AuthDiagnosticEvent.ACTIVITY_VIEW_MODEL_ATTACHED
        ActivityLifecyclePhase.STARTED -> AuthDiagnosticEvent.ACTIVITY_STARTED
        ActivityLifecyclePhase.RESUMED -> AuthDiagnosticEvent.ACTIVITY_RESUMED
        ActivityLifecyclePhase.PAUSED -> AuthDiagnosticEvent.ACTIVITY_PAUSED
        ActivityLifecyclePhase.STOPPED -> AuthDiagnosticEvent.ACTIVITY_STOPPED
        ActivityLifecyclePhase.DESTROYED -> if (snapshot.isChangingConfigurations)
            AuthDiagnosticEvent.ACTIVITY_DESTROYED_FOR_CONFIGURATION else AuthDiagnosticEvent.ACTIVITY_DESTROYED
        ActivityLifecyclePhase.NEW_INTENT -> AuthDiagnosticEvent.ACTIVITY_NEW_INTENT
    }
}

fun formatActivityLifecycleDiagnostic(record: ActivityLifecycleDiagnosticRecord): String = with(record.snapshot) {
    formatAuthDiagnosticEvent(record.event, record.activityInstanceId, viewModelInstanceId) +
        " finishing=$isFinishing changingConfig=$isChangingConfigurations task=$taskId" +
        " taskRoot=$isTaskRoot savedState=$savedInstanceStatePresent" +
        " flags=0x${Integer.toUnsignedString(intentFlags, 16).padStart(8, '0')}"
}

enum class BackDiagnosticSource(val event: AuthDiagnosticEvent) {
    KEY_UP(AuthDiagnosticEvent.BACK_KEY_UP),
    ACTIVITY_CALLBACK(AuthDiagnosticEvent.BACK_ACTIVITY_CALLBACK),
    TRANSACTIONS(AuthDiagnosticEvent.BACK_TRANSACTIONS_TO_DASHBOARD),
    DEMO(AuthDiagnosticEvent.BACK_DEMO_TO_LOGIN),
}

/** Observes but never consumes/replaces Back. The original handler executes exactly once. */
inline fun <T> observeBackDispatch(
    diagnostics: AuthDiagnosticSink,
    instanceId: Int,
    source: BackDiagnosticSource,
    proceed: () -> T,
): T {
    if (diagnostics.enabled) diagnostics.record(source.event, instanceId, null)
    return proceed()
}
