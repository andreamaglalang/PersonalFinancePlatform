package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo filter categories must identify the same records the demo list contains. */
class DemoFixtureCategoryRepositoryTest {
    private suspend fun list(type: TransactionType?) =
        (DemoFixtureCategoryRepository().list(CategoryQuery(type = type, includeInactive = true, pageSize = 100))
            as FinanceResult.Success).value.items

    @Test fun everyOptionMatchesAFixtureIdNameAndType() = runTest {
        val all = list(null)
        val fixtures = TransactionFixtures.items.associateBy { it.categoryId }
        assertEquals(fixtures.keys, all.map { it.categoryId }.toSet())
        all.forEach { c -> assertEquals(fixtures.getValue(c.categoryId).categoryName, c.name); assertEquals(fixtures.getValue(c.categoryId).type, c.type) }
    }

    @Test fun typeQueryNarrowsOptions() = runTest {
        assertTrue(list(TransactionType.Income).all { it.type == TransactionType.Income })
        assertTrue(list(TransactionType.Expense).all { it.type == TransactionType.Expense })
    }

    @Test fun filteringByAnOptionReturnsOnlyThatCategory() = runTest {
        val food = list(TransactionType.Expense).first { it.name == "Food" }
        val page = MockTransactionRepository().readPage(1, 100, TransactionFilter(categoryId = food.categoryId, categoryName = food.name))
            as TransactionPageResult.Success
        assertTrue(page.page.items.isNotEmpty())
        assertTrue(page.page.items.all { it.categoryName == "Food" })
    }
}
