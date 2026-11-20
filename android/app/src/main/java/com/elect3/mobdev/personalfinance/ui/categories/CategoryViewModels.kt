package com.elect3.mobdev.personalfinance.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.data.local.CategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.local.inDisplayOrder
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CATEGORY_NAME_MAX
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryDraft
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.CategoryWriteRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.categoryNamesMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CATEGORY_PAGE = 100
private const val MAX_CATEGORY_PAGES = 50

private fun FinanceFailure.isSession() = this == FinanceFailure.MissingSession || this == FinanceFailure.SessionExpired

/** Reads every page (API page size limit 100). Any failure fails the whole read; no partial lists are shown. */
internal suspend fun readAllCategories(repository: CategoryRepository, includeInactive: Boolean = true): FinanceResult<List<Category>> {
    val items = ArrayList<Category>()
    var expectedTotal: Long? = null
    for (page in 1..MAX_CATEGORY_PAGES) {
        val result = try { repository.list(CategoryQuery(includeInactive = includeInactive, page = page, pageSize = CATEGORY_PAGE)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }
        when (result) {
            is FinanceResult.Failure -> return result
            is FinanceResult.Success -> {
                val value = result.value
                if (value.page != page || value.pageSize != CATEGORY_PAGE ||
                    (expectedTotal != null && value.totalCount != expectedTotal)) return FinanceResult.Failure(FinanceFailure.InvalidResponse)
                expectedTotal = value.totalCount
                items += value.items
                if (items.map { it.categoryId }.distinct().size != items.size || items.size.toLong() > value.totalCount)
                    return FinanceResult.Failure(FinanceFailure.InvalidResponse)
                if (items.size.toLong() == value.totalCount) return FinanceResult.Success(items)
                if (value.items.isEmpty()) return FinanceResult.Failure(FinanceFailure.InvalidResponse)
            }
        }
    }
    return FinanceResult.Failure(FinanceFailure.InvalidResponse)
}

sealed interface CatalogDialog {
    data class ConfirmDelete(val category: Category) : CatalogDialog
    data class InUse(val category: Category) : CatalogDialog
}
enum class CatalogMessageKind { Added, Updated, Deleted, Deactivated, Reactivated, Denied, Conflict, Failed, Unknown }
data class CatalogMessage(val kind: CatalogMessageKind, val name: String = "", val id: Long = 0)

data class CategoryCatalogState(
    val loading: Boolean = true,
    val error: FinanceFailure? = null,
    val all: List<Category> = emptyList(),
    val presentationVersion: Int = 0,
    val busyId: Int? = null,
    val dialog: CatalogDialog? = null,
    val message: CatalogMessage? = null,
    val reorder: Boolean = false,
    val invalidated: Boolean = false,
) {
    val loaded get() = !loading && error == null
}

/**
 * One account's (or the demo's) categories for the Add Transaction picker and Category settings.
 * [writes] is null when management is unavailable; rows are then read-only.
 */
class CategoryCatalogViewModel(
    private val categories: CategoryRepository,
    val writes: CategoryWriteRepository?,
    val presentation: CategoryPresentationStore,
    val demo: Boolean,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    /** Selection-only catalogs use active rows; category management retains its existing all-row query. */
    private val includeInactive: Boolean = true,
) : ViewModel() {
    private val mutable = MutableStateFlow(CategoryCatalogState())
    val state = mutable.asStateFlow()
    private var load: Job? = null
    private var action: Job? = null
    private var messages = 0L

    init {
        refresh()
        viewModelScope.launch { presentation.version.collect { v -> mutable.value = mutable.value.copy(presentationVersion = v) } }
    }

    val canManage get() = writes != null

    fun active(type: TransactionType) = mutable.value.all.filter { it.isActive }.inDisplayOrder(presentation, type)
    fun everything(type: TransactionType) = mutable.value.all.inDisplayOrder(presentation, type)
    fun iconKey(category: Category) = presentation.icon(category.categoryId)?.takeIf { CategoryVisuals.isKnown(it) }
        ?: CategoryVisuals.defaultIconKey(category.name, category.type)

    fun refresh() {
        if (mutable.value.invalidated) return
        load?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        load = viewModelScope.launch {
            val result = readAllCategories(categories, includeInactive)
            if (!isSessionCurrent()) { expire(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> mutable.value.copy(loading = false, all = result.value)
                is FinanceResult.Failure -> {
                    if (result.reason.isSession()) { expire(); return@launch }
                    mutable.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    private fun editable(category: Category) = writes != null && !category.isSystemDefault &&
        mutable.value.busyId == null && !mutable.value.invalidated && isSessionCurrent()

    fun requestRemove(category: Category) {
        if (editable(category)) mutable.value = mutable.value.copy(dialog = CatalogDialog.ConfirmDelete(category))
    }
    fun dismissDialog() { if (mutable.value.busyId == null) mutable.value = mutable.value.copy(dialog = null) }

    fun confirmDelete() {
        val dialog = mutable.value.dialog as? CatalogDialog.ConfirmDelete ?: return
        val category = dialog.category
        val repo = writes ?: return
        run(category) {
            when (val result = repo.delete(category.categoryId)) {
                is FinanceResult.Success -> {
                    presentation.forget(category.categoryId)
                    mutable.value = mutable.value.copy(all = mutable.value.all.filterNot { it.categoryId == category.categoryId },
                        dialog = null, message = message(CatalogMessageKind.Deleted, category.name))
                }
                is FinanceResult.Failure -> when (result.reason) {
                    // The API refuses to delete referenced categories; offer the archive path instead.
                    FinanceFailure.Conflict -> mutable.value = mutable.value.copy(dialog = CatalogDialog.InUse(category))
                    else -> fail(result.reason, category)
                }
            }
        }
    }

    fun deactivate() {
        val dialog = mutable.value.dialog as? CatalogDialog.InUse ?: return
        setActive(dialog.category, false)
    }
    fun reactivate(category: Category) { if (editable(category) && !category.isActive) setActive(category, true) }

    private fun setActive(category: Category, active: Boolean) {
        val repo = writes ?: return
        run(category) {
            val draft = CategoryDraft(category.name, category.type, category.colorHex, isActive = active)
            when (val result = repo.update(category.categoryId, draft)) {
                is FinanceResult.Success -> mutable.value = mutable.value.copy(all = mutable.value.all.map {
                    if (it.categoryId == category.categoryId) result.value else it
                }, dialog = null, message = message(if (active) CatalogMessageKind.Reactivated else CatalogMessageKind.Deactivated, category.name))
                is FinanceResult.Failure -> fail(result.reason, category)
            }
        }
    }

    private fun run(category: Category, block: suspend () -> Unit) {
        if (writes == null || category.isSystemDefault || mutable.value.busyId != null || mutable.value.invalidated || !isSessionCurrent()) return
        mutable.value = mutable.value.copy(busyId = category.categoryId)
        action = viewModelScope.launch {
            try {
                block()
                if (!isSessionCurrent()) expire()
            } finally {
                if (!mutable.value.invalidated) mutable.value = mutable.value.copy(busyId = null)
            }
        }
    }

    private fun fail(reason: FinanceFailure, category: Category) {
        if (reason.isSession()) { expire(); return }
        val kind = when (reason) {
            FinanceFailure.AccessDenied -> CatalogMessageKind.Denied
            FinanceFailure.Conflict, FinanceFailure.NotFound -> CatalogMessageKind.Conflict
            // The request may have reached the server: never guess, reload what it actually holds.
            FinanceFailure.Timeout, FinanceFailure.InvalidResponse, FinanceFailure.Unavailable -> CatalogMessageKind.Unknown
            else -> CatalogMessageKind.Failed
        }
        mutable.value = mutable.value.copy(dialog = null, message = message(kind, category.name))
        if (kind != CatalogMessageKind.Failed && kind != CatalogMessageKind.Denied) {
            mutable.value = mutable.value.copy(busyId = null); refresh()
        }
    }

    /** Called by the editor after the repository confirmed a save. */
    fun applySaved(category: Category, created: Boolean) {
        val exists = mutable.value.all.any { it.categoryId == category.categoryId }
        mutable.value = mutable.value.copy(
            all = if (exists) mutable.value.all.map { if (it.categoryId == category.categoryId) category else it } else mutable.value.all + category,
            message = message(if (created) CatalogMessageKind.Added else CatalogMessageKind.Updated, category.name))
    }

    fun toggleReorder() { mutable.value = mutable.value.copy(reorder = !mutable.value.reorder) }
    fun move(type: TransactionType, categoryId: Int, delta: Int) {
        val ids = everything(type).map { it.categoryId }.toMutableList()
        val i = ids.indexOf(categoryId); val j = i + delta
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        presentation.setOrder(type, ids)
    }

    fun consumeMessage(id: Long) { if (mutable.value.message?.id == id) mutable.value = mutable.value.copy(message = null) }
    private fun message(kind: CatalogMessageKind, name: String) = CatalogMessage(kind, name, ++messages)

    private fun expire() {
        if (mutable.value.invalidated) return
        invalidate(); onSessionExpired()
    }
    fun invalidate() {
        load?.cancel(); action?.cancel()
        mutable.value = CategoryCatalogState(loading = false, invalidated = true)
    }
}

enum class NameProblem { Required, TooLong, Duplicate }
enum class EditorError { Failed, Unknown, NameConflict, TypeConflict, Denied }

data class CategoryEditorState(
    val editingId: Int?,
    val type: TransactionType,
    val name: String,
    val iconKey: String,
    val colorHex: String?,
    /** Shown when editing a category whose stored colour is outside the curated palette. */
    val customHex: String?,
    val fallbackHex: String,
    val nameTouched: Boolean = false,
    val saving: Boolean = false,
    val error: EditorError? = null,
    val discardConfirmation: Boolean = false,
    val closeRequested: Boolean = false,
    val saved: Category? = null,
    val initial: List<Any?>,
) {
    val isEdit get() = editingId != null
    val displayHex get() = colorHex ?: fallbackHex
    val dirty get() = listOf(name.trim(), type, iconKey, colorHex) != initial
}

class CategoryEditorViewModel(
    private val writes: CategoryWriteRepository,
    private val presentation: CategoryPresentationStore,
    private val existing: Category?,
    initialType: TransactionType,
    initialIcon: String?,
    /** Loaded categories, for the same duplicate-name rule the API applies (same type, trimmed, case-insensitive). */
    private val siblings: List<Category>,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    init { require(existing == null || !existing.isSystemDefault) { "System defaults are administrator-managed" } }
    private val mutable = MutableStateFlow(run {
        val type = existing?.type ?: initialType
        val name = existing?.name.orEmpty()
        val icon = initialIcon ?: (if (existing != null) CategoryVisuals.defaultIconKey(existing.name, existing.type) else "tag")
        val color = existing?.colorHex?.uppercase() ?: if (existing == null) CategoryVisuals.palette.first().hex else null
        val custom = color?.takeIf { hex -> CategoryVisuals.palette.none { it.hex == hex } }
        val fallback = existing?.let { CategoryVisuals.displayHex(it) } ?: CategoryVisuals.palette.first().hex
        CategoryEditorState(existing?.categoryId, type, name, icon, color, custom, fallback, initial = listOf(name.trim(), type, icon, color))
    })
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun nameProblem(s: CategoryEditorState = mutable.value): NameProblem? {
        val n = s.name.trim()
        return when {
            n.isEmpty() -> NameProblem.Required
            n.length > CATEGORY_NAME_MAX -> NameProblem.TooLong
            siblings.any { it.categoryId != s.editingId && it.type == s.type && categoryNamesMatch(it.name, n) } -> NameProblem.Duplicate
            else -> null
        }
    }

    private fun editable() = !mutable.value.saving && mutable.value.saved == null && !mutable.value.closeRequested

    fun name(value: String) {
        if (editable() && value.length <= CATEGORY_NAME_MAX + 10) mutable.value = mutable.value.copy(name = value, nameTouched = true,
            error = mutable.value.error.takeUnless { it == EditorError.NameConflict })
    }
    /** Type is fixed after creation (the API also refuses type changes for categories in use). */
    fun type(value: TransactionType) { if (editable() && !mutable.value.isEdit) mutable.value = mutable.value.copy(type = value, error = null) }
    fun icon(key: String) { if (editable() && CategoryVisuals.isKnown(key)) mutable.value = mutable.value.copy(iconKey = key) }
    fun color(hex: String) {
        if (editable() && (CategoryVisuals.palette.any { it.hex == hex } || hex == mutable.value.customHex)) mutable.value = mutable.value.copy(colorHex = hex)
    }

    fun save() {
        if (!editable()) return
        val s = mutable.value.copy(nameTouched = true)
        mutable.value = s
        if (nameProblem(s) != null || !isSessionCurrent()) { if (!isSessionCurrent()) expire(); return }
        // Icon-only change on an existing category: nothing the API stores has changed, so no request is sent.
        if (existing != null && s.name.trim() == existing.name && s.colorHex == existing.colorHex?.uppercase()) {
            presentation.setIcon(existing.categoryId, s.iconKey)
            mutable.value = s.copy(saved = existing)
            return
        }
        val draft = CategoryDraft(s.name, s.type, s.colorHex, isActive = existing?.isActive ?: true)
        mutable.value = s.copy(saving = true, error = null)
        job = viewModelScope.launch {
            val result = try { if (existing == null) writes.create(draft) else writes.update(existing.categoryId, draft) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }
            if (!isSessionCurrent()) { expire(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> {
                    presentation.setIcon(result.value.categoryId, mutable.value.iconKey)
                    mutable.value.copy(saving = false, saved = result.value)
                }
                is FinanceResult.Failure -> {
                    if (result.reason.isSession()) { expire(); return@launch }
                    mutable.value.copy(saving = false, error = when (result.reason) {
                        FinanceFailure.Conflict -> if (existing != null && existing.type != draft.type) EditorError.TypeConflict else EditorError.NameConflict
                        FinanceFailure.AccessDenied -> EditorError.Denied
                        // A create may have been applied even though no confirmation arrived.
                        FinanceFailure.Timeout, FinanceFailure.InvalidResponse, FinanceFailure.Unavailable -> EditorError.Unknown
                        else -> EditorError.Failed
                    })
                }
            }
        }
    }

    fun back() {
        val s = mutable.value
        if (s.saving) return
        mutable.value = if (s.dirty && s.saved == null) s.copy(discardConfirmation = true) else s.copy(closeRequested = true)
    }
    fun keepEditing() { mutable.value = mutable.value.copy(discardConfirmation = false) }
    fun discard() { job?.cancel(); mutable.value = mutable.value.copy(discardConfirmation = false, closeRequested = true) }

    private fun expire() { job?.cancel(); mutable.value = mutable.value.copy(saving = false, closeRequested = true); onSessionExpired() }
}
