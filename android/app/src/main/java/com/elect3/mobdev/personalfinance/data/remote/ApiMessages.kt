package com.elect3.mobdev.personalfinance.data.remote

import com.google.gson.JsonParser

/**
 * A specific, safe reason for a rejected request. Server text is never shown as-is: only messages in this reviewed
 * allowlist (the same list the MVC site uses) and the server's password-requirement sentence, rebuilt from known
 * parts, are returned, in the app's wording. Anything else returns null, so screens keep their generic message.
 */
internal object ApiMessages {
    private const val MAX_BODY = 16 * 1024

    private val known = mapOf(
        "Enter an amount greater than zero." to "Enter an amount greater than zero.",
        "Amounts can have no more than two decimal places." to "Amounts can have no more than two decimal places.",
        "That amount is too large." to "That amount is too large.",
        "The converted amount exceeds the supported monetary range." to "The converted amount is too large.",
        "The currency is not supported by the exchange-rate provider." to "That currency isn't supported for conversion.",
        "The requested currency or historical rate is unavailable from the provider." to "No exchange rate is available for that currency and date.",
        "An active, accessible expense category is required." to "Choose an active expense category.",
        "A category with the same name and type already exists." to "A category with this name and type already exists. Choose a different name.",
        "A budget already exists for this category and month." to "A budget already exists for this category and month.",
        "Contribution dates cannot be later than today (Philippine time)." to "Contribution dates can't be later than today.",
        "A goal with protected financial history cannot be deleted." to "This goal has contributions, so it can't be deleted.",
        "Cancelled goals are financially immutable." to "Cancelled goals can't be changed.",
        "This bill has payment history and cannot be deleted. Deactivate it instead." to "This bill has payment history and can't be deleted. Deactivate it instead.",
        "Bill payments require a controlled reversal and cannot be deleted." to "Bill payments can't be deleted.",
        "Only the oldest outstanding occurrence may be paid." to "This due date was already paid or is not the next one due, for example after a payment in the OWLI app. Nothing was paid. Reload the bill and check.",
        "This occurrence already has payment history." to "This due date has already been paid, for example in the OWLI app. Nothing was paid again.",
        "Future occurrences cannot be paid." to "This due date has not arrived yet, so it can't be paid.",
        "Inactive bills cannot accept new payments." to "This bill is inactive. Activate it before recording a payment.",
        "The operation conflicts with existing related records." to "This record is linked to other records, so this change isn't allowed.",
        "This transaction was changed elsewhere. Reload it and try again." to "This transaction was updated elsewhere. Reload before editing.",
        "Transaction changed. Reload and retry." to "This transaction was updated elsewhere. Reload before editing.",
        "Transaction changed during the request. Reload and retry." to "This transaction was updated elsewhere. Reload before editing.",
        "Budget changed during the request. Reload and retry." to "This budget was updated elsewhere. Reload and try again.",
        "The bill changed during the request. Reload and retry." to "This bill was updated elsewhere. Reload and try again.",
        "Contribution history changed. Reload and retry." to "This goal's contributions were updated elsewhere. Reload and try again.",
        "Profile changed. Reload before saving." to "Your profile was updated elsewhere. Reload before saving.",
        "ClientId has already been accepted with a different contribution payload." to "This contribution was already recorded with different details. Reload and check before trying again.",
        "This ClientId was already used for different transaction data." to "This transaction was already recorded with different details. Reload and check before trying again.",
        "Your current password is incorrect." to "Your current password is incorrect.",
        "Choose a new password that is different from your current password." to "Choose a new password that is different from your current password.",
        "Passwords do not match." to "Passwords do not match.",
        "Enter a valid email address." to "Enter a valid email address.",
        "Enter your full name." to "Enter your full name.",
        "Enter your full name (up to 100 characters)." to "Enter your full name (up to 100 characters).",
    )

    private val passwordParts = listOf(
        "at least 12 characters", "no more than 128 characters", "an uppercase letter", "a lowercase letter",
        "a number", "a special character, such as ! or #", "at least 4 different characters",
    )

    fun translate(message: String?): String? = message?.let { known[it] ?: it.takeIf(::isPasswordRequirements) }

    /** "Password must contain A, B and C." where every part is a known requirement, each at most once. */
    fun isPasswordRequirements(message: String): Boolean {
        val prefix = "Password must contain "
        if (!message.startsWith(prefix) || !message.endsWith(".")) return false
        var body = message.substring(prefix.length, message.length - 1)
        val seen = mutableSetOf<String>()
        while (body.isNotEmpty()) {
            val part = passwordParts.filter { body.startsWith(it) }.maxByOrNull { it.length } ?: return false
            if (!seen.add(part)) return false
            body = body.removePrefix(part)
            body = when {
                body.startsWith(", ") -> body.removePrefix(", ")
                body.startsWith(" and ") -> body.removePrefix(" and ")
                body.isEmpty() -> body
                else -> return false
            }
        }
        return seen.isNotEmpty()
    }

    /** Known messages from a ProblemDetails / ValidationProblemDetails body, joined; null when none are known. */
    fun read(body: String?): String? {
        if (body.isNullOrBlank() || body.length > MAX_BODY) return null
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val messages = mutableListOf<String>()
            root.get("errors")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (_, value) ->
                if (value.isJsonArray) value.asJsonArray.forEach { item ->
                    if (item.isJsonPrimitive && item.asJsonPrimitive.isString) translate(item.asString)?.let(messages::add)
                }
            }
            root.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { translate(it.asString) }?.let(messages::add)
            messages.distinct().joinToString(" ").ifEmpty { null }
        }.getOrNull()
    }
}
