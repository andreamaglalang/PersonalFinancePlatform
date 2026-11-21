package com.elect3.mobdev.personalfinance.data.auth

import org.junit.Assert.*
import org.junit.Test

/** Uses the actual release variant object. Any Android Log call would fail this plain JVM test. */
class ReleaseAuthDiagnosticsTest {
    @Test fun releaseDiagnosticsAreDisabledForAllAuthAndLifecycleEvents() {
        assertFalse(AuthDiagnostics.enabled)
        for (event in AuthDiagnosticEvent.entries) AuthDiagnostics.record(event, 5, 8)
        for (phase in ActivityLifecyclePhase.entries) AuthDiagnostics.recordLifecycle(
            ActivityLifecycleDiagnosticRecord(phase, 5,
                ActivityLifecycleSnapshot(false, false, 42, true, false, 0x10200000, 8)))
    }

    @Test fun releaseBackObservationDoesNotChangeTheOriginalHandler() {
        var calls = 0
        val result = observeBackDispatch(AuthDiagnostics, 5, BackDiagnosticSource.ACTIVITY_CALLBACK) {
            calls++
            "test-only-result"
        }
        assertEquals(1, calls)
        assertEquals("test-only-result", result)
    }
}
