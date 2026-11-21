package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*

/** Local form choices only. No authenticated dependencies and no persistence. */
class DemoCategoryRepository : CategoryRepository {
    override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
        val type = query.type ?: TransactionType.Expense
        val names = if (type == TransactionType.Income) listOf("Salary", "Freelance", "Other income")
            else listOf("Food", "Transport", "Shopping", "Utilities", "Health", "Other expense")
        val rows = names.mapIndexed { index, name -> Category(index + 1, name, type, null, true, true) }
        val offset = ((query.page - 1L) * query.pageSize).coerceAtMost(rows.size.toLong()).toInt()
        return FinanceResult.Success(CategoryPage(rows.drop(offset).take(query.pageSize), query.page, query.pageSize, rows.size.toLong()))
    }
}
