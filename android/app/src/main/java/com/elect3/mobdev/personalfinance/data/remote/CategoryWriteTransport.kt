package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryDraft
import com.elect3.mobdev.personalfinance.domain.repository.CategoryWriteRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringWriter
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal enum class WriteMethod { POST, PUT, DELETE }

internal class FinanceWriteRequest(val method: WriteMethod, val url: URL, private val bearer: String, val body: String?) {
    fun authorization() = bearer
    override fun toString() = "FinanceWriteRequest(${method.name}, [redacted])"
}
internal fun interface FinanceWriteEngine { suspend fun send(request: FinanceWriteRequest): FinanceHttpResponse }

internal const val MAX_CATEGORY_REQUEST_BYTES = 4 * 1024

internal object CategoryWriteJson {
    /** Exactly the existing CategoryWriteRequest fields; no icon or order is ever sent. */
    fun encode(draft: CategoryDraft): String {
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.serializeNulls = true
            json.beginObject()
            json.name("name").value(draft.trimmedName)
            json.name("type").value(draft.type.wireValue)
            json.name("colorHEX").value(draft.colorHex?.uppercase())
            json.name("isActive").value(draft.isActive)
            json.endObject()
        }
        return output.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_CATEGORY_REQUEST_BYTES) }
    }
}

/** Fixed-length body, no redirects or retries, platform TLS unchanged, success bodies only, nothing logged. */
internal class PlatformFinanceWriteEngine(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : FinanceWriteEngine {
    override suspend fun send(request: FinanceWriteRequest): FinanceHttpResponse = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val bytes = request.body?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= MAX_CATEGORY_REQUEST_BYTES)
        val connection = open(request.url)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) { connection.disconnect(); return@suspendCancellableCoroutine }
            try {
                connection.requestMethod = request.method.name
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Authorization", request.authorization())
                connection.setRequestProperty("Accept", "application/json, application/problem+json")
                if (bytes != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
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
                } else "" // Error bodies (ProblemDetails) are never read or retained.
                if (continuation.isActive) continuation.resume(FinanceHttpResponse(status, body))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally { connection.disconnect() }
        }
    }
}

private class CategoryWriteSessionChanged : CancellationException()

/**
 * Member category writes against the existing endpoints. Session handling mirrors the GET transport:
 * a missing/expired/changed session yields MissingSession/SessionExpired so callers route to expiry.
 */
internal class RemoteCategoryWriteRepository(
    private val configuration: ApiConfiguration,
    auth: InMemoryAuthRepository,
    engine: FinanceWriteEngine,
) : CategoryWriteRepository {
    private val writer = FinanceWriter(auth, engine)
    override suspend fun create(draft: CategoryDraft): FinanceResult<Category> =
        send(WriteMethod.POST, null, draft, setOf(201, 200)) { FinanceJson.category(it, draft) }

    override suspend fun update(categoryId: Int, draft: CategoryDraft): FinanceResult<Category> =
        send(WriteMethod.PUT, categoryId, draft, setOf(200)) { FinanceJson.category(it, draft, categoryId) }

    override suspend fun delete(categoryId: Int): FinanceResult<Unit> =
        send(WriteMethod.DELETE, categoryId, null, setOf(204, 200)) { }

    private suspend fun <T> send(method: WriteMethod, id: Int?, draft: CategoryDraft?, ok: Set<Int>, decode: (String) -> T): FinanceResult<T> {
        if (id != null && id <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val body = try { draft?.let { CategoryWriteJson.encode(it) } } catch (_: Exception) { return FinanceResult.Failure(FinanceFailure.InvalidInput) }
        return writer.send(method, configuration.categoryWriteUrl(id), body, ok, decode)
    }
}

/**
 * Authenticated write over the existing member endpoints. A missing/expired/changed session yields
 * MissingSession/SessionExpired; statuses map to bounded failures; error bodies are never read.
 */
internal class FinanceWriter(private val auth: InMemoryAuthRepository, private val engine: FinanceWriteEngine) {
    suspend fun <T> send(method: WriteMethod, url: URL?, body: String?, ok: Set<Int>, decode: (String) -> T): FinanceResult<T> {
        if (url == null) return FinanceResult.Failure(FinanceFailure.NotConfigured)
        val lease = when (val access = auth.financeSession()) {
            is FinanceResult.Success -> access.value
            is FinanceResult.Failure -> return access
        }
        return try {
            coroutineScope {
                val work = async(start = CoroutineStart.LAZY) {
                    if (!lease.isCurrent()) return@async FinanceResult.Failure(FinanceFailure.SessionExpired)
                    val response = engine.send(FinanceWriteRequest(method, url, lease.authorization(), body))
                    ensureActive()
                    if (!lease.isCurrent()) return@async FinanceResult.Failure(FinanceFailure.SessionExpired)
                    val result: FinanceResult<T> = if (response.status in ok) {
                        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw InvalidFinanceResponse()
                        try { FinanceResult.Success(decode(response.body)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { throw InvalidFinanceResponse() }
                    } else FinanceResult.Failure(statusFailure(response.status))
                    ensureActive()
                    if (lease.isCurrent()) result else FinanceResult.Failure(FinanceFailure.SessionExpired)
                }
                val registration = lease.lifetime.invokeOnCompletion { work.cancel(CategoryWriteSessionChanged()) }
                try { work.start(); work.await() } finally { registration.dispose() }
            }
        } catch (_: CategoryWriteSessionChanged) {
            currentCoroutineContext().ensureActive()
            FinanceResult.Failure(FinanceFailure.SessionExpired)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SocketTimeoutException) { FinanceResult.Failure(FinanceFailure.Timeout)
        } catch (_: SSLException) { FinanceResult.Failure(FinanceFailure.SecureConnection)
        } catch (_: InvalidFinanceResponse) { FinanceResult.Failure(FinanceFailure.InvalidResponse)
        } catch (_: IOException) { FinanceResult.Failure(FinanceFailure.Unavailable)
        } catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }
    }
}
