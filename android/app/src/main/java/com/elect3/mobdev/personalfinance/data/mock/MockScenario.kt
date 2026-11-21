package com.elect3.mobdev.personalfinance.data.mock

/** Failure-once state belongs to each repository instance, not to these enum values. */
enum class MockScenario {
    Normal,
    Empty,
    InitialFailureOnce,
    // Synthetic UI demonstration only; no JWT, credentials, or token handling.
    SessionExpired,
    SecondPageFailureOnce,
}
