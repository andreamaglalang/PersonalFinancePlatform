package com.elect3.mobdev.personalfinance.data.remote

import java.io.*
import java.net.*
import javax.net.ssl.*
import java.security.cert.Certificate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** In-memory transport doubles, not live authentication. Fake fixture credentials are never logged. */
class AuthDeliveryClassificationTest {
    private class Connection(val mode: String, val status: Int = 503) : HttpsURLConnection(URL("https://fixture.invalid/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): OutputStream {
            if(mode=="connect") throw ConnectException()
            if(mode=="tls") throw SSLHandshakeException("fixture")
            return object : OutputStream() { override fun write(value: Int) { if(mode=="partial") throw SocketTimeoutException() } }
        }
        override fun getResponseCode(): Int { if(mode=="response-timeout") throw SocketTimeoutException();return status }
    }
    private fun failure(mode: String, status: Int = 503): ApiResult.Failure = runBlocking {
        val client=AuthApiClient(ApiConfiguration.https("https://fixture.invalid/"),open={Connection(mode,status)})
        client.refresh("f".repeat(86)) as ApiResult.Failure
    }
    @Test fun connectionFailureBeforeBodyIsProvenNotSent() {
        val result=failure("connect");assertEquals(AuthFailure.ConnectionFailed,result.reason);assertEquals(RequestDelivery.NotSent,result.delivery)
    }
    @Test fun tlsFailureBeforeBodyHasDistinctCategoryAndIsNotSent() {
        val result=failure("tls");assertEquals(AuthFailure.SecureConnection,result.reason);assertEquals(RequestDelivery.NotSent,result.delivery)
    }
    @Test fun partialBodyTimeoutIsUncertain() { assertEquals(RequestDelivery.Unknown,failure("partial").delivery) }
    @Test fun timeoutWaitingForResponseIsUncertain() { assertEquals(RequestDelivery.Unknown,failure("response-timeout").delivery) }
    @Test fun serverFailureIsNeverAssumedSafeToReplay() { assertEquals(RequestDelivery.Unknown,failure("http",503).delivery) }
    @Test fun rateLimitIsAConfirmedRejection() {
        val result=failure("http",429);assertEquals(AuthFailure.RateLimited,result.reason);assertEquals(RequestDelivery.Rejected,result.delivery)
    }
    @Test fun unauthorizedIsARealSessionRejection() {
        val result=failure("http",401);assertEquals(AuthFailure.SessionExpired,result.reason);assertEquals(RequestDelivery.Rejected,result.delivery)
    }
}
