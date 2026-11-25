package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryDraft
import com.elect3.mobdev.personalfinance.domain.repository.CategoryPage
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.CategoryWriteRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.categoryNamesMatch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Mock Demo session state, held in memory only. Nothing here is written to disk or sent to any API,
 * and [reset] returns everything to the fixed sample data (called when the demo is left).
 */
class DemoSession {
    val ledger = DemoLedger()
    val categories = DemoCategoryStore(ledger)
    /** Demo icon and order choices: memory only. */
    val presentation = com.elect3.mobdev.personalfinance.data.local.InMemoryCategoryPresentationStore()
    /** Demo budgets: memory only, spending derived from the demo transactions. */
    val budgets = DemoBudgetStore(ledger, categories)
    val plan = DemoPlanStore()
    fun reset() { ledger.clear(); categories.reset(); presentation.clear(); budgets.reset(); plan.reset() }
}

/** Demo transactions added during this session, on top of the fixed fixtures. */
class DemoLedger {
    private val lock = Any()
    private val added = ArrayList<Transaction>()
    private var nextId = FIRST_ID

    fun snapshot(): List<Transaction> = synchronized(lock) { ArrayList(added) }

    fun add(type: TransactionType, category: Category, amount: BigDecimal, date: LocalDate, description: String?): Transaction {
        require(category.type == type && category.isActive && amount.signum() > 0)
        return synchronized(lock) {
            Transaction(nextId++, UUID.randomUUID(), type, category.categoryId, category.name, amount, "PHP",
                BigDecimal.ONE, date, amount, date, description, Instant.now(), null, null).also { added += it }
        }
    }

    fun uses(categoryId: Int): Boolean = synchronized(lock) { added.any { it.categoryId == categoryId } } ||
        TransactionFixtures.items.any { it.categoryId == categoryId }

    fun clear() = synchronized(lock) { added.clear(); nextId = FIRST_ID }

    private companion object { const val FIRST_ID = 1000 }
}

/**
 * Demo categories. The seven categories used by the sample transactions keep their fixture IDs and
 * act like system defaults (read-only, as on the real server); the rest are editable demo categories.
 * Applies the same rules as the API: unique trimmed case-insensitive names per type, no delete when used.
 */
class DemoCategoryStore(private val ledger: DemoLedger) : CategoryRepository, CategoryWriteRepository {
    private val lock = Any()
    private val rows = ArrayList<Category>()
    private var nextId = FIRST_USER_ID
    init { reset() }

    fun reset() = synchronized(lock) {
        rows.clear(); nextId = FIRST_USER_ID
        TransactionFixtures.items.distinctBy { it.categoryId }.sortedBy { it.categoryId }.forEach {
            rows += Category(it.categoryId, it.categoryName, it.type, FIXTURE_COLORS[it.categoryName], true, true)
        }
        listOf(
            "Housing" to "#8F6D5C", "Education" to "#6747C7", "Entertainment" to "#7E62D8", "Pets" to "#93791A",
        ).forEach { (name, color) -> rows += Category(nextId++, name, TransactionType.Expense, color, true, false) }
        listOf("Business" to "#1F8A82", "Allowance" to "#2F7FB0").forEach { (name, color) ->
            rows += Category(nextId++, name, TransactionType.Income, color, true, false)
        }
    }

    override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
        val all = synchronized(lock) { ArrayList(rows) }
            .filter { (query.type == null || it.type == query.type) && (query.includeInactive || it.isActive) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.categoryId }))
        val offset = ((query.page - 1L) * query.pageSize).coerceAtMost(all.size.toLong()).toInt()
        return FinanceResult.Success(CategoryPage(all.drop(offset).take(query.pageSize), query.page, query.pageSize, all.size.toLong()))
    }

    override suspend fun create(draft: CategoryDraft): FinanceResult<Category> = synchronized(lock) {
        if (duplicate(draft, null)) return FinanceResult.Failure(FinanceFailure.Conflict)
        val row = Category(nextId++, draft.trimmedName, draft.type, draft.colorHex?.uppercase(), draft.isActive, false)
        rows += row
        FinanceResult.Success(row)
    }

    override suspend fun update(categoryId: Int, draft: CategoryDraft): FinanceResult<Category> = synchronized(lock) {
        val index = rows.indexOfFirst { it.categoryId == categoryId }
        if (index < 0) return FinanceResult.Failure(FinanceFailure.NotFound)
        val current = rows[index]
        if (current.isSystemDefault) return FinanceResult.Failure(FinanceFailure.AccessDenied)
        if (current.type != draft.type && ledger.uses(categoryId)) return FinanceResult.Failure(FinanceFailure.Conflict)
        if (duplicate(draft, categoryId)) return FinanceResult.Failure(FinanceFailure.Conflict)
        val row = current.copy(name = draft.trimmedName, type = draft.type, colorHex = draft.colorHex?.uppercase(), isActive = draft.isActive)
        rows[index] = row
        FinanceResult.Success(row)
    }

    override suspend fun delete(categoryId: Int): FinanceResult<Unit> = synchronized(lock) {
        val current = rows.firstOrNull { it.categoryId == categoryId } ?: return FinanceResult.Failure(FinanceFailure.NotFound)
        if (current.isSystemDefault) return FinanceResult.Failure(FinanceFailure.AccessDenied)
        if (ledger.uses(categoryId)) return FinanceResult.Failure(FinanceFailure.Conflict)
        rows.remove(current)
        FinanceResult.Success(Unit)
    }

    private fun duplicate(draft: CategoryDraft, exclude: Int?) =
        rows.any { it.categoryId != exclude && it.type == draft.type && categoryNamesMatch(it.name, draft.name) }

    private companion object {
        const val FIRST_USER_ID = 100
        val FIXTURE_COLORS = mapOf(
            "Salary" to "#2F8A5B", "Freelance" to "#6747C7", "Food" to "#CC5F49", "Transport" to "#4468C9",
            "Utilities" to "#B8691F", "Shopping" to "#A55AA9", "Healthcare" to "#C9577A",
        )
    }
}
