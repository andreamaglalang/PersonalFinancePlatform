package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryPage
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult

/**
 * Mock Demo categories for FILTERING the demo transaction list. Built from the sample transactions
 * themselves, so every category ID and name matches the records being filtered.
 * (The Add Transaction demo form keeps its own local choices; those IDs do not refer to fixture records.)
 */
class DemoFixtureCategoryRepository : CategoryRepository {
    override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
        val rows = TransactionFixtures.items
            .distinctBy { it.categoryId }
            .filter { query.type == null || it.type == query.type }
            .map { Category(it.categoryId, it.categoryName, it.type, null, true, true) }
            .sortedBy { it.categoryId }
        val offset = ((query.page - 1L) * query.pageSize).coerceAtMost(rows.size.toLong()).toInt()
        return FinanceResult.Success(CategoryPage(rows.drop(offset).take(query.pageSize), query.page, query.pageSize, rows.size.toLong()))
    }
}
