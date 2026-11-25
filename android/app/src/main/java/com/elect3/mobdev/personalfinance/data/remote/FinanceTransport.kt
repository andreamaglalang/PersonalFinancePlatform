package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlin.coroutines.resume

internal enum class FinanceResource(val path: String) { Categories("categories"), Transactions("transactions"), Budgets("budgets"), ExchangeRateQuote("exchange-rates/quote") }
internal class FinanceSessionLease(
    private val token: String, val lifetime: Job,
    private val canAuthorize: () -> Boolean = { true }, private val onUnauthorized: () -> Unit = {}, private val valid: () -> Boolean,
) {
    fun isCurrent() = valid()
    fun rejectAuthorization() = onUnauthorized()
    fun authorization(): String {
        if (!canAuthorize() || !valid()) throw ExpiredFinanceAccess()
        require(token.isNotBlank() && token.length <= 16384 && token.all { it.code in 33..126 }) { "Invalid session" }
        return "Bearer $token"
    }
    override fun toString() = "FinanceSessionLease([redacted])"
}
internal class ExpiredFinanceAccess : IllegalStateException()
internal class FinanceHttpRequest(val url: URL, private val bearer: String) {
    fun authorization() = bearer
    override fun toString() = "FinanceHttpRequest([redacted])"
}
internal class FinanceHttpResponse(val status: Int, val body: String) {
    override fun toString() = "FinanceHttpResponse(status=$status, [redacted])"
}
internal fun interface FinanceHttpEngine { suspend fun get(request: FinanceHttpRequest): FinanceHttpResponse }

/** GET only, fixed resources, normal platform TLS. No redirects, retries or body logging. */
internal class PlatformFinanceHttpEngine(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : FinanceHttpEngine {
    override suspend fun get(request: FinanceHttpRequest): FinanceHttpResponse = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connection = open(request.url)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) { connection.disconnect(); return@suspendCancellableCoroutine }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Authorization", request.authorization())
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val body = if (status == 200) connection.inputStream.use { input ->
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
                } else "" // Never retain private ProblemDetails/error bodies.
                if (continuation.isActive) continuation.resume(FinanceHttpResponse(status, body))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally { connection.disconnect() }
        }
    }
}
internal const val MAX_BODY_BYTES = 1024 * 1024
internal class InvalidFinanceResponse : IOException()
private class FinanceSessionChanged : CancellationException()

internal class AuthenticatedFinanceTransport(
    private val configuration: ApiConfiguration,
    private val auth: InMemoryAuthRepository,
    private val engine: FinanceHttpEngine,
) {
    suspend fun <T> get(resource: FinanceResource, query: String, decode: (String) -> T): FinanceResult<T> {
        val url = configuration.financeUrl(resource, query) ?: return FinanceResult.Failure(FinanceFailure.NotConfigured)
        return getUrl(url, decode)
    }

    suspend fun <T> getUrl(url: URL?, decode: (String) -> T): FinanceResult<T> = getAttempt(url, decode, true)
    private suspend fun <T> getAttempt(url: URL?, decode: (String) -> T, canRecover: Boolean): FinanceResult<T> {
        if (url == null) return FinanceResult.Failure(FinanceFailure.NotConfigured)
        val lease = when (val access = auth.readyFinanceSession()) {
            is FinanceResult.Success -> access.value
            is FinanceResult.Failure -> return access
        }
        return try {
            coroutineScope {
                val work = async(start = CoroutineStart.LAZY) {
                    if (!lease.isCurrent()) return@async FinanceResult.Failure(FinanceFailure.SessionExpired)
                    val response = engine.get(FinanceHttpRequest(url, lease.authorization()))
                    ensureActive()
                    if (!lease.isCurrent()) return@async FinanceResult.Failure(FinanceFailure.SessionExpired)
                    val result = if (response.status == 200) {
                        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw InvalidFinanceResponse()
                        try {
                            FinanceResult.Success(decode(response.body))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // JSON reader IO failures are malformed responses, not network failures.
                            throw InvalidFinanceResponse()
                        }
                    } else if (response.status == 401) {
                        lease.rejectAuthorization()
                        if (canRecover) {
                            auth.refreshIfDue()
                            if (auth.hasActiveSession()) return@async getAttempt(url, decode, false)
                        }
                        auth.financeAccessResult()
                    } else FinanceResult.Failure(statusFailure(response.status))
                    ensureActive()
                    if (lease.isCurrent()) result else FinanceResult.Failure(FinanceFailure.SessionExpired)
                }
                val registration = lease.lifetime.invokeOnCompletion { work.cancel(FinanceSessionChanged()) }
                try { work.start(); work.await() } finally { registration.dispose() }
            }
        } catch (_: ExpiredFinanceAccess) {
            auth.financeAccessResult()
        } catch (changed: FinanceSessionChanged) {
            currentCoroutineContext().ensureActive()
            FinanceResult.Failure(FinanceFailure.SessionExpired)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SocketTimeoutException) { FinanceResult.Failure(FinanceFailure.Timeout)
        } catch (_: SSLException) { FinanceResult.Failure(FinanceFailure.SecureConnection)
        } catch (_: InvalidFinanceResponse) { FinanceResult.Failure(FinanceFailure.InvalidResponse)
        } catch (_: IOException) { FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true)
        } catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }
    }
}
internal fun statusFailure(status: Int) = when (status) {
    400 -> FinanceFailure.InvalidInput
    401 -> FinanceFailure.SessionExpired
    403 -> FinanceFailure.AccessDenied
    404 -> FinanceFailure.NotFound
    409, 412, 428 -> FinanceFailure.Conflict
    429 -> FinanceFailure.RateLimited
    in 500..599 -> FinanceFailure.Unavailable
    else -> FinanceFailure.InvalidResponse
}
