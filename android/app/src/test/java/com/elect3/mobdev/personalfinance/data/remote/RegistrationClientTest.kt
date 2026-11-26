package com.elect3.mobdev.personalfinance.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** URLConnection double only: no sockets, no live API. Fixture credentials are synthetic. */
class RegistrationClientTest {
    private class Connection(url: URL, val status: Int, val body: String = "", val failConnect: Boolean = false) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream(): OutputStream { if (failConnect) throw ConnectException(); return sent }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): java.io.InputStream = error("Error body must not be read")
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private val user = """{"id":7,"fullName":"Test Member","email":"member@example.test","baseCurrency":"PHP","roles":["Member"]}"""
    private var opened: URL? = null
    private var connection: Connection? = null

    private fun register(status: Int, body: String = "", failConnect: Boolean = false) = runBlocking {
        AuthApiClient(ApiConfiguration.https("https://fixture.invalid/"), open = { url ->
            opened = url; Connection(url, status, body, failConnect).also { connection = it }
        }).register("Test Member", "member@example.test", "Fixture-Pass-123!", "Fixture-Pass-123!")
    }

    private fun failure(status: Int) = (register(status) as ApiResult.Failure).reason

    @Test fun createdParsesCurrentUserAndPostsExactBodyToRegister() {
        val result = register(201, user) as ApiResult.Success
        assertEquals(7, result.value.id)
        assertEquals("member@example.test", result.value.email)
        assertEquals("PHP", result.value.baseCurrency)
        assertEquals(listOf("Member"), result.value.roles)
        assertEquals(URL("https://fixture.invalid/api/auth/register"), opened)
        assertEquals("POST", connection!!.requestMethod)
        val sent = JsonParser.parseString(connection!!.sent.toString("UTF-8")).asJsonObject
        assertEquals(setOf("fullName", "email", "password", "confirmPassword"), sent.keySet())
        assertEquals("Test Member", sent.get("fullName").asString)
        assertEquals("member@example.test", sent.get("email").asString)
        assertEquals("Fixture-Pass-123!", sent.get("password").asString)
        assertEquals("Fixture-Pass-123!", sent.get("confirmPassword").asString)
    }

    @Test fun okInsteadOfCreatedOrMalformedProfileIsNotSuccess() {
        assertEquals(AuthFailure.InvalidResponse, failure(200))
        assertEquals(AuthFailure.InvalidResponse, (register(201, "{\"id\":0}") as ApiResult.Failure).reason)
    }

    @Test fun statusesMapToRegistrationFailures() {
        assertEquals(AuthFailure.InvalidInput, failure(400))
        assertEquals(AuthFailure.AlreadyRegistered, failure(409))
        assertEquals(AuthFailure.RateLimited, failure(429))
        assertEquals(AuthFailure.Unavailable, failure(503))
    }

    @Test fun networkFailureIsConnectionFailed() {
        assertEquals(AuthFailure.ConnectionFailed, (register(201, failConnect = true) as ApiResult.Failure).reason)
    }

    @Test fun unconfiguredEndpointSendsNothing() = runBlocking {
        var opens = 0
        val result = AuthApiClient(open = { opens++; Connection(it, 201, user) })
            .register("a", "b@c.d", "p", "p") as ApiResult.Failure
        assertEquals(AuthFailure.NotConfigured, result.reason); assertEquals(0, opens)
    }
}
