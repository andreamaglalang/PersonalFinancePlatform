package com.elect3.mobdev.personalfinance.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.local.InMemoryCategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelsTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    @After fun clear() = store.clear()
    private var owned = 0
    private inline fun <reified T : ViewModel> own(crossinline make: () -> T): T = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <V : ViewModel> create(modelClass: Class<V>): V { @Suppress("UNCHECKED_CAST") return make() as V }
    })["vm${owned++}", T::class.java]

    private fun cat(id: Int, name: String, type: TransactionType = TransactionType.Expense, active: Boolean = true, default: Boolean = false) =
        Category(id, name, type, null, active, default)

    /** Paged fake honouring the request's page and size, recording calls. */
    private class PagedCategories(val rows: List<Category>, var failure: FinanceFailure? = null) : CategoryRepository {
        val queries = mutableListOf<CategoryQuery>()
        override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
            queries += query
            failure?.let { return FinanceResult.Failure(it) }
            val visible = rows.filter { query.includeInactive || it.isActive }
            val from = (query.page - 1) * query.pageSize
            return FinanceResult.Success(CategoryPage(visible.drop(from).take(query.pageSize), query.page, query.pageSize, visible.size.toLong()))
        }
    }
    private class FakeWrites : CategoryWriteRepository {
        val calls = mutableListOf<String>()
        var deleteResult: FinanceResult<Unit> = FinanceResult.Success(Unit)
        var writeFailure: FinanceFailure? = null
        override suspend fun create(draft: CategoryDraft): FinanceResult<Category> { calls += "create:${draft.trimmedName}:${draft.colorHex}"
            return writeFailure?.let { FinanceResult.Failure(it) } ?: FinanceResult.Success(Category(900, draft.trimmedName, draft.type, draft.colorHex, draft.isActive, false)) }
        override suspend fun update(categoryId: Int, draft: CategoryDraft): FinanceResult<Category> { calls += "update:$categoryId:${draft.isActive}"
            return writeFailure?.let { FinanceResult.Failure(it) } ?: FinanceResult.Success(Category(categoryId, draft.trimmedName, draft.type, draft.colorHex, draft.isActive, false)) }
        override suspend fun delete(categoryId: Int): FinanceResult<Unit> { calls += "delete:$categoryId"; return deleteResult }
    }

    @Test fun catalogReadsEveryPageIncludingInactive() = runTest(main.dispatcher) {
        val rows = (1..230).map { cat(it, "C%03d".format(it), active = it % 10 != 0) }
        val repo = PagedCategories(rows)
        val vm = own { CategoryCatalogViewModel(repo, null, InMemoryCategoryPresentationStore(), demo = false) }
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), repo.queries.map { it.page })
        assertTrue(repo.queries.all { it.includeInactive && it.pageSize == 100 })
        assertEquals(230, vm.state.value.all.size)
        assertEquals(207, vm.active(TransactionType.Expense).size)
        assertFalse(vm.canManage)
    }

    @Test fun failedReadShowsErrorNotAPartialListAndSessionLossExpires() = runTest(main.dispatcher) {
        val repo = PagedCategories(listOf(cat(1, "Food")), FinanceFailure.Unavailable)
        val vm = own { CategoryCatalogViewModel(repo, null, InMemoryCategoryPresentationStore(), demo = false) }
        advanceUntilIdle()
        assertEquals(FinanceFailure.Unavailable, vm.state.value.error); assertTrue(vm.state.value.all.isEmpty())
        var expired = 0
        val expiring = own { CategoryCatalogViewModel(PagedCategories(emptyList(), FinanceFailure.SessionExpired), null,
            InMemoryCategoryPresentationStore(), demo = false, onSessionExpired = { expired++ }) }
        advanceUntilIdle()
        assertEquals(1, expired); assertTrue(expiring.state.value.invalidated)
    }

    @Test fun deleteConflictOffersDeactivationWhichKeepsTheCategory() = runTest(main.dispatcher) {
        val food = cat(5, "Food")
        val writes = FakeWrites().apply { deleteResult = FinanceResult.Failure(FinanceFailure.Conflict) }
        val vm = own { CategoryCatalogViewModel(PagedCategories(listOf(food)), writes, InMemoryCategoryPresentationStore(), demo = false) }
        advanceUntilIdle()
        vm.requestRemove(food); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(CatalogDialog.InUse(food), vm.state.value.dialog)
        vm.deactivate(); advanceUntilIdle()
        assertEquals(listOf("delete:5", "update:5:false"), writes.calls)
        assertFalse(vm.state.value.all.single().isActive)
        assertEquals(CatalogMessageKind.Deactivated, vm.state.value.message?.kind)
        assertTrue(vm.active(TransactionType.Expense).isEmpty())
        vm.reactivate(vm.state.value.all.single()); advanceUntilIdle()
        assertTrue(vm.state.value.all.single().isActive)
    }

    @Test fun systemDefaultsAreNeverWritten() = runTest(main.dispatcher) {
        val default = cat(1, "Salary", TransactionType.Income, default = true)
        val writes = FakeWrites()
        val vm = own { CategoryCatalogViewModel(PagedCategories(listOf(default)), writes, InMemoryCategoryPresentationStore(), demo = false) }
        advanceUntilIdle()
        vm.requestRemove(default); vm.reactivate(default.copy(isActive = false)); advanceUntilIdle()
        assertNull(vm.state.value.dialog); assertTrue(writes.calls.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            CategoryEditorViewModel(writes, InMemoryCategoryPresentationStore(), default, TransactionType.Income, null, emptyList())
        }
    }

    @Test fun reorderIsLocalAndUsedByThePicker() = runTest(main.dispatcher) {
        val presentation = InMemoryCategoryPresentationStore()
        val vm = own { CategoryCatalogViewModel(PagedCategories(listOf(cat(1, "Alpha"), cat(2, "Beta"), cat(3, "Gamma"))), FakeWrites(), presentation, demo = false) }
        advanceUntilIdle()
        assertEquals(listOf("Alpha", "Beta", "Gamma"), vm.active(TransactionType.Expense).map { it.name })
        vm.move(TransactionType.Expense, 3, -1); vm.move(TransactionType.Expense, 3, -1)
        assertEquals(listOf("Gamma", "Alpha", "Beta"), vm.active(TransactionType.Expense).map { it.name })
        assertEquals(listOf(3, 1, 2), presentation.order(TransactionType.Expense))
        vm.move(TransactionType.Expense, 3, -1) // already first: no change
        assertEquals(listOf(3, 1, 2), presentation.order(TransactionType.Expense))
    }

    @Test fun editorValidatesNameLikeTheApi() = runTest(main.dispatcher) {
        val writes = FakeWrites()
        val siblings = listOf(cat(1, "Food"), cat(2, "Salary", TransactionType.Income))
        val vm = own { CategoryEditorViewModel(writes, InMemoryCategoryPresentationStore(), null, TransactionType.Expense, null, siblings) }
        assertEquals(NameProblem.Required, vm.nameProblem())
        vm.name("  FOOD "); assertEquals(NameProblem.Duplicate, vm.nameProblem())
        vm.type(TransactionType.Income); assertNull(vm.nameProblem()) // other type is fine
        vm.type(TransactionType.Expense); vm.name("x".repeat(51)); assertEquals(NameProblem.TooLong, vm.nameProblem())
        vm.save(); advanceUntilIdle()
        assertTrue(writes.calls.isEmpty()); assertNull(vm.state.value.saved)
    }

    @Test fun createSendsNameTypeColourAndKeepsTheIconOnTheDevice() = runTest(main.dispatcher) {
        val writes = FakeWrites(); val presentation = InMemoryCategoryPresentationStore()
        val vm = own { CategoryEditorViewModel(writes, presentation, null, TransactionType.Expense, null, emptyList()) }
        vm.name(" Books "); vm.icon("book"); vm.color("#2F8A5B"); vm.icon("not-an-icon")
        vm.save(); vm.save() // duplicate tap
        advanceUntilIdle()
        assertEquals(listOf("create:Books:#2F8A5B"), writes.calls)
        assertEquals(900, vm.state.value.saved?.categoryId)
        assertEquals("book", presentation.icon(900))
    }

    @Test fun iconOnlyEditSendsNoRequestAndTypeIsLocked() = runTest(main.dispatcher) {
        val writes = FakeWrites(); val presentation = InMemoryCategoryPresentationStore()
        val pets = cat(7, "Pets")
        val vm = own { CategoryEditorViewModel(writes, presentation, pets, pets.type, null, listOf(pets)) }
        vm.type(TransactionType.Income); assertEquals(TransactionType.Expense, vm.state.value.type)
        vm.icon("paw"); vm.save(); advanceUntilIdle()
        assertTrue(writes.calls.isEmpty()); assertEquals("paw", presentation.icon(7)); assertEquals(pets, vm.state.value.saved)
    }

    @Test fun conflictsAndUnconfirmedSavesAreReportedHonestly() = runTest(main.dispatcher) {
        val writes = FakeWrites().apply { writeFailure = FinanceFailure.Conflict }
        val vm = own { CategoryEditorViewModel(writes, InMemoryCategoryPresentationStore(), null, TransactionType.Expense, null, emptyList()) }
        vm.name("Rent"); vm.save(); advanceUntilIdle()
        assertEquals(EditorError.NameConflict, vm.state.value.error); assertNull(vm.state.value.saved)
        writes.writeFailure = FinanceFailure.Timeout
        vm.name("Rent 2"); vm.save(); advanceUntilIdle()
        assertEquals(EditorError.Unknown, vm.state.value.error)
    }

    @Test fun unsavedChangesNeedConfirmationBeforeLeaving() = runTest(main.dispatcher) {
        val vm = own { CategoryEditorViewModel(FakeWrites(), InMemoryCategoryPresentationStore(), null, TransactionType.Expense, null, emptyList()) }
        vm.back(); assertTrue(vm.state.value.closeRequested)
        val dirty = own { CategoryEditorViewModel(FakeWrites(), InMemoryCategoryPresentationStore(), cat(3, "Gym"), TransactionType.Expense, "dumbbell", emptyList()) }
        dirty.color("#4468C9"); dirty.back()
        assertTrue(dirty.state.value.discardConfirmation); assertFalse(dirty.state.value.closeRequested)
        dirty.keepEditing(); assertFalse(dirty.state.value.discardConfirmation)
        dirty.back(); dirty.discard(); assertTrue(dirty.state.value.closeRequested)
    }

    @Test fun demoCatalogUsesTheInMemoryStoreEndToEnd() = runTest(main.dispatcher) {
        val session = DemoSession()
        val vm = own { CategoryCatalogViewModel(session.categories, session.categories, session.presentation, demo = true) }
        advanceUntilIdle()
        val housing = vm.state.value.all.first { it.name == "Housing" }
        vm.requestRemove(housing); vm.confirmDelete(); advanceUntilIdle()
        assertTrue(vm.state.value.all.none { it.name == "Housing" })
        assertEquals(CatalogMessageKind.Deleted, vm.state.value.message?.kind)
    }

    @Test fun visualsCoverEveryIconAndParseColours() {
        val keys = CategoryVisuals.groups.flatMap { it.icons }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        listOf("Food", "Transport", "Utilities", "Salary", "Freelance", "Healthcare", "Shopping", "Other").forEach {
            assertTrue(CategoryVisuals.isKnown(CategoryVisuals.defaultIconKey(it, TransactionType.Expense)))
        }
        assertEquals("tag", CategoryVisuals.defaultIconKey("Zzz", TransactionType.Expense))
        assertEquals("heart", CategoryVisuals.defaultIconKey("Healthcare", TransactionType.Expense))
        assertEquals("car", CategoryVisuals.defaultIconKey("Car loan", TransactionType.Expense))
        assertEquals(15, CategoryVisuals.palette.size)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF2F8A5B), categoryColor("#2F8A5B"))
        assertEquals("#123ABC", CategoryVisuals.displayHex(Category(1, "X", TransactionType.Expense, "#123abc", true, false)))
    }
}
