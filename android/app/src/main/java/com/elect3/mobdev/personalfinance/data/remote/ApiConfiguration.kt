package com.elect3.mobdev.personalfinance.data.remote

import java.net.URI
import java.net.URL

/** No endpoint is selected by default. Supplying one requires separate environment approval. */
class ApiConfiguration private constructor(private val base: URI?) {
    internal fun authUrl(operation: String): URL? {
        require(operation == "login" || operation == "logout")
        return base?.resolve("api/auth/$operation")?.toURL()
    }

    companion object {
        fun unconfigured() = ApiConfiguration(null)

        fun https(baseUrl: String): ApiConfiguration {
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "HTTPS endpoint required" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Endpoint must not contain credentials, query, or fragment"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid endpoint port" }
            require(uri.path.isNullOrEmpty() || uri.path.endsWith('/')) { "Base path must end with /" }
            return ApiConfiguration(uri)
        }
    }
}
