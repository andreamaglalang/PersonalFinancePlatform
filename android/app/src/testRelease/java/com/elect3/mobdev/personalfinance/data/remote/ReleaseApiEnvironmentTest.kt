package com.elect3.mobdev.personalfinance.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** R11: the release endpoint comes only from the build-time property; anything unusable stays unconfigured. */
class ReleaseApiEnvironmentTest {
    @Test fun blankOrInvalidValuesStayUnconfigured() {
        for (value in listOf("", "   ", "http://api.example.test/", "https://user:pw@api.example.test/", "https://api.example.test/v1", "not a url"))
            assertEquals(value, "unconfigured", ApiEnvironment.fromBuildValue(value).sessionNamespace())
    }

    @Test fun httpsOriginIsUsedForAuthAndFinanceRoutes() {
        val configuration = ApiEnvironment.fromBuildValue("https://api.example.test/")
        assertEquals("https://api.example.test/", configuration.sessionNamespace())
        assertEquals("https://api.example.test/api/auth/login", configuration.authUrl("login").toString())
    }

    @Test fun thisBuildDoesNotInventAHost() {
        // Unit tests run without -PowliApiBaseUrl, so the generated value must be empty and the app unconfigured.
        assertEquals("unconfigured", ApiEnvironment.configuration().sessionNamespace())
    }
}
