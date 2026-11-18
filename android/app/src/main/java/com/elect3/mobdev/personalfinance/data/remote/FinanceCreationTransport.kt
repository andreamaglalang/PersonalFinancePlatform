package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.net.URL
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal const val MAX_CREATION_REQUEST_BYTES = 16 * 1024
internal object CreationJson {
    fun encode(request: CreateTransactionRequest): String {
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.serializeNulls = true
            json.beginObject()
            json.name("clientId").value(request.clientId.toString())
            json.name("type").value(request.draft.type.wireValue)
            json.name("categoryId").value(request.draft.categoryId)
            json.name("amount").value(request.draft.amount)
            json.name("currencyCode").value(request.draft.currencyCode)
            json.name("transactionDate").value(request.draft.transactionDate.toString())
            json.name("description").value(request.draft.description)
            json.endObject()
        }
        return output.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_CREATION_REQUEST_BYTES) { "Request too large" } }
    }
}
internal class FinancePostRequest(val url: URL, private val bearer: String, val body: String) {
    fun authorization() = bearer
    override fun toString() = "FinancePostRequest([redacted])"
}
internal fun interface FinancePostEngine { suspend fun post(request: FinancePostRequest): FinanceHttpResponse }

/** Fixed-length POST, no redirect/retry loop, platform TLS unchanged, no body/error logging. */
internal class PlatformFinancePostEngine(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : FinancePostEngine {
    override suspend fun post(request: FinancePostRequest): FinanceHttpResponse = withContext(Dispatchers.IO) {
        ensureActive()
        val bytes = request.body.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CREATION_REQUEST_BYTES) { "Request too large" }
        val connection = open(request.url)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) { bytes.fill(0); connection.disconnect(); return@suspendCancellableCoroutine }
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Authorization", request.authorization())
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json, application/problem+json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                val body = if (status == 200 || status == 201) connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        if (!continuation.isActive) throw CancellationException()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > MAX_BODY_BYTES) throw InvalidFinanceResponse()
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } else ""
                if (continuation.isActive) continuation.resume(FinanceHttpResponse(status, body))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally { bytes.fill(0); connection.disconnect() }
        }
    }
}
private class CreationSessionChanged : CancellationException()

/** Explicit construction only. Not connected to FinanceRepositories or the UI Save action. */
class RemoteTransactionCreationRepository private constructor(
    private val configuration: ApiConfiguration,
    private val auth: InMemoryAuthRepository,
    private val engine: FinancePostEngine,
) : TransactionCreationRepository {
    constructor(configuration: ApiConfiguration, auth: InMemoryAuthRepository) : this(configuration, auth, PlatformFinancePostEngine())
    internal constructor(auth: InMemoryAuthRepository, engine: FinancePostEngine,
        configuration: ApiConfiguration = ApiConfiguration.unconfigured()) : this(configuration, auth, engine)

    override suspend fun create(request: CreateTransactionRequest): CreationResult {
        val url = configuration.transactionCreateUrl() ?: return CreationResult.Failure(FinanceFailure.NotConfigured)
        val lease = when (val access = auth.financeSession()) {
            is FinanceResult.Success -> access.value
            is FinanceResult.Failure -> return CreationResult.Failure(access.reason)
        }
        val body = try { CreationJson.encode(request) } catch (_: Exception) { return CreationResult.Failure(FinanceFailure.InvalidInput) }
        val dispatched = AtomicBoolean(false)
        fun lostSession(): CreationResult = if (dispatched.get()) CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true)
            else CreationResult.Failure(FinanceFailure.SessionExpired)
        return try {
            coroutineScope {
                val work = async(start = CoroutineStart.LAZY) {
                    if (!lease.isCurrent()) return@async lostSession()
                    val http = FinancePostRequest(url, lease.authorization(), body)
                    ensureActive()
                    dispatched.set(true) // Conservative: entry into the engine can transmit the body.
                    val response = engine.post(http)
                    ensureActive()
                    if (!lease.isCurrent()) return@async lostSession()
                    val result = when (response.status) {
                        200, 201 -> try {
                            require(response.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
                            CreationResult.Success(FinanceJson.createdTransaction(response.body, request), response.status == 200)
                        } catch (_: Exception) { CreationResult.UnknownOutcome(UnknownCreationReason.InvalidResponse) }
                        in 500..599 -> CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure)
                        400, 401, 403, 404, 409, 429 -> CreationResult.Failure(statusFailure(response.status))
                        else -> CreationResult.UnknownOutcome(UnknownCreationReason.InvalidResponse)
                    }
                    ensureActive()
                    if (lease.isCurrent()) result else lostSession()
                }
                val registration = lease.lifetime.invokeOnCompletion { work.cancel(CreationSessionChanged()) }
                try { work.start(); work.await() } finally { registration.dispose() }
            }
        } catch (_: CreationSessionChanged) {
            currentCoroutineContext().ensureActive(); lostSession()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SocketTimeoutException) { CreationResult.UnknownOutcome(UnknownCreationReason.Timeout)
        } catch (_: InvalidFinanceResponse) { CreationResult.UnknownOutcome(UnknownCreationReason.InvalidResponse)
        } catch (_: Exception) { CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure) }
    }
}
