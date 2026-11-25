package com.elect3.mobdev.personalfinance.data.local

import android.content.SharedPreferences
import com.elect3.mobdev.personalfinance.domain.currency.CurrencyPreferenceStorage

class DeviceCurrencyPreferenceStorage(private val preferences: SharedPreferences) : CurrencyPreferenceStorage {
    override fun read(key: String): String? = preferences.getString(key,null)
    override fun write(key: String,value: String): Boolean = preferences.edit().putString(key,value).commit()
    companion object { const val FILE="owli_currency_preferences" }
}
