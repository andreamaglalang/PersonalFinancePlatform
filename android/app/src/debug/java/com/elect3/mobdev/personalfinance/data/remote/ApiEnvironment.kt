package com.elect3.mobdev.personalfinance.data.remote

import android.util.Log
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Debug-only endpoint and opt-in diagnostic. Never sends authentication data or logs a body. */
internal object ApiEnvironment {
    private const val BASE_URL = "https://localhost:7027/"
    private const val TAG = "OwliHttpsProbe"

    fun configuration(): ApiConfiguration = ApiConfiguration.https(BASE_URL)

    suspend fun checkConnectionIfRequested(requested: Boolean) {
        if (!requested) return
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var connection: HttpsURLConnection? = null
            try {
                connection = URL(BASE_URL + "openapi/v1.json").openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                // Platform chain/hostname verification and the debug Network Security Config apply.
                // Do not override the socket factory, trust manager, or hostname verifier.
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                val certificate = connection.serverCertificates.first() as X509Certificate
                certificate.checkValidity()
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02X".format(it.toInt() and 0xff) }
                val cipher = connection.cipherSuite
                if (status in 200..299) connection.inputStream.close()
                else connection.errorStream?.close()
                Log.i(TAG, "HTTPS_PROBE_RESULT http=$status normalTls=true host=localhost " +
                    "serverSha256=$fingerprint cipher=$cipher")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // This fixed, credential-free request has no private request/response data.
                // Report bounded exception details only; never log the OpenAPI response body.
                val details = generateSequence<Throwable>(failure) { it.cause }.take(3)
                    .joinToString(" | ") {
                        "${it.javaClass.name}: ${it.message.orEmpty().replace(Regex("[\\r\\n]+"), " ").take(240)}"
                    }
                Log.e(TAG, "HTTPS_PROBE_FAILURE $details")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
