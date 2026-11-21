package com.elect3.mobdev.personalfinance.data.local

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide theme choice. System follows the phone setting (the previous, default behaviour). */
enum class AppearancePreference { Light, Dark, System }

/** Theme preference only, in app-private SharedPreferences on this device. Holds no account or financial data. */
class AppearanceStore(private val prefs: SharedPreferences?) {
    private val mutable = MutableStateFlow(read())
    val preference: StateFlow<AppearancePreference> = mutable.asStateFlow()

    private fun read(): AppearancePreference = prefs?.getString(KEY, null)
        ?.let { v -> AppearancePreference.entries.firstOrNull { it.name == v } } ?: AppearancePreference.System

    fun set(value: AppearancePreference) {
        prefs?.edit()?.putString(KEY, value.name)?.apply()
        mutable.value = value
    }

    companion object {
        const val FILE = "owli_appearance"
        private const val KEY = "theme"
    }
}
