package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class ActivityLifecycleDiagnosticsTest {
    private fun snapshot(finishing: Boolean = false, changing: Boolean = false, saved: Boolean = false) =
        ActivityLifecycleSnapshot(finishing, changing, 42, true, saved, 0x10200000, 8)

    @Test fun lifecycleSequenceClassifiesEveryCallbackWithoutMutatingItsSnapshot() {
        val state = snapshot()
        val phases = listOf(ActivityLifecyclePhase.CREATED, ActivityLifecyclePhase.VIEW_MODEL_ATTACHED,
            ActivityLifecyclePhase.STARTED, ActivityLifecyclePhase.RESUMED, ActivityLifecyclePhase.PAUSED,
            ActivityLifecyclePhase.STOPPED, ActivityLifecyclePhase.DESTROYED, ActivityLifecyclePhase.NEW_INTENT)
        assertEquals(listOf(AuthDiagnosticEvent.ACTIVITY_CREATED, AuthDiagnosticEvent.ACTIVITY_VIEW_MODEL_ATTACHED,
            AuthDiagnosticEvent.ACTIVITY_STARTED, AuthDiagnosticEvent.ACTIVITY_RESUMED, AuthDiagnosticEvent.ACTIVITY_PAUSED,
            AuthDiagnosticEvent.ACTIVITY_STOPPED, AuthDiagnosticEvent.ACTIVITY_DESTROYED, AuthDiagnosticEvent.ACTIVITY_NEW_INTENT),
            phases.map { ActivityLifecycleDiagnosticRecord(it, 5, state).event })
        assertEquals(snapshot(), state)
    }

    @Test fun finishingAndConfigurationChangeAreIndependentAndNeitherInventsAFinishReason() {
        for (finishing in listOf(false, true)) {
            for (changing in listOf(false, true)) {
                val record = ActivityLifecycleDiagnosticRecord(ActivityLifecyclePhase.DESTROYED, 5,
                    snapshot(finishing, changing))
                assertEquals(if (changing) AuthDiagnosticEvent.ACTIVITY_DESTROYED_FOR_CONFIGURATION
                    else AuthDiagnosticEvent.ACTIVITY_DESTROYED, record.event)
                val line = formatActivityLifecycleDiagnostic(record)
                assertTrue(line.contains("finishing=$finishing"))
                assertTrue(line.contains("changingConfig=$changing"))
                // A non-configuration destruction does not itself imply isFinishing=true.
                assertEquals(finishing, record.snapshot.isFinishing)
            }
        }
    }

    @Test fun savedStatePresenceIsExplicitAndDoesNotImplyRetainedViewModel() {
        val restored = ActivityLifecycleDiagnosticRecord(ActivityLifecyclePhase.CREATED, 5, snapshot(saved = true))
        val fresh = ActivityLifecycleDiagnosticRecord(ActivityLifecyclePhase.CREATED, 5, snapshot())
        assertEquals(AuthDiagnosticEvent.ACTIVITY_RESTORED, restored.event)
        assertEquals(AuthDiagnosticEvent.ACTIVITY_CREATED, fresh.event)
        assertTrue(formatActivityLifecycleDiagnostic(restored).contains("savedState=true"))
        assertEquals(8, restored.snapshot.viewModelInstanceId)
    }

    @Test fun formattingHasOnlyTheApprovedFixedKeysAndNumericFlags() {
        val record = ActivityLifecycleDiagnosticRecord(ActivityLifecyclePhase.DESTROYED, 5,
            snapshot(finishing = true))
        assertEquals("event=ACTIVITY_DESTROYED instance=5 related=8 finishing=true changingConfig=false " +
            "task=42 taskRoot=true savedState=false flags=0x10200000", formatActivityLifecycleDiagnostic(record))
        assertEquals("event=BACK_ACTIVITY_CALLBACK instance=5 related=0",
            formatAuthDiagnosticEvent(AuthDiagnosticEvent.BACK_ACTIVITY_CALLBACK, 5, null))
    }

    @Test fun extremeNumericFieldsRemainBoundedAndContainNoIntentOrStringPayloadSlots() {
        val record = ActivityLifecycleDiagnosticRecord(ActivityLifecyclePhase.DESTROYED, Int.MAX_VALUE,
            ActivityLifecycleSnapshot(true, true, Int.MIN_VALUE, false, true, -1, Int.MAX_VALUE))
        val line = formatActivityLifecycleDiagnostic(record)
        assertTrue(line.length <= 256)
        assertTrue(line.endsWith("flags=0xffffffff"))
        for (type in listOf(ActivityLifecycleSnapshot::class.java, ActivityLifecycleDiagnosticRecord::class.java)) {
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertFalse(fields.any { it.type == String::class.java || it.type.name.startsWith("android.") })
        }
    }

    @Test fun backObservationRecordsItsSourceAndDelegatesExactlyOnceWithTheSameResult() {
        val diagnostics = RecordingAuthDiagnostics()
        for (source in BackDiagnosticSource.entries) {
            var calls = 0
            val result = observeBackDispatch(diagnostics, 5, source) { calls++; false }
            assertFalse(result) // The event was not consumed by diagnostics.
            assertEquals(1, calls)
            assertEquals(source.event, diagnostics.events.last())
        }
    }

    @Test fun backObservationPreservesExceptionsWithoutRecordingTheirText() {
        val diagnostics = RecordingAuthDiagnostics()
        val original = IllegalStateException("synthetic-private-detail")
        var calls = 0
        try {
            observeBackDispatch(diagnostics, 5, BackDiagnosticSource.ACTIVITY_CALLBACK) { calls++; throw original }
            fail("Expected original failure")
        } catch (actual: IllegalStateException) {
            assertSame(original, actual)
        }
        assertEquals(1, calls)
        assertFalse(diagnostics.entries.toString().contains("synthetic-private-detail"))
    }

    @Test fun disabledSinkDoesNotObserveBackButStillDelegates() {
        var recorded = false
        val disabled = AuthDiagnosticSink { _, _, _ -> recorded = true }
        var delegated = false
        observeBackDispatch(disabled, 5, BackDiagnosticSource.KEY_UP) { delegated = true }
        assertFalse(recorded)
        assertTrue(delegated)
    }
}
