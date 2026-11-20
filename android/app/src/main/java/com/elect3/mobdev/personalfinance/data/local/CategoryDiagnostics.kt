package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import java.util.concurrent.atomic.AtomicInteger

/**
 * Closed vocabulary for the signed-in category selection path (remote request and Room fallback).
 * Records hold only enums, booleans and small counters: no tokens, account or session identifiers, URLs,
 * category names, amounts or exception text can be expressed. Debug builds log them; release builds do nothing.
 */
enum class CategoryDiagnosticEvent { CATEGORY_REMOTE_REQUEST_STARTED, CATEGORY_REMOTE_REQUEST_FAILED, CATEGORY_ROOM_FALLBACK_COMPLETED }

/** Where a failure was observed: before the network call (session checks), from the server, or in response validation. */
enum class CategoryRequestStage { BEFORE_REQUEST, REMOTE, RESPONSE_VALIDATION }

/** How the Room fallback ended. SERVED means cached rows were returned to the form. */
enum class CategoryFallbackOutcome { SERVED, EMPTY_CACHE, CACHE_ERROR, SESSION_ENDED }

data class CategoryDiagnosticRecord(
    val event: CategoryDiagnosticEvent,
    /** Process-local sequence number linking the events of one request; unrelated to accounts or sessions. */
    val request: Int,
    val page: Int,
    val type: TransactionType?,
    val stage: CategoryRequestStage? = null,
    val reason: FinanceFailure? = null,
    val connectivity: Boolean? = null,
    val outcome: CategoryFallbackOutcome? = null,
    /** Number of cached rows returned on this page (SERVED only). A count, never a value or a name. */
    val rows: Int? = null,
)

fun interface CategoryDiagnosticSink {
    fun record(record: CategoryDiagnosticRecord)
}

object NoCategoryDiagnostics : CategoryDiagnosticSink {
    override fun record(record: CategoryDiagnosticRecord) = Unit
}

object CategoryDiagnosticIds {
    private val sequence = AtomicInteger()
    fun next(): Int = sequence.incrementAndGet()
}

/** Formats only the closed fields above. */
fun formatCategoryDiagnostic(r: CategoryDiagnosticRecord): String = buildString {
    append("event=").append(r.event.name)
    append(" request=").append(r.request)
    append(" page=").append(r.page)
    append(" type=").append(r.type?.name ?: "ALL")
    r.stage?.let { append(" stage=").append(it.name) }
    r.reason?.let { append(" reason=").append(it.name) }
    r.connectivity?.let { append(" connectivity=").append(it) }
    r.outcome?.let { append(" outcome=").append(it.name) }
    r.rows?.let { append(" rows=").append(it) }
}
