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
    /** Exactly the API's TransactionWriteRequest fields (it rejects unknown members): no clientId on an edit. */
    fun encode(draft: TransactionDraft): String {
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
            json.endObject()
        }
        return output.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_UPDATE_REQUEST_BYTES) { "Request too large" } }
    }
}

/**
 * Member transaction edit over the existing PUT route. Session handling and status mapping are the shared
 * [FinanceWriter] rules: error bodies are never read, failures are bounded [FinanceFailure] values.
 * The response is the server's authoritative snapshot (rate, rate date, base amount); it is checked against the
 * submitted edit and never recalculated here.
 */
internal class RemoteTransactionUpdateRepository(
    private val configuration: ApiConfiguration,
    auth: InMemoryAuthRepository,
    engine: FinanceWriteEngine,
) : TransactionUpdateRepository {
    private val writer = FinanceWriter(auth, engine)

    override suspend fun update(transactionId: Int, draft: TransactionDraft): FinanceResult<Transaction> {
        if (transactionId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val body = try { TransactionUpdateJson.encode(draft) } catch (_: Exception) { return FinanceResult.Failure(FinanceFailure.InvalidInput) }
        return writer.send(WriteMethod.PUT, configuration.transactionUpdateUrl(transactionId), body, setOf(200)) {
            FinanceJson.updatedTransaction(it, transactionId, draft)
        }
    }
}
