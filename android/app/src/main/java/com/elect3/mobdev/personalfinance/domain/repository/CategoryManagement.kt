package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.TransactionType

/** Backend name limit (CategoryWriteRequest.Name, max 50 after trimming). */
const val CATEGORY_NAME_MAX = 50

/**
 * Exactly the fields of the existing API CategoryWriteRequest: Name, Type, ColorHEX, IsActive.
 * The API has no icon or display-order fields, so neither is ever sent.
 */
data class CategoryDraft(
    val name: String,
    val type: TransactionType,
    val colorHex: String?,
    val isActive: Boolean = true,
) {
    init {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= CATEGORY_NAME_MAX) { "Invalid category name" }
        require(colorHex == null || colorHex.matches(Regex("#[0-9A-Fa-f]{6}"))) { "Invalid colour" }
    }
    val trimmedName: String get() = name.trim()
}

/**
 * Member category writes (api/categories). System defaults are administrator-managed on the server
 * and must never be offered for editing. Conflict means a duplicate name, a type change on a used
 * category, or a delete of a category that transactions reference.
 */
interface CategoryWriteRepository {
    suspend fun create(draft: CategoryDraft): FinanceResult<Category>
    suspend fun update(categoryId: Int, draft: CategoryDraft): FinanceResult<Category>
    suspend fun delete(categoryId: Int): FinanceResult<Unit>
}

/** Same-type names compare trimmed and case-insensitively, like the API's unique index. */
fun categoryNamesMatch(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
