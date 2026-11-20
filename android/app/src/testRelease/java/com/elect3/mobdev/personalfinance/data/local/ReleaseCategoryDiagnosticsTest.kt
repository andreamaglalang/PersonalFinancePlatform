package com.elect3.mobdev.personalfinance.data.local

import org.junit.Assert.*
import org.junit.Test

/** Uses the actual release variant object. Any Android Log call would fail this plain JVM test. */
class ReleaseCategoryDiagnosticsTest {
    @Test fun releaseCategoryDiagnosticsAreANoOpForEveryEvent() {
        for (event in CategoryDiagnosticEvent.entries)
            CategoryDiagnostics.record(CategoryDiagnosticRecord(event, 1, 1, null, CategoryRequestStage.REMOTE,
                null, true, CategoryFallbackOutcome.SERVED, 3))
        assertTrue(CategoryDiagnostics.toString().isNotEmpty())
    }
}
