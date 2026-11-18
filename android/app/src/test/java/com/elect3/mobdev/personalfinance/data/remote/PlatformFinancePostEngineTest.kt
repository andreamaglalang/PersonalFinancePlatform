package com.elect3.mobdev.personalfinance.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.Certificate
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import javax.net.ssl.HttpsURLConnection

/** URLConnection double only: no sockets or HTTP server. */
class PlatformFinancePostEngineTest {
    private val url = URL("https://localhost:7027/api/transactions")
    private class Connection(url: URL, private val status: Int = 201, private val body: ByteArray = "{}".toByteArray()) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var inputRead = false
        var customInput: InputStream? = null
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; customInput?.close() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream(): java.io.InputStream { inputRead = true; return customInput ?: ByteArrayInputStream(body) }
        override fun getErrorStream(): java.io.InputStream = error("Error body must not be read")
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun postUsesFixedBodyBearerNormalTlsAndNoRedirects() = runTest {
        val c = Connection(url); var opens = 0
        val verifier = c.hostnameVerifier; val socketFactory = c.sslSocketFactory
        val result = PlatformFinancePostEngine { opens++; c }.post(FinancePostRequest(url, "Bearer offline", "{\"amount\":12.34}"))
        assertEquals("POST", c.requestMethod); assertEquals(201, result.status)
        assertEquals("{\"amount\":12.34}", c.sent.toString("UTF-8"))
        assertEquals("Bearer offline", c.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", c.getRequestProperty("Content-Type"))
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches)
        assertSame(verifier, c.hostnameVerifier); assertSame(socketFactory, c.sslSocketFactory)
        assertTrue(c.disconnected); assertEquals(1, opens)
    }
    @Test fun bothSuccessStatusesReadBodiesAndRejectedStatusesDoNot() = runTest {
        for (status in listOf(200, 201, 302, 401, 503)) {
            val c = Connection(url, status)
            val result = PlatformFinancePostEngine { c }.post(FinancePostRequest(url, "Bearer offline", "{}"))
            assertEquals(status, result.status)
            assertEquals(status == 200 || status == 201, c.inputRead)
            assertTrue(c.disconnected)
        }
    }
    @Test fun oversizedResponseIsRejectedAndClosed() = runTest {
        val c = Connection(url, body = ByteArray(MAX_BODY_BYTES + 1) { 65 })
        try { PlatformFinancePostEngine { c }.post(FinancePostRequest(url, "Bearer offline", "{}")); fail("Oversized response accepted") }
        catch (_: InvalidFinanceResponse) { }
        assertTrue(c.disconnected)
    }
    @Test fun oversizedRequestFailsBeforeOpeningConnectionAndRequestStringIsRedacted() = runTest {
        var opens = 0
        val request = FinancePostRequest(url, "Bearer private-fixture", "x".repeat(MAX_CREATION_REQUEST_BYTES + 1))
        try { PlatformFinancePostEngine { opens++; Connection(it) }.post(request); fail("Oversized request accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, opens)
        assertFalse(request.toString().contains("private-fixture"))
    }
    @Test fun cancellationDisconnectsAnInterruptedResponseWithoutNetwork() = runTest {
        val reading = CountDownLatch(1); val closed = CountDownLatch(1)
        val c = Connection(url)
        c.customInput = object : InputStream() {
            override fun read(): Int { reading.countDown(); check(closed.await(5, TimeUnit.SECONDS)); return -1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = read()
            override fun close() { closed.countDown() }
        }
        val call = async(Dispatchers.Default) { PlatformFinancePostEngine { c }.post(FinancePostRequest(url, "Bearer offline", "{}")) }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertTrue(c.disconnected); assertEquals(0L, closed.count)
    }
}
