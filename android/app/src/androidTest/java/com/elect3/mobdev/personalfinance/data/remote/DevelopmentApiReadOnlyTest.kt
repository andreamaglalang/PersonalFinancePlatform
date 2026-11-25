package com.elect3.mobdev.personalfinance.data.remote

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual API, normal platform TLS, no credentials, no login/refresh/logout or financial writes. */
class DevelopmentApiReadOnlyTest {
    @Test fun phoneReachesActualApiWithTrustedTlsAndAccountRoutes() {
        val connection=URL("https://localhost:7027/openapi/v1.json").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod="GET";connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=10000;connection.readTimeout=10000
            assertEquals(200,connection.responseCode)
            val serverTime=ZonedDateTime.parse(connection.getHeaderField("Date"),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            assertTrue("Device/server UTC clock difference exceeds renewal margin",Duration.between(serverTime,Instant.now()).abs().seconds<90)
            val body=connection.inputStream.bufferedReader().use{it.readText()}
            val paths=JSONObject(body).getJSONObject("paths")
            assertTrue(paths.getJSONObject("/api/account/profile").has("put"))
            assertTrue(paths.getJSONObject("/api/account/change-password").has("post"))
        } finally { connection.disconnect() }
    }
    @Test fun protectedAccountApiRejectsMissingAuthentication() {
        val connection=URL("https://localhost:7027/api/account/profile").openConnection() as HttpsURLConnection
        try { connection.connectTimeout=10000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
            assertEquals(401,connection.responseCode)
        } finally { connection.disconnect() }
    }
}
