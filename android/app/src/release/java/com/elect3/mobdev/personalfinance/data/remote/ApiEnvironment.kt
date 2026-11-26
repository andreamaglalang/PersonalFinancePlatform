package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.BuildConfig

/** Release builds use only the build-time deployment endpoint (no development endpoint or transport diagnostic). */
internal object ApiEnvironment {
    fun configuration(): ApiConfiguration = fromBuildValue(BuildConfig.OWLI_API_BASE_URL)

    /** Blank or invalid → unconfigured (the app shows its existing unconfigured state; nothing is sent). */
    internal fun fromBuildValue(value: String): ApiConfiguration =
        value.takeIf { it.isNotBlank() }?.let { runCatching { ApiConfiguration.https(it) }.getOrNull() }
            ?: ApiConfiguration.unconfigured()

    @Suppress("UNUSED_PARAMETER")
    suspend fun checkConnectionIfRequested(requested: Boolean) = Unit
}
