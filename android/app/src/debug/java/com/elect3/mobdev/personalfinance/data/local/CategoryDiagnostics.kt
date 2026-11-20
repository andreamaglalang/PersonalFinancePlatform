package com.elect3.mobdev.personalfinance.data.local

import android.util.Log

/** Debug variant only. Only closed enum names, booleans and counters reach Logcat (tag OwliCategory). */
object CategoryDiagnostics : CategoryDiagnosticSink {
    override fun record(record: CategoryDiagnosticRecord) {
        Log.d("OwliCategory", formatCategoryDiagnostic(record))
    }
}
