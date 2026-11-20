# OWLI Android — ELECT3 / MOBDEV

The single-module Kotlin/Compose application uses Material 3, MVVM, StateFlow, coroutines and Navigation Compose. MainActivity supplies retained ViewModels and repositories through manual factories. Screens do not construct repositories during composition.

## Implemented

- Login, Mock Demo entry and logout; authenticated sessions remain in memory.
- Activity transaction listing, bounded pagination, filters, search, ordering, retry and refresh.
- DemoSession shares an in-memory synthetic transaction ledger between creation, Activity and Dashboard. Leaving demo resets the session.
- Demo income/expense forms save locally; authenticated creation uses the existing API repository and refreshes account-owned views.
- Dashboard income, expense, net flow, recent records and spending presentation. Net flow is not an account balance. PHP amounts use BigDecimal.
- Category selection/management and local category presentation preferences.
- Lavender & Midnight theme. Concurrent account/appearance work exists but is outside this verification milestone.

Networking currently uses native HTTPS transport and Gson. Retrofit, OkHttp and Room are not installed. Local preferences are presentation settings, not a financial database or credential store. Debug uses existing development configuration; release API configuration remains unconfigured. Never commit credentials or machine-specific configuration.

## Remaining work and limits

Budgets, Savings Goals, Insights and registration are not completed Android workflows. Planned screens do not establish implemented functionality. Remote bounded pagination does not prove an atomic server snapshot. Live API integration and concurrent account/appearance changes are not certified by the mock tests.

## Build configuration

Minimum SDK 26; compile/target SDK 36; Build Tools 36.0.0. Gradle 9.1.0, AGP 9.0.1 with built-in Kotlin 2.2.10, Compose compiler 2.2.10 and Compose BOM 2025.08.00. Java/Kotlin target 17; local Adoptium JDK 17 was available for this verification. Versions reside in gradle/libs.versions.toml. Existing wrapper checksum pins remain unchanged.

## Verification — 2026-11-19

AuthenticatedSaveNavigationTest now uses a shared DemoSession and the real Dashboard route. Its new mock expense test checks ledger persistence, Activity visibility, Dashboard totals, repeated tab navigation, synthetic-data labels and zero calls through injected authentication/GET/POST/category-write engines. Transport is fake; no API or real credentials are used.

Executed offline from android/:

```powershell
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest --rerun :app:testReleaseUnitTest --rerun --console=plain
```

Both commands succeeded. Fresh JVM XML reports: debug 341 tests, release 343 tests; zero failures/errors. Debug APK assembly passed; focused instrumentation source compiled. The first command reused cached unit-test results; the second explicitly reran the JVM tasks.

The new instrumentation test has NOT executed on-device. One physical device is available; APK deployment is awaiting clarification of the no-new-installation restriction. Historical retained instrumentation evidence covers 23 Activity/list tests and does not verify this new workflow. Complete save → Activity → Dashboard behavior therefore remains NOT YET DEVICE-VERIFIED. No production defect was demonstrated or fixed.

Concurrent working-tree changes mean results describe the tested snapshot. No clean operation, dependency addition, SQL/API startup, live authentication or Git mutation was performed. Device execution requires accepted standard app/test APK deployment without clearing app data.
