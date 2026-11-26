package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.elect3.mobdev.personalfinance.domain.model.TransactionType

/**
 * Navigation-level actions offered by the app shell to its destinations (Dashboard quick actions).
 * Both are null when unavailable, in which case destinations hide the related controls.
 * [addTransaction] opens the existing Add Transaction dialog, optionally preselecting a type; it is
 * supplied only where the owner already allows adding (Mock Demo, or a signed-in account with categories).
 */
@Immutable
data class OwliShellActions(
    val addTransaction: ((TransactionType?) -> Unit)? = null,
    val openActivity: (() -> Unit)? = null,
    /** Opens Activity and asks it to show its filter sheet once. */
    val openActivityFilter: (() -> Unit)? = null,
    /** Opens Category settings (Mock Demo, or a signed-in account with categories). */
    val openCategories: (() -> Unit)? = null,
    /** Opens the Account screen from the Dashboard avatar. */
    val openAccount: (() -> Unit)? = null,
    /** Opens Insights (Financial Reports); null while the owner provides no Insights content. */
    val openInsights: (() -> Unit)? = null,
    /** Opens the notifications inbox (signed in only). */
    val openNotifications: (() -> Unit)? = null,
)

val LocalOwliShellActions = staticCompositionLocalOf { OwliShellActions() }

/** One-shot request from the shell for the Activity screen to open its filter sheet. */
@Immutable
data class OwliFilterRequest(val pending: Boolean = false, val consume: () -> Unit = {})

val LocalOwliFilterRequest = androidx.compose.runtime.compositionLocalOf { OwliFilterRequest() }
