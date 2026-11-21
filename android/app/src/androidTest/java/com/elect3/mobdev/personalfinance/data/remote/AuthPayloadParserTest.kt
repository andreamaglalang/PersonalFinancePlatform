package com.elect3.mobdev.personalfinance.data.remote

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M1b: login and refresh share one fail-closed response parser. Instrumented because org.json is only a
 * non-functional stub in local JVM tests. No network: no request is ever sent.
 */
class AuthPayloadParserTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")

    private fun body(edit: JSONObject.() -> Unit = {}) = JSONObject("""{
        "accessToken":"synthetic-access","accessTokenExpiresAtUtc":"2030-01-01T00:15:00Z",
        "refreshToken":"${"R".repeat(43)}${"_-".repeat(21)}x","refreshTokenExpiresAtUtc":"2030-01-08T00:00:00Z",
        "user":{"id":7,"fullName":"Synthetic","email":"s@example.test","baseCurrency":"PHP","roles":["User"]}}""")
        .apply(edit).toString()

    private fun rejected(edit: JSONObject.() -> Unit) {
        try { parseAuthPayload(body(edit), now); fail("Accepted an invalid authentication response") }
        catch (_: Exception) { /* fail closed */ }
    }

    @Test fun validResponseParsesAndStaysRedacted() {
        val payload = parseAuthPayload(body(), now)
        assertEquals("synthetic-access", payload.accessToken)
        assertEquals(86, payload.refreshToken.length)
        assertEquals(7, payload.user.id); assertEquals("PHP", payload.user.baseCurrency)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), payload.accessExpires)
        assertFalse(payload.toString().contains("synthetic-access"))
        assertFalse(payload.user.toString().contains("s@example.test"))
    }

    @Test fun refreshTokenOutsideTheServerFormatIsRejected() {
        rejected { put("refreshToken", "R".repeat(85)) }
        rejected { put("refreshToken", "R".repeat(87)) }
        rejected { put("refreshToken", "R".repeat(85) + "=") }
        rejected { put("refreshToken", "R".repeat(85) + "+") }
    }

    @Test fun expiredOrInconsistentExpiriesAreRejected() {
        rejected { put("accessTokenExpiresAtUtc", "2030-01-01T00:00:00Z") }
        rejected { put("accessTokenExpiresAtUtc", "2029-12-31T23:59:00Z") }
        rejected { put("refreshTokenExpiresAtUtc", "2030-01-01T00:10:00Z") }
        rejected { put("accessTokenExpiresAtUtc", "not-a-date") }
    }

    @Test fun missingOrInvalidIdentityIsRejected() {
        rejected { remove("user") }
        rejected { getJSONObject("user").put("id", 0) }
        rejected { getJSONObject("user").put("fullName", " ") }
        rejected { getJSONObject("user").put("baseCurrency", "php") }
        rejected { getJSONObject("user").remove("roles") }
        rejected { put("accessToken", "") }
        rejected { remove("refreshToken") }
    }

    @Test fun nonJsonBodyIsRejected() {
        try { parseAuthPayload("<html>private</html>", now); fail() } catch (_: Exception) { }
    }

    @Test fun defaultRefreshCapabilityFailsClosedWithoutARequest() = kotlinx.coroutines.runBlocking {
        val api = object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = error("unused")
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = error("unused")
        }
        val result = api.refresh("R".repeat(86))
        assertEquals(AuthFailure.NotConfigured, (result as ApiResult.Failure).reason)
    }

    @Test fun unconfiguredClientNeverSendsTheRefreshToken() = kotlinx.coroutines.runBlocking {
        val result = AuthApiClient(ApiConfiguration.unconfigured()).refresh("R".repeat(86))
        assertEquals(AuthFailure.NotConfigured, (result as ApiResult.Failure).reason)
    }
}
