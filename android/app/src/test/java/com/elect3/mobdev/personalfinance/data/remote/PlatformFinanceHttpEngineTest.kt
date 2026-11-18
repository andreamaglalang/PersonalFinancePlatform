package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Uses URLConnection test doubles only. No sockets, devices, backend or credentials. */
class PlatformFinanceHttpEngineTest {
    private class Connection(url: URL, private val status: Int = 200, private val body: ByteArray = "{}".toByteArray()) : HttpsURLConnection(url) {
        var disconnected = false
        var inputOpened = false
        var errorOpened = false
        var customInput: InputStream? = null
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; customInput?.close() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream { inputOpened = true; return customInput ?: ByteArrayInputStream(body) }
        override fun getErrorStream(): InputStream { errorOpened = true; error("Error body must not be read") }
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private val url = URL("https://localhost:7027/api/categories?page=1&pageSize=50")

    @Test fun platformEngineAttachesBearerAndLeavesTlsValidationIntact() = runTest {
        val c = Connection(url)
        val originalVerifier = c.hostnameVerifier
        val originalFactory = c.sslSocketFactory
        var opens = 0
        val engine = PlatformFinanceHttpEngine { opens++; assertEquals(url, it); c }
        val response = engine.get(FinanceHttpRequest(url, "Bearer offline-fixture"))
        assertEquals(200, response.status); assertEquals("{}", response.body)
        assertEquals("GET", c.requestMethod)
        assertEquals("Bearer offline-fixture", c.getRequestProperty("Authorization"))
        assertEquals("application/json", c.getRequestProperty("Accept"))
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches); assertFalse(c.doOutput)
        assertEquals(10000, c.connectTimeout); assertEquals(10000, c.readTimeout)
        assertSame(originalVerifier, c.hostnameVerifier); assertSame(originalFactory, c.sslSocketFactory)
        assertTrue(c.disconnected); assertEquals(1, opens)
    }
    @Test fun redirectsAndErrorBodiesAreNeverFollowedOrRead() = runTest {
        for (status in listOf(302, 401, 503)) {
            val c = Connection(url, status)
            var opens = 0
            val result = PlatformFinanceHttpEngine { opens++; c }.get(FinanceHttpRequest(url, "Bearer offline"))
            assertEquals(status, result.status); assertEquals("", result.body)
            assertEquals(1, opens); assertFalse(c.inputOpened); assertFalse(c.errorOpened); assertTrue(c.disconnected)
        }
    }
    @Test fun oversizedBodyIsRejectedAndConnectionClosed() = runTest {
        val c = Connection(url, body = ByteArray(MAX_BODY_BYTES + 1) { 65 })
        try {
            PlatformFinanceHttpEngine { c }.get(FinanceHttpRequest(url, "Bearer offline"))
            fail("Body limit must be enforced")
        } catch (_: InvalidFinanceResponse) { }
        assertTrue(c.disconnected)
    }
    @Test fun cancellationDisconnectsBlockedReadWithoutNetwork() = runTest {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val c = Connection(url)
        c.customInput = object : InputStream() {
            override fun read(): Int { reading.countDown(); check(closed.await(5, TimeUnit.SECONDS)); return -1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int) = read()
            override fun close() { closed.countDown() }
        }
        val call = async(Dispatchers.Default) { PlatformFinanceHttpEngine { c }.get(FinanceHttpRequest(url, "Bearer offline")) }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertEquals(0L, closed.count); assertTrue(c.disconnected)
    }
    @Test fun invalidBearerCannotInjectHeaders() {
        for (token in listOf("", "offline\r\nInjected: value", "offline token", "x".repeat(16385))) {
            val lease = FinanceSessionLease(token, Job()) { true }
            try { lease.authorization(); fail("Invalid bearer accepted") } catch (e: IllegalArgumentException) {
                assertEquals("Invalid session", e.message)
            }
            assertFalse(lease.toString().contains(token.takeIf { it.isNotEmpty() } ?: "secret-placeholder"))
        }
    }
}
