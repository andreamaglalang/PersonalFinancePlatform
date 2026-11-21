package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Event order and redaction of the debug-only category diagnostics; behaviour must stay identical. */
class CategoryDiagnosticsTest {
    private val secretName = "Secret Payroll Category"
    private val session = "session-token-abc123"
    private val accountId = 987654
    private fun cat(id: Int, type: TransactionType = TransactionType.Expense) = Category(id, "$secretName $id", type, null, true, false)
    private fun remote(block: suspend (CategoryQuery) -> FinanceResult<CategoryPage>) =
        object : CategoryRepository { override suspend fun list(query: CategoryQuery) = block(query) }
    private val query = CategoryQuery(type = TransactionType.Expense)
    private val offline = FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true)
    private class Sink : CategoryDiagnosticSink {
        val records = mutableListOf<CategoryDiagnosticRecord>()
        override fun record(record: CategoryDiagnosticRecord) { records += record }
        val events get() = records.map { it.event }
        val lines get() = records.map(::formatCategoryDiagnostic)
    }
    private fun repo(r: CategoryRepository, cache: LocalCategoryCache, sink: CategoryDiagnosticSink,
                     account: () -> Int? = { accountId }, current: () -> Boolean = { true }, identity: () -> String? = { session }) =
        CachedCategoryRepository(r, cache, account, current, identity, sink)

    @Test fun offlineWithWarmCacheEmitsStartedFailedFallbackInOrder() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(accountId, listOf(cat(1), cat(2)))
        val sink = Sink()
        val result = repo(remote { offline }, cache, sink).list(query)
        assertTrue(result is FinanceResult.Success)
        assertEquals(listOf(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_STARTED, CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_FAILED,
            CategoryDiagnosticEvent.CATEGORY_ROOM_FALLBACK_COMPLETED), sink.events)
        assertEquals(1, sink.records.map { it.request }.distinct().size)
        val failed = sink.records[1]
        assertEquals(CategoryRequestStage.REMOTE, failed.stage); assertEquals(FinanceFailure.Unavailable, failed.reason); assertEquals(true, failed.connectivity)
        val fallback = sink.records[2]
        assertEquals(CategoryFallbackOutcome.SERVED, fallback.outcome); assertEquals(2, fallback.rows)
    }

    @Test fun offlineWithColdCacheReportsEmptyCacheAndKeepsOriginalError() = runTest {
        val sink = Sink()
        val result = repo(remote { offline }, InMemoryCategoryCache(), sink).list(query)
        assertSame(offline, result)
        assertEquals(CategoryFallbackOutcome.EMPTY_CACHE, sink.records.last().outcome)
        assertNull(sink.records.last().rows)
    }

    @Test fun onlineSuccessEmitsOnlyStarted() = runTest {
        val sink = Sink(); val ok = FinanceResult.Success(CategoryPage(listOf(cat(1)), 1, 50, 1))
        assertSame(ok, repo(remote { ok }, InMemoryCategoryCache(), sink).list(query))
        assertEquals(listOf(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_STARTED), sink.events)
    }

    @Test fun nonConnectivityServerFailureEmitsFailedWithoutFallback() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(accountId, listOf(cat(1)))
        val sink = Sink(); val denied = FinanceResult.Failure(FinanceFailure.SessionExpired)
        assertSame(denied, repo(remote { denied }, cache, sink).list(query))
        assertEquals(listOf(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_STARTED, CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_FAILED), sink.events)
        assertEquals(false, sink.records.last().connectivity)
    }

    @Test fun missingSessionFailsBeforeRequestWithoutCallingRemote() = runTest {
        val sink = Sink(); var calls = 0
        val result = repo(remote { calls++; offline }, InMemoryCategoryCache(), sink, identity = { null }).list(query)
        assertEquals(FinanceResult.Failure(FinanceFailure.MissingSession), result)
        assertEquals(0, calls)
        assertEquals(listOf(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_FAILED), sink.events)
        assertEquals(CategoryRequestStage.BEFORE_REQUEST, sink.records.single().stage)
    }

    @Test fun invalidServerResponseIsReportedAsValidationFailure() = runTest {
        val sink = Sink(); val wrongPage = FinanceResult.Success(CategoryPage(listOf(cat(1)), 2, 50, 1))
        assertEquals(FinanceResult.Failure(FinanceFailure.InvalidResponse), repo(remote { wrongPage }, InMemoryCategoryCache(), sink).list(query))
        assertEquals(CategoryRequestStage.RESPONSE_VALIDATION, sink.records.last().stage)
    }

    @Test fun formattedEventsContainNoNamesIdentifiersOrSessionValues() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(accountId, listOf(cat(1)))
        val sink = Sink()
        repo(remote { offline }, cache, sink).list(query)
        repo(remote { offline }, InMemoryCategoryCache(), sink, identity = { null }).list(query)
        assertTrue(sink.lines.isNotEmpty())
        for (line in sink.lines) {
            assertFalse(line, line.contains(secretName) || line.contains("Secret") || line.contains(session) || line.contains(accountId.toString()))
            assertTrue(line, line.matches(Regex("^event=[A-Z_]+( [a-z]+=[A-Za-z0-9_]+)+$")))
        }
    }

    @Test fun throwingSinkDoesNotChangeResults() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(accountId, listOf(cat(1)))
        val broken = CategoryDiagnosticSink { throw IllegalStateException("sink failure") }
        val result = repo(remote { offline }, cache, broken).list(query)
        assertEquals(listOf(cat(1)), (result as FinanceResult.Success).value.items)
    }
}
