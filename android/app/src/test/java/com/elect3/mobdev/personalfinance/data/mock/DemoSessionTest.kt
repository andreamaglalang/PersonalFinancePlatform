package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Mock Demo session: memory only, API-like category rules, and a full reset when the demo is left. */
class DemoSessionTest {
    private suspend fun DemoCategoryStore.all() =
        (list(CategoryQuery(includeInactive = true, pageSize = 100)) as FinanceResult.Success).value.items

    @Test fun demoSaveAppearsInTheDemoListAndResetRemovesIt() = runTest {
        val session = DemoSession()
        val repo = MockTransactionRepository(ledger = session.ledger)
        val food = session.categories.all().first { it.name == "Food" }
        val before = (repo.readPage(1, 100) as TransactionPageResult.Success).page.totalCount
        session.ledger.add(TransactionType.Expense, food, BigDecimal("99.50"), LocalDate.of(2026, 11, 19), "Snacks")
        val page = (repo.readPage(1, 100) as TransactionPageResult.Success).page
        assertEquals(before + 1, page.totalCount)
        val newest = page.items.first()
        assertEquals("Food", newest.categoryName); assertEquals(0, BigDecimal("99.50").compareTo(newest.amount))
        assertEquals("PHP", newest.currencyCode)
        session.reset()
        assertEquals(before, (repo.readPage(1, 100) as TransactionPageResult.Success).page.totalCount)
    }

    @Test fun demoSaveIsCountedByTheSampleDashboardSummary() = runTest {
        val session = DemoSession()
        val repo = MockTransactionRepository(ledger = session.ledger)
        val before = (LoadDashboardSummary(repo)(java.time.YearMonth.of(2026, 11)) as com.elect3.mobdev.personalfinance.domain.dashboard.DashboardSummaryResult.Success).summary
        val salary = session.categories.all().first { it.name == "Salary" }
        session.ledger.add(TransactionType.Income, salary, BigDecimal("1000.00"), LocalDate.of(2026, 11, 19), null)
        val after = (LoadDashboardSummary(repo)(java.time.YearMonth.of(2026, 11)) as com.elect3.mobdev.personalfinance.domain.dashboard.DashboardSummaryResult.Success).summary
        assertEquals(0, before.totalIncome.add(BigDecimal("1000.00")).compareTo(after.totalIncome))
        assertEquals(before.recordCount + 1, after.recordCount)
    }

    @Test fun fixtureCategoriesKeepTheirIdsAndAreReadOnly() = runTest {
        val store = DemoSession().categories
        val fixtureIds = TransactionFixtures.items.map { it.categoryId }.toSet()
        val defaults = store.all().filter { it.isSystemDefault }
        assertEquals(fixtureIds, defaults.map { it.categoryId }.toSet())
        val food = defaults.first { it.name == "Food" }
        assertEquals(FinanceResult.Failure(FinanceFailure.AccessDenied), store.update(food.categoryId, CategoryDraft("Meals", food.type, null)))
        assertEquals(FinanceResult.Failure(FinanceFailure.AccessDenied), store.delete(food.categoryId))
    }

    @Test fun namesAreUniquePerTypeIgnoringCaseAndSpaces() = runTest {
        val store = DemoSession().categories
        assertEquals(FinanceResult.Failure(FinanceFailure.Conflict), store.create(CategoryDraft("  food ", TransactionType.Expense, null)))
        // Same name with the other type is allowed, as on the server.
        assertTrue(store.create(CategoryDraft("Food", TransactionType.Income, null)) is FinanceResult.Success)
    }

    @Test fun usedCategoriesCannotBeDeletedButCanBeDeactivated() = runTest {
        val session = DemoSession()
        val store = session.categories
        val pets = store.all().first { it.name == "Pets" }
        session.ledger.add(TransactionType.Expense, pets, BigDecimal("10"), LocalDate.of(2026, 11, 19), null)
        assertEquals(FinanceResult.Failure(FinanceFailure.Conflict), store.delete(pets.categoryId))
        assertEquals(FinanceResult.Failure(FinanceFailure.Conflict), store.update(pets.categoryId, CategoryDraft("Pets", TransactionType.Income, null)))
        val off = (store.update(pets.categoryId, CategoryDraft("Pets", TransactionType.Expense, pets.colorHex, isActive = false)) as FinanceResult.Success).value
        assertFalse(off.isActive)
        val activeOnly = (store.list(CategoryQuery(type = TransactionType.Expense)) as FinanceResult.Success).value.items
        assertTrue(activeOnly.none { it.categoryId == pets.categoryId })
        val unused = store.all().first { it.name == "Housing" }
        assertEquals(Unit, (store.delete(unused.categoryId) as FinanceResult.Success).value)
    }

    @Test fun resetRestoresSeedCategoriesAndPresentation() = runTest {
        val session = DemoSession()
        val created = (session.categories.create(CategoryDraft("Books", TransactionType.Expense, "#6747C7")) as FinanceResult.Success).value
        session.presentation.setIcon(created.categoryId, "book")
        session.reset()
        assertTrue(session.categories.all().none { it.name == "Books" })
        assertNull(session.presentation.icon(created.categoryId))
    }
}
