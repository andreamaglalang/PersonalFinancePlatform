package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo source filters the COMPLETE fixture set before paging, like the API. */
class MockTransactionFilterTest {
    private val all = TransactionFixtures.items
    private suspend fun readAll(filter: TransactionFilter, pageSize: Int = 7): Pair<Long, List<Int>> {
        val repo = MockTransactionRepository()
        val ids = mutableListOf<Int>(); var page = 1; var total = -1L
        while (true) {
            val r = repo.readPage(page, pageSize, filter) as TransactionPageResult.Success
            total = r.page.totalCount
            ids += r.page.items.map { it.transactionId }
            if (r.page.items.isEmpty() || ids.size.toLong() >= total) break
            page++
        }
        return total to ids
    }

    @Test fun typeFilterTotalCountsTheFullFilteredSetAcrossPages() = runTest {
        val expected = all.filter { it.type == TransactionType.Income }
        val (total, ids) = readAll(TransactionFilter(type = TransactionType.Income))
        assertEquals(expected.size.toLong(), total)
        assertEquals(expected.map { it.transactionId }.toSet(), ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun categoryAndInclusiveDateRangeCombine() = runTest {
        val sample = all.first { it.type == TransactionType.Expense }
        val from = sample.transactionDate.minusDays(3); val to = sample.transactionDate
        val expected = all.filter { it.categoryId == sample.categoryId && !it.transactionDate.isBefore(from) && !it.transactionDate.isAfter(to) }
        val (total, ids) = readAll(TransactionFilter(categoryId = sample.categoryId, categoryName = sample.categoryName, fromDate = from, toDate = to))
        assertEquals(expected.size.toLong(), total)
        assertTrue(ids.contains(sample.transactionId))
    }

    @Test fun oldestFirstOrdersByDateThenIdAscending() = runTest {
        val (_, ids) = readAll(TransactionFilter(sort = TransactionListSort.Oldest))
        val expected = all.sortedWith(compareBy({ it.transactionDate }, { it.transactionId })).map { it.transactionId }
        assertEquals(expected, ids)
    }

    @Test fun defaultFilterMatchesTheUnfilteredReadExactly() = runTest {
        val repo = MockTransactionRepository()
        val plain = repo.readPage(1, 20) as TransactionPageResult.Success
        val viaFilter = repo.readPage(1, 20, TransactionFilter()) as TransactionPageResult.Success
        assertEquals(plain.page.items, viaFilter.page.items)
        assertEquals(plain.page.totalCount, viaFilter.page.totalCount)
    }

    @Test fun noMatchesIsAnEmptyCompletePage() = runTest {
        val r = MockTransactionRepository().readPage(1, 20, TransactionFilter(fromDate = LocalDate.of(1999, 1, 1), toDate = LocalDate.of(1999, 1, 31)))
            as TransactionPageResult.Success
        assertEquals(0L, r.page.totalCount)
        assertTrue(r.page.items.isEmpty())
    }

    @Test fun invalidRangesAndCategoriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(fromDate = LocalDate.of(2026, 11, 30), toDate = LocalDate.of(2026, 11, 1)) }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(categoryId = 0, categoryName = "x") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(categoryId = 3) }
        assertTrue(TransactionFilter().isDefault)
        assertTrue(!TransactionFilter(sort = TransactionListSort.Oldest).narrows)
        assertTrue(TransactionFilter(type = TransactionType.Expense).narrows)
    }
}
