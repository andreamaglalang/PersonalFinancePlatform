package com.elect3.mobdev.personalfinance.data.auth

import android.util.Log

/** Debug variant only. Only enum names and process-local numeric IDs reach Logcat. */
object AuthDiagnostics : AuthDiagnosticSink {
    override val enabled = true
    override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) {
        Log.d("OwliAuth", formatAuthDiagnosticEvent(event, instanceId, relatedInstanceId))
    }

    override fun recordLifecycle(record: ActivityLifecycleDiagnosticRecord) {
        Log.d("OwliAuth", formatActivityLifecycleDiagnostic(record))
    }
}
