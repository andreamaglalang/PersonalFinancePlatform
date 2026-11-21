package com.elect3.mobdev.personalfinance.ui.categories

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category

/** One icon of the approved category icon family (24dp line icons from the Owlight extension mockup). */
data class CategoryIcon(val key: String, @DrawableRes val drawable: Int, @StringRes val label: Int)
data class CategoryIconGroup(@StringRes val title: Int, val icons: List<CategoryIcon>)
data class CategorySwatch(val hex: String, @StringRes val name: Int) { val color: Color get() = categoryColor(hex) }

object CategoryVisuals {
    val groups: List<CategoryIconGroup> = listOf(
        CategoryIconGroup(R.string.cat_group_food_drinks, listOf(
            CategoryIcon("food", R.drawable.ic_cat_food, R.string.cat_icon_food),
            CategoryIcon("coffee", R.drawable.ic_cat_coffee, R.string.cat_icon_coffee),
            CategoryIcon("cocktail", R.drawable.ic_cat_cocktail, R.string.cat_icon_cocktail),
            CategoryIcon("apple", R.drawable.ic_cat_apple, R.string.cat_icon_apple))),
        CategoryIconGroup(R.string.cat_group_shopping, listOf(
            CategoryIcon("cart", R.drawable.ic_cat_cart, R.string.cat_icon_cart),
            CategoryIcon("bag", R.drawable.ic_cat_bag, R.string.cat_icon_bag),
            CategoryIcon("shirt", R.drawable.ic_cat_shirt, R.string.cat_icon_shirt),
            CategoryIcon("gift", R.drawable.ic_cat_gift, R.string.cat_icon_gift),
            CategoryIcon("scissors", R.drawable.ic_cat_scissors, R.string.cat_icon_scissors))),
        CategoryIconGroup(R.string.cat_group_transport, listOf(
            CategoryIcon("bus", R.drawable.ic_cat_bus, R.string.cat_icon_bus),
            CategoryIcon("car", R.drawable.ic_cat_car, R.string.cat_icon_car),
            CategoryIcon("plane", R.drawable.ic_cat_plane, R.string.cat_icon_plane),
            CategoryIcon("fuel", R.drawable.ic_cat_fuel, R.string.cat_icon_fuel))),
        CategoryIconGroup(R.string.cat_group_home, listOf(
            CategoryIcon("house", R.drawable.ic_cat_house, R.string.cat_icon_house),
            CategoryIcon("sofa", R.drawable.ic_cat_sofa, R.string.cat_icon_sofa),
            CategoryIcon("bolt", R.drawable.ic_cat_bolt, R.string.cat_icon_bolt),
            CategoryIcon("drop", R.drawable.ic_cat_drop, R.string.cat_icon_drop),
            CategoryIcon("wifi", R.drawable.ic_cat_wifi, R.string.cat_icon_wifi))),
        CategoryIconGroup(R.string.cat_group_work, listOf(
            CategoryIcon("brief", R.drawable.ic_cat_brief, R.string.cat_icon_brief),
            CategoryIcon("trend", R.drawable.ic_cat_trend, R.string.cat_icon_trend),
            CategoryIcon("wallet", R.drawable.ic_cat_wallet, R.string.cat_icon_wallet),
            CategoryIcon("card", R.drawable.ic_cat_card, R.string.cat_icon_card),
            CategoryIcon("coins", R.drawable.ic_cat_coins, R.string.cat_icon_coins),
            CategoryIcon("trophy", R.drawable.ic_cat_trophy, R.string.cat_icon_trophy))),
        CategoryIconGroup(R.string.cat_group_education, listOf(
            CategoryIcon("cap", R.drawable.ic_cat_cap, R.string.cat_icon_cap),
            CategoryIcon("book", R.drawable.ic_cat_book, R.string.cat_icon_book),
            CategoryIcon("edit", R.drawable.ic_cat_edit, R.string.cat_icon_edit))),
        CategoryIconGroup(R.string.cat_group_health, listOf(
            CategoryIcon("heart", R.drawable.ic_cat_heart, R.string.cat_icon_heart),
            CategoryIcon("pulse", R.drawable.ic_cat_pulse, R.string.cat_icon_pulse),
            CategoryIcon("pill", R.drawable.ic_cat_pill, R.string.cat_icon_pill),
            CategoryIcon("dumbbell", R.drawable.ic_cat_dumbbell, R.string.cat_icon_dumbbell))),
        CategoryIconGroup(R.string.cat_group_entertainment, listOf(
            CategoryIcon("gamepad", R.drawable.ic_cat_gamepad, R.string.cat_icon_gamepad),
            CategoryIcon("music", R.drawable.ic_cat_music, R.string.cat_icon_music),
            CategoryIcon("film", R.drawable.ic_cat_film, R.string.cat_icon_film),
            CategoryIcon("headphones", R.drawable.ic_cat_headphones, R.string.cat_icon_headphones))),
        CategoryIconGroup(R.string.cat_group_nature, listOf(
            CategoryIcon("paw", R.drawable.ic_cat_paw, R.string.cat_icon_paw),
            CategoryIcon("leaf", R.drawable.ic_cat_leaf, R.string.cat_icon_leaf),
            CategoryIcon("sun", R.drawable.ic_cat_sun, R.string.cat_icon_sun))),
        CategoryIconGroup(R.string.cat_group_general, listOf(
            CategoryIcon("star", R.drawable.ic_cat_star, R.string.cat_icon_star),
            CategoryIcon("users", R.drawable.ic_cat_users, R.string.cat_icon_users),
            CategoryIcon("phone", R.drawable.ic_cat_phone, R.string.cat_icon_phone),
            CategoryIcon("laptop", R.drawable.ic_cat_laptop, R.string.cat_icon_laptop),
            CategoryIcon("tag", R.drawable.ic_cat_tag, R.string.cat_icon_tag),
            CategoryIcon("dots", R.drawable.ic_cat_dots, R.string.cat_icon_dots))),
    )
    private val byKey = groups.flatMap { it.icons }.associateBy { it.key }
    fun icon(key: String?): CategoryIcon = key?.let { byKey[it] } ?: byKey.getValue("tag")
    fun isKnown(key: String) = key in byKey

    /** Curated muted palette from the extension mockup; white icons keep at least 4:1 contrast on each. */
    val palette: List<CategorySwatch> = listOf(
        CategorySwatch("#6747C7", R.string.cat_color_violet), CategorySwatch("#7E62D8", R.string.cat_color_lavender),
        CategorySwatch("#A55AA9", R.string.cat_color_orchid), CategorySwatch("#C9577A", R.string.cat_color_rose),
        CategorySwatch("#CC5F49", R.string.cat_color_coral), CategorySwatch("#B8691F", R.string.cat_color_amber),
        CategorySwatch("#93791A", R.string.cat_color_ochre), CategorySwatch("#667F2A", R.string.cat_color_olive),
        CategorySwatch("#2F8A5B", R.string.cat_color_green), CategorySwatch("#1F8A82", R.string.cat_color_teal),
        CategorySwatch("#2F7FB0", R.string.cat_color_sky), CategorySwatch("#4468C9", R.string.cat_color_blue),
        CategorySwatch("#5F6F9A", R.string.cat_color_slate), CategorySwatch("#8F6D5C", R.string.cat_color_clay),
        CategorySwatch("#74508F", R.string.cat_color_plum),
    )

    /** Display-only fallback for categories without a stored colour. Never written back to the server. */
    fun displayHex(category: Category): String = category.colorHex?.uppercase()
        ?: palette[Math.floorMod(category.categoryId, palette.size)].hex

    /**
     * Display-only default icon for a category with no device-saved icon, chosen from its name.
     * Nothing is stored or sent; the user may pick another icon, which is then saved on this device.
     */
    fun defaultIconKey(name: String, type: TransactionType): String {
        val n = name.lowercase()
        val rules = listOf(
            // Checked first: "healthcare" contains "car".
            listOf("health", "medic", "doctor", "pharm") to "heart", listOf("fitness", "gym") to "dumbbell",
            listOf("grocer") to "apple", listOf("coffee", "cafe") to "coffee", listOf("drink", "bar") to "cocktail",
            listOf("food", "meal", "dining", "restaurant", "lunch") to "food",
            listOf("transport", "commute", "bus", "fare") to "bus", listOf("car", "parking") to "car",
            listOf("fuel", "gas") to "fuel", listOf("travel", "flight", "trip") to "plane",
            listOf("utilit", "electric", "power") to "bolt", listOf("water") to "drop", listOf("internet", "wifi") to "wifi",
            listOf("rent", "hous", "home") to "house", listOf("furni") to "sofa",
            listOf("shop") to "cart", listOf("cloth", "apparel") to "shirt", listOf("gift") to "gift",
            listOf("personal care", "salon", "groom") to "scissors",
            listOf("educat", "school", "tuition") to "cap", listOf("book") to "book",
            listOf("entertain", "game") to "gamepad", listOf("music") to "music", listOf("movie", "film") to "film",
            listOf("pet") to "paw", listOf("phone", "mobile") to "phone", listOf("electronic", "gadget", "laptop") to "laptop",
            listOf("salary", "wage", "payroll") to "brief", listOf("freelance") to "edit", listOf("business") to "wallet",
            listOf("invest", "dividend", "interest") to "trend", listOf("bonus") to "trophy", listOf("allowance") to "coins",
        )
        rules.firstOrNull { (words, _) -> words.any { n.contains(it) } }?.let { return it.second }
        return if (n.startsWith("other")) "dots" else if (type == TransactionType.Income) "coins" else "tag"
    }
}

fun categoryColor(hex: String): Color {
    require(hex.matches(Regex("#[0-9A-Fa-f]{6}")))
    return Color(0xFF000000L or hex.substring(1).toLong(16))
}
