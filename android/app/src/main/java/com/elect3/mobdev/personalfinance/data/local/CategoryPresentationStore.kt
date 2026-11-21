package com.elect3.mobdev.personalfinance.data.local

import android.content.SharedPreferences
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Display-only category preferences that the API cannot store: the chosen icon and the picker order.
 * Holds only category IDs, icon keys and ID lists; never names, amounts, tokens or other account data.
 */
interface CategoryPresentationStore {
    /** Increments on every change so screens can re-read. */
    val version: StateFlow<Int>
    fun icon(categoryId: Int): String?
    fun setIcon(categoryId: Int, key: String)
    fun order(type: TransactionType): List<Int>
    fun setOrder(type: TransactionType, ids: List<Int>)
    fun forget(categoryId: Int)
}

/** Saved order first, then everything else alphabetically (then by ID) so the result is stable. */
fun List<Category>.inDisplayOrder(store: CategoryPresentationStore, type: TransactionType): List<Category> {
    val saved = store.order(type).withIndex().associate { it.value to it.index }
    return filter { it.type == type }.sortedWith(compareBy<Category>({ saved[it.categoryId] ?: Int.MAX_VALUE },
        { it.name.lowercase() }, { it.categoryId }))
}

/** Mock Demo: memory only, cleared with the demo session. */
class InMemoryCategoryPresentationStore : CategoryPresentationStore {
    private val icons = HashMap<Int, String>()
    private val orders = HashMap<TransactionType, List<Int>>()
    private val mutable = MutableStateFlow(0)
    override val version: StateFlow<Int> = mutable.asStateFlow()
    @Synchronized override fun icon(categoryId: Int) = icons[categoryId]
    @Synchronized override fun setIcon(categoryId: Int, key: String) { icons[categoryId] = key; mutable.value++ }
    @Synchronized override fun order(type: TransactionType) = orders[type].orEmpty()
    @Synchronized override fun setOrder(type: TransactionType, ids: List<Int>) { orders[type] = ids.distinct(); mutable.value++ }
    @Synchronized override fun forget(categoryId: Int) {
        icons.remove(categoryId); orders.keys.toList().forEach { t -> orders[t] = orders.getValue(t) - categoryId }; mutable.value++
    }
    @Synchronized fun clear() { icons.clear(); orders.clear(); mutable.value++ }
}

/**
 * Signed-in accounts: saved on THIS DEVICE ONLY (app-private SharedPreferences), scoped by account ID so
 * another account on the same device never sees these choices. Not synced; lost on uninstall or data clear.
 */
class DeviceCategoryPresentationStore(private val prefs: SharedPreferences, accountId: Int) : CategoryPresentationStore {
    init { require(accountId > 0) }
    private val prefix = "a$accountId."
    private val mutable = MutableStateFlow(0)
    override val version: StateFlow<Int> = mutable.asStateFlow()

    override fun icon(categoryId: Int): String? = prefs.getString("${prefix}icon.$categoryId", null)
    override fun setIcon(categoryId: Int, key: String) {
        require(categoryId > 0 && key.matches(Regex("[a-z]{2,16}")))
        prefs.edit().putString("${prefix}icon.$categoryId", key).apply(); mutable.value++
    }
    override fun order(type: TransactionType): List<Int> = prefs.getString("${prefix}order.${type.wireValue}", null)
        ?.split(',')?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }?.distinct().orEmpty()
    override fun setOrder(type: TransactionType, ids: List<Int>) {
        prefs.edit().putString("${prefix}order.${type.wireValue}", ids.filter { it > 0 }.distinct().joinToString(",")).apply(); mutable.value++
    }
    override fun forget(categoryId: Int) {
        val editor = prefs.edit().remove("${prefix}icon.$categoryId")
        TransactionType.entries.forEach { t ->
            val ids = order(t)
            if (categoryId in ids) editor.putString("${prefix}order.${t.wireValue}", (ids - categoryId).joinToString(","))
        }
        editor.apply(); mutable.value++
    }

    companion object { const val FILE = "owli_category_display" }
}
