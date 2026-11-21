package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.CancellationException

/** Cache server-confirmed pages without converting a partial device cache into a server page or Dashboard total. */
fun cacheTransactionHistory(remote: TransactionRepository, ledger: LocalLedger, accountId: Int,
    isSessionCurrent: () -> Boolean): TransactionRepository {
    suspend fun cached(read: suspend () -> TransactionPageResult): TransactionPageResult {
        if (!isSessionCurrent()) return TransactionPageResult.Failure(TransactionFailure.SessionExpired)
        val result = read()
        if (!isSessionCurrent()) return TransactionPageResult.Failure(TransactionFailure.SessionExpired)
        if (result is TransactionPageResult.Success) {
            try { ledger.recordSynced(accountId, result.page.items) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cache failure must not hide a successful server read. */ }
        }
        return result
    }
    return if (remote is FilterableTransactionRepository) object : FilterableTransactionRepository {
        override suspend fun readPage(page: Int, pageSize: Int) = cached { remote.readPage(page, pageSize) }
        override suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter) =
            cached { remote.readPage(page, pageSize, filter) }
    } else object : TransactionRepository {
        override suspend fun readPage(page: Int, pageSize: Int) = cached { remote.readPage(page, pageSize) }
    }
}

/** Local-only filtering. The result is explicitly incomplete; it is never used for server pagination/totals. */
fun filterLocalHistory(rows: List<LocalHistoryTransaction>, filter: TransactionFilter): List<LocalHistoryTransaction> {
    val matching = rows.filter {
        (filter.type == null || it.draft.type == filter.type) &&
        (filter.categoryId == null || it.draft.categoryId == filter.categoryId) &&
        (filter.fromDate == null || it.draft.transactionDate >= filter.fromDate) &&
        (filter.toDate == null || it.draft.transactionDate <= filter.toDate)
    }
    val sorted = matching.sortedWith(compareBy<LocalHistoryTransaction> { it.draft.transactionDate }.thenBy { it.clientId.toString() })
    return if (filter.sort == TransactionListSort.Oldest) sorted else sorted.reversed()
}
