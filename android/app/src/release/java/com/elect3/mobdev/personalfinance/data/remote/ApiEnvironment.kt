package com.elect3.mobdev.personalfinance.data.remote

/** Release builds have no development endpoint or transport diagnostic. */
internal object ApiEnvironment {
    fun configuration(): ApiConfiguration = ApiConfiguration.unconfigured()

    @Suppress("UNUSED_PARAMETER")
    suspend fun checkConnectionIfRequested(requested: Boolean) = Unit
}
