package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import com.elect3.mobdev.personalfinance.domain.repository.TransactionUpdateRepository
import com.google.gson.stream.JsonWriter
import java.io.StringWriter

internal const val MAX_UPDATE_REQUEST_BYTES = 16 * 1024

internal object TransactionUpdateJson {
    /** Exactly the API's update fields plus the C1 `version` (it rejects unknown members): no clientId on an edit. */
    fun encode(draft: TransactionDraft, version: String): String {
        require(version.matches(TRANSACTION_VERSION)) { "Invalid version" }
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.serializeNulls = true
            json.beginObject()
            json.name("type").value(draft.type.wireValue)
            json.name("categoryId").value(draft.categoryId)
            json.name("amount").value(draft.amount)
            json.name("currencyCode").value(draft.currencyCode)
            json.name("transactionDate").value(draft.transactionDate.toString())
            json.name("description").value(draft.description)
            json.name("version").value(version)
            json.endObject()
        }
        return output.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_UPDATE_REQUEST_BYTES) { "Request too large" } }
    }
}

/**
 * Member transaction edit, reload and delete over the existing api/transactions/{id} route. Session handling and status mapping are the shared
 * [FinanceWriter] rules: error bodies are never read, failures are bounded [FinanceFailure] values.
 * The response is the server's authoritative snapshot (rate, rate date, base amount); it is checked against the
 * submitted edit and never recalculated here.
 */
internal class RemoteTransactionUpdateRepository(
    private val transport: AuthenticatedFinanceTransport,
    private val configuration: ApiConfiguration,
    auth: InMemoryAuthRepository,
    engine: FinanceWriteEngine,
) : TransactionUpdateRepository {
    private val writer = FinanceWriter(auth, engine)

    override suspend fun update(transactionId: Int, draft: TransactionDraft, version: String): FinanceResult<Transaction> {
        if (transactionId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val body = try { TransactionUpdateJson.encode(draft, version) } catch (_: Exception) { return FinanceResult.Failure(FinanceFailure.InvalidInput) }
        return writer.send(WriteMethod.PUT, configuration.transactionUpdateUrl(transactionId), body, setOf(200)) {
            FinanceJson.updatedTransaction(it, transactionId, draft)
        }
    }

    override suspend fun get(transactionId: Int): FinanceResult<Transaction> {
        if (transactionId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return transport.getUrl(configuration.transactionUpdateUrl(transactionId)) { FinanceJson.transaction(it, transactionId) }
    }

    override suspend fun delete(transaction: Transaction): FinanceResult<Unit> {
        val id = transaction.transactionId
        val version = transaction.version
        if (id <= 0 || version == null || !version.matches(TRANSACTION_VERSION)) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val url = configuration.transactionUpdateUrl(id)?.let { java.net.URL(it.toString() + "?version=" + version) }
        return writer.send(WriteMethod.DELETE, url, null, setOf(200)) { FinanceJson.deletedTransaction(it, id) }
    }
}
