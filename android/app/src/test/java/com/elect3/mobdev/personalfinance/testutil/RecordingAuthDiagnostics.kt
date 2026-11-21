package com.elect3.mobdev.personalfinance.testutil

import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecycleDiagnosticRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RecordingAuthDiagnostics : AuthDiagnosticSink {
    override val enabled = true
    data class Entry(val event: AuthDiagnosticEvent, val instanceId: Int, val relatedId: Int?)
    val entries = mutableListOf<Entry>()
    val lifecycleRecords = mutableListOf<ActivityLifecycleDiagnosticRecord>()
    val events get() = entries.map { it.event }
    override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) {
        entries += Entry(event, instanceId, relatedInstanceId)
    }
    override fun recordLifecycle(record: ActivityLifecycleDiagnosticRecord) {
        lifecycleRecords += record
    }
}

class MutableAuthClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    override fun instant(): Instant = now
}
