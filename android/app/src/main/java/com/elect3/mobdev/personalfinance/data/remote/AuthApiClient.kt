package com.elect3.mobdev.personalfinance.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticIds
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.NoAuthDiagnostics
import kotlin.coroutines.resume

/** Safe categories only. Raw response bodies and exception messages never reach UI state. */
enum class AuthFailure {
    NotConfigured, InvalidInput, InvalidCredentials, RateLimited, Timeout,
    SecureConnection, ConnectionFailed, Unavailable, InvalidResponse, AccessDenied, SessionExpired, RenewalUnconfirmed, SecureStorage,
}

internal enum class RequestDelivery { NotSent, Rejected, Unknown }

internal sealed interface ApiResult<out T> {
    class Success<T>(val value: T) : ApiResult<T>
    class Failure(val reason: AuthFailure, val delivery: RequestDelivery = RequestDelivery.Unknown) : ApiResult<Nothing>
}

/** Deliberately not a data class: generated toString/copy must not expose secrets. */
internal class AuthPayload(
    val accessToken: String,
    val refreshToken: String,
    val accessExpires: Instant,
    val refreshExpires: Instant,
    val user: AuthUser,
) {
    override fun toString() = "AuthPayload([redacted])"
}

internal class AuthUser(
    val id: Int, val fullName: String, val email: String,
    val baseCurrency: String, val roles: List<String>,
) {
    override fun toString() = "AuthUser([redacted])"
}

internal interface AuthApi {
    suspend fun login(email: String, password: String): ApiResult<AuthPayload>
    suspend fun logout(refreshToken: String): ApiResult<Unit>
    /** Rotates the refresh token. Default fails closed (no request) for hosts without a refresh transport. */
    suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> = ApiResult.Failure(AuthFailure.NotConfigured)
}

/**
 * Shared validation for login and refresh responses. Throws on anything malformed, expired or out of
 * contract; callers map that to [AuthFailure.InvalidResponse]. Never returns partially validated credentials.
 */
internal fun parseAuthPayload(body: String, now: Instant = Instant.now()): AuthPayload {
    val json = JSONObject(body)
    val user = json.getJSONObject("user")
    val roles = user.getJSONArray("roles")
    val access = json.getString("accessToken")
    val refresh = json.getString("refreshToken")
    val accessExpires = Instant.parse(json.getString("accessTokenExpiresAtUtc"))
    val refreshExpires = Instant.parse(json.getString("refreshTokenExpiresAtUtc"))
    val id = user.getInt("id")
    val name = user.getString("fullName")
    val emailValue = user.getString("email")
    val currency = user.getString("baseCurrency")
    require(access.isNotBlank() && access.length <= 16384)
    require(refresh.matches(Regex("[A-Za-z0-9_-]{86}")))
    require(id > 0 && name.isNotBlank() && emailValue.isNotBlank())
    require(currency.matches(Regex("[A-Z]{3}")))
    require(accessExpires.isAfter(now) && !refreshExpires.isBefore(accessExpires))
    return AuthPayload(access, refresh, accessExpires, refreshExpires,
        AuthUser(id, name, emailValue, currency, List(roles.length()) { roles.getString(it) }))
}

/** Platform TLS defaults are untouched; redirects, caching, and automatic retries are disabled. */
internal class AuthApiClient(
    private val configuration: ApiConfiguration = ApiConfiguration.unconfigured(),
    private val diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : AuthApi {
    private val diagnosticId = AuthDiagnosticIds.next()
    override suspend fun login(email: String, password: String): ApiResult<AuthPayload> =
        payload(post("login", JSONObject().put("email", email).put("password", password)))

    /** POST api/auth/refresh. One attempt only; the caller never retries the same token automatically. */
    override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> =
        payload(post("refresh", JSONObject().put("refreshToken", refreshToken)))

    private fun payload(response: ApiResult<String>): ApiResult<AuthPayload> = when (response) {
        is ApiResult.Failure -> response
        is ApiResult.Success -> try {
            ApiResult.Success(parseAuthPayload(response.value))
        } catch (_: Exception) {
            ApiResult.Failure(AuthFailure.InvalidResponse)
        }
    }

    override suspend fun logout(refreshToken: String): ApiResult<Unit> {
        return when (val response = observeLogoutRequest(diagnostics, diagnosticId) {
            post("logout", JSONObject().put("refreshToken", refreshToken))
        }) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> ApiResult.Success(Unit)
        }
    }

    private suspend fun post(operation: String, body: JSONObject): ApiResult<String> {
        val url = configuration.authUrl(operation) ?: return ApiResult.Failure(AuthFailure.NotConfigured)
        return withContext<ApiResult<String>>(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val connection = try { open(url) }
            catch (_: SSLException) { return@withContext ApiResult.Failure(AuthFailure.SecureConnection, RequestDelivery.NotSent) }
            catch (_: IOException) { return@withContext ApiResult.Failure(AuthFailure.ConnectionFailed, RequestDelivery.NotSent) }
            catch (_: Exception) { return@withContext ApiResult.Failure(AuthFailure.Unavailable, RequestDelivery.NotSent) }
            // Install cancellation before any blocking network operation begins.
            suspendCancellableCoroutine<ApiResult<String>> { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                var bodyStarted = false
                val result = try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json, application/problem+json")
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    try {
                        connection.outputStream.use { bodyStarted = true; it.write(bytes) }
                    } finally {
                        bytes.fill(0)
                    }
                    val status = connection.responseCode
                    // login and refresh return credentials (200 + body); logout acknowledges with 204.
                    val returnsCredentials = operation == "login" || operation == "refresh"
                    if (status == (if (returnsCredentials) 200 else 204)) {
                        val output = ByteArrayOutputStream()
                        if (returnsCredentials) connection.inputStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                if (!continuation.isActive) throw CancellationException()
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (output.size() + count > 65536) throw InvalidAuthResponse()
                                output.write(buffer, 0, count)
                            }
                        }
                        ApiResult.Success(output.toString("UTF-8"))
                    } else {
                        // ASP.NET ProblemDetails/ValidationProblemDetails can include title/errors.
                        // Status is authoritative; avoid displaying or retaining arbitrary private bodies.
                        ApiResult.Failure(when (status) {
                            400 -> AuthFailure.InvalidInput
                            401 -> if (operation == "login") AuthFailure.InvalidCredentials else AuthFailure.SessionExpired
                            403 -> AuthFailure.AccessDenied
                            429 -> AuthFailure.RateLimited
                            in 500..599 -> AuthFailure.Unavailable
                            else -> AuthFailure.InvalidResponse
                        }, if (status in 400..499) RequestDelivery.Rejected else RequestDelivery.Unknown)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SocketTimeoutException) {
                    ApiResult.Failure(AuthFailure.Timeout, if (bodyStarted) RequestDelivery.Unknown else RequestDelivery.NotSent)
                } catch (_: SSLException) {
                    ApiResult.Failure(AuthFailure.SecureConnection, if (bodyStarted) RequestDelivery.Unknown else RequestDelivery.NotSent)
                } catch (_: InvalidAuthResponse) {
                    ApiResult.Failure(AuthFailure.InvalidResponse)
                } catch (_: IOException) {
                    ApiResult.Failure(AuthFailure.ConnectionFailed, if (bodyStarted) RequestDelivery.Unknown else RequestDelivery.NotSent)
                } catch (_: Exception) {
                    ApiResult.Failure(AuthFailure.Unavailable)
                } finally {
                    connection.disconnect()
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    private class InvalidAuthResponse : IOException()
}
