package com.elect3.mobdev.personalfinance.data.remote

import java.net.URI
import java.net.URL

/** No endpoint is selected by default. Supplying one requires separate environment approval. */
class ApiConfiguration private constructor(private val base: URI?) {
    internal fun sessionNamespace(): String = base?.toASCIIString() ?: "unconfigured"

    internal fun authUrl(operation: String): URL? {
        require(operation == "login" || operation == "logout" || operation == "refresh")
        return base?.resolve("api/auth/$operation")?.toURL()
    }

    internal fun financeUrl(resource: FinanceResource, query: String): URL? =
        base?.resolve("api/${resource.path}?$query")?.toURL()

    internal fun memberUrl(path: String): URL? {
        require(path.matches(Regex("account/(profile|change-password)|savings-goals(/[1-9][0-9]*(/cancel|/contributions(/[1-9][0-9]*)?)?)?|recurring-bills(/[1-9][0-9]*(/payments)?)?")))
        return base?.resolve("api/$path")?.toURL()
    }

    internal fun transactionCreateUrl(): URL? = base?.resolve("api/transactions")?.toURL()

    /** PUT api/transactions/{id} (member edit). */
    internal fun transactionUpdateUrl(transactionId: Int): URL? {
        require(transactionId > 0)
        return base?.resolve("api/transactions/$transactionId")?.toURL()
    }

    /** api/budgets (POST) and api/budgets/{id} (PUT, DELETE). */
    internal fun budgetWriteUrl(budgetId: Int?): URL? {
        require(budgetId == null || budgetId > 0)
        return base?.resolve(if (budgetId == null) "api/budgets" else "api/budgets/$budgetId")?.toURL()
    }

    /** Member category writes only: POST api/categories, PUT/DELETE api/categories/{id}. Never the admin defaults route. */
    internal fun categoryWriteUrl(categoryId: Int?): URL? {
        require(categoryId == null || categoryId > 0)
        return base?.resolve(if (categoryId == null) "api/categories" else "api/categories/$categoryId")?.toURL()
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
