package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.local.InMemoryPendingRequestStore
import com.elect3.mobdev.personalfinance.data.local.PendingRequestStore
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.net.URL

/** Online only. Shares the existing session lease, TLS, bounded responses and no-retry transport. */
class LiveAccountPlanRepository internal constructor(
    private val configuration: ApiConfiguration,
    private val auth: InMemoryAuthRepository,
    engine: FinanceHttpEngine,
    writeEngine: FinanceWriteEngine,
    private val pendingStore: PendingRequestStore = InMemoryPendingRequestStore(),
) {
    constructor(configuration: ApiConfiguration, auth: InMemoryAuthRepository, pendingStore: PendingRequestStore = InMemoryPendingRequestStore()) :
        this(configuration, auth, PlatformFinanceHttpEngine(), PlatformFinanceWriteEngine(), pendingStore)
    private val reader = AuthenticatedFinanceTransport(configuration, auth, engine)
    private val writer = FinanceWriter(auth, writeEngine)
    private val mutationLock = kotlinx.coroutines.sync.Mutex()
    /** Endpoint + account: another signed-in user never sees or replays these records. */
    private fun namespace(): String? = auth.accountId()?.let { "${configuration.sessionNamespace()}#$it" }
    /** The persisted, uncertain contribution or payment for [path]. It is never evidence of success. */
    suspend fun pendingRequest(path: String): JsonObject? {
        val namespace = namespace() ?: return null
        return pendingStore.find(namespace, path)?.let { runCatching { json(it) }.getOrNull() }
    }
    /** Paths of every uncertain contribution or payment of the signed-in account. */
    suspend fun pendingPaths(): Set<String> = namespace()?.let { pendingStore.paths(it).toSet() }.orEmpty()
    private fun json(body: String): JsonObject {
        val input = JsonReader(StringReader(body)); input.isLenient = false
        val value = JsonParser.parseReader(input)
        require(input.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT && value.isJsonObject)
        return value.asJsonObject
    }
    private fun checked(path: String, root: JsonObject, page: Boolean): JsonObject {
        fun text(o: JsonObject, key: String) = o.get(key).also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
        fun money(o: JsonObject, key: String) { require(o.get(key).asBigDecimal.signum() >= 0) }
        fun date(o: JsonObject, key: String) { require(java.time.LocalDate.parse(text(o, key)).year in 1..9999) }
        fun currency(o: JsonObject, key: String) { require(text(o, key).matches(Regex("[A-Z]{3}"))) }
        fun row(o: JsonObject, contribution: Boolean) {
            require(o.get(if (contribution) "contributionId" else if (path.startsWith("recurring-bills")) "billId" else "goalId").asInt > 0)
            if (contribution) {
                money(o, "amount"); date(o, "contributedOn"); currency(o, "baseCurrency"); text(o, "version")
            } else if (path.startsWith("recurring-bills")) {
                require(text(o, "name").isNotBlank()); money(o, "amount"); date(o, "nextDueDate"); date(o, "firstDueDate")
                currency(o, "currencyCode"); require(o.get("categoryId").asInt > 0); text(o, "categoryName")
                require(text(o, "frequency") in setOf("Weekly", "Monthly", "Quarterly", "Yearly"))
                require(o.get("reminderDaysBefore").asInt in 0..30); require(o.get("isActive").asJsonPrimitive.isBoolean)
            } else {
                require(text(o, "name").isNotBlank()); currency(o, "baseCurrency"); text(o, "version")
                listOf("targetAmount", "savedAmount", "surplusAmount", "progressPercentage").forEach { money(o, it) }
                // Remaining is target minus saved, so it is negative once a goal is over-funded (the surplus is reported separately).
                require(o.get("remainingAmount").asBigDecimal.compareTo(o.get("targetAmount").asBigDecimal.subtract(o.get("savedAmount").asBigDecimal)) == 0)
                require(text(o, "status") in setOf("Active", "Completed", "Cancelled"))
                if (o.get("targetDate")?.isJsonNull == false) date(o, "targetDate")
            }
        }
        if (page) {
            require(root.get("page").asInt > 0 && root.get("pageSize").asInt in 1..100 && root.get("totalCount").asLong >= 0)
            val items = root.getAsJsonArray("items"); require(items.size() <= root.get("pageSize").asInt)
            items.forEach { row(it.asJsonObject, path.endsWith("/contributions")) }
        } else if (path == "account/profile") {
            require(root.get("id").asInt == auth.accountId())
            val profile = auth.accountProfile()
            require(text(root, "email").equals(profile?.email, true) && text(root, "baseCurrency") == profile?.baseCurrency)
            require(text(root, "fullName").isNotBlank() && text(root, "version").matches(Regex("[A-F0-9]{64}")))
        } else if (root.size() != 0) {
            when {
                path.endsWith("/payments") -> {
                    val receipt = root.getAsJsonObject("receipt"); java.util.UUID.fromString(text(receipt, "clientId"))
                    require(receipt.get("billId").asInt > 0); date(receipt, "dueDate"); money(receipt, "amount"); currency(receipt, "currencyCode")
                }
                path.endsWith("/contributions") -> {
                    val receipt = root.getAsJsonObject("receipt"); java.util.UUID.fromString(text(receipt, "clientId"))
                    require(receipt.get("goalId").asInt > 0); date(receipt, "originalContributedOn"); money(receipt, "originalAmount")
                    require(root.get("deleted").asJsonPrimitive.isBoolean)
                }
                root.has("goalId") && root.has("targetAmount") -> row(root, false)
                root.has("billId") -> row(root, false)
            }
        }
        return root
    }
    suspend fun get(path: String): FinanceResult<JsonObject> = reader.getUrl(configuration.memberUrl(path)) { checked(path, json(it), false) }
    /** A page is displayed explicitly. No account-spanning pagination or cached financial records. */
    suspend fun page(path: String, page: Int): FinanceResult<JsonObject> {
        require(page in 1..100000)
        val url = configuration.memberUrl(path)?.let { URL(it.toString() + "?page=$page&pageSize=50" + if (path == "recurring-bills") "&includeInactive=true" else "") }
        return reader.getUrl(url) { checked(path, json(it), true) }
    }
    suspend fun save(path: String, body: JsonObject?, update: Boolean = false, delete: Boolean = false, version: String? = null): FinanceResult<JsonObject> {
        val tag = version?.let {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
            if (bytes == null || bytes.size != 8 || java.util.Base64.getEncoder().encodeToString(bytes) != it)
                return FinanceResult.Failure(FinanceFailure.InvalidResponse)
            "\"$it\""
        }
        val financial = !update && !delete && (path.endsWith("/contributions") || path.endsWith("/payments"))
        if (financial && !mutationLock.tryLock()) return FinanceResult.Failure(FinanceFailure.Conflict)
        val namespace = namespace()
        try {
            var payload = body?.toString()
            if (financial) {
                if (namespace == null || body == null) return FinanceResult.Failure(FinanceFailure.MissingSession)
                val previous = try { pendingStore.find(namespace, path) } catch (_: Exception) { return FinanceResult.Failure(FinanceFailure.Unavailable) }
                if (previous != null && runCatching { json(previous) }.getOrNull() != body) return FinanceResult.Failure(FinanceFailure.Conflict)
                // Persist the identity and exact payload before the request can leave the device; never send unpersisted.
                if (previous == null) try { pendingStore.save(namespace, path, payload!!) } catch (_: Exception) { return FinanceResult.Failure(FinanceFailure.Unavailable) }
                payload = previous ?: payload
            }
        val result = writer.sendVersioned(if (delete) WriteMethod.DELETE else if (update) WriteMethod.PUT else WriteMethod.POST,
            configuration.memberUrl(path), payload, if (delete || path == "account/change-password") setOf(204) else setOf(200, 201), tag) {
                if (it.isBlank()) {
                    require(delete || path == "account/change-password"); JsonObject()
                } else checked(path, json(it), false).also { root ->
                    if (financial) {
                        val receipt = root.getAsJsonObject("receipt")
                        require(receipt.get("clientId").asString.equals(body!!.get("clientId").asString, true))
                        val id = path.split('/')[1].toInt()
                        if (path.endsWith("/payments")) {
                            require(receipt.get("billId").asInt == id && receipt.get("dueDate").asString == body.get("dueDate").asString)
                        } else {
                            require(receipt.get("goalId").asInt == id && receipt.get("originalContributedOn").asString == body.get("contributedOn").asString)
                            require(receipt.get("originalAmount").asBigDecimal.compareTo(body.get("amount").asBigDecimal) == 0)
                            require(receipt.get("originalNote") == body.get("note"))
                        }
                    }
                }
            }
        // Cleared only on a definitive answer; transport failures, 5xx, 401 and 429 keep the identity for retry.
        if (financial && (result is FinanceResult.Success || (result is FinanceResult.Failure && result.reason in definitiveRejections)))
            runCatching { pendingStore.remove(namespace!!, path) } // A leftover record only causes a harmless server replay.
        return result
        } finally { if (financial) mutationLock.unlock() }
    }
    suspend fun changePassword(current: String, password: String, confirmation: String): FinanceResult<JsonObject> {
        val marker = auth.sessionMarker()
        val result = save("account/change-password", fields("currentPassword" to current, "newPassword" to password, "confirmPassword" to confirmation))
        // Success revokes all families server-side. Unknown outcomes must never be resent automatically.
        if (result is FinanceResult.Success || (result is FinanceResult.Failure && result.reason in uncertainFailures)) auth.clearIfCurrent(marker)
        return result
    }
    suspend fun updateProfile(name: String, version: String): FinanceResult<JsonObject> {
        val marker = auth.sessionMarker()
        val result = save("account/profile", fields("fullName" to name.trim(), "version" to version), update = true)
        if (result is FinanceResult.Success) auth.updateDisplayName(marker, result.value.get("fullName").asString)
        return result
    }
}
/** 400/403/404/409: the server permanently rejected this request, so its identity is not reused. */
internal val definitiveRejections = setOf(FinanceFailure.InvalidInput, FinanceFailure.AccessDenied, FinanceFailure.NotFound, FinanceFailure.Conflict)
internal val uncertainFailures = setOf(FinanceFailure.Timeout, FinanceFailure.Unavailable, FinanceFailure.InvalidResponse, FinanceFailure.SecureConnection)
internal fun fields(vararg values: Pair<String, Any?>) = JsonObject().apply {
    values.forEach { (key, value) -> when (value) {
        null -> add(key, JsonNull.INSTANCE)
        is Number -> addProperty(key, value)
        is Boolean -> addProperty(key, value)
        else -> addProperty(key, value.toString())
    } }
}
/** [account] marks account (profile/password) writes; plan writes never mention passwords. */
internal fun liveFailure(reason: FinanceFailure, writing: Boolean = false, account: Boolean = false, message: String? = null): String =
    message?.takeIf { reason in definitiveRejections } ?: when (reason) {
    FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> "Your session has expired. Please sign in again."
    FinanceFailure.InvalidInput -> if (account) "These details weren't accepted. Check each field and try again."
        else "Check your entries. A value was not accepted, for example a date later than today."
    FinanceFailure.Conflict -> "This record changed or has financial history. Reload before editing."
    FinanceFailure.NotFound -> "This operation is not available on the running API, or the record no longer exists."
    FinanceFailure.AccessDenied -> "This operation is not permitted."
    FinanceFailure.RateLimited -> "Too many requests. Try again later."
    FinanceFailure.NotConfigured -> "Live API is not configured."
    else -> if (writing) "The outcome is unknown. Check your records before submitting again." else "Cannot load your account. Check the connection and API availability."
}
