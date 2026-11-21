# OWLI AUTH-5K-D2 — bounded lifecycle diagnostics

## Baseline and scope

Checkout: `D:\Final Project\PersonalFinancePlatform-Android`.
Branch: `feature/android-lavender-midnight-ui`.
HEAD: `6c7f4c24c0bc2eac54e0cb3aa8d0708c5f3375d0`.

The source matched the AUTH-5K-B/AUTH-5K-D1 baseline; no unexpected differences were found. Existing pending work was preserved. This phase made only diagnostic/test/build-test configuration changes. Authentication ownership, in-memory token storage, expiry, refresh and logout policies were not changed.

No API startup, device/emulator contact, database access, session revocation, real authentication, migrations, commits or pushes occurred.

## Exact file inventory for this phase

Modified (including files already untracked before this phase):

1. `android/app/build.gradle.kts` — enables the release JVM test component so the real release no-op implementation is tested; no release APK is assembled.
2. `android/app/src/main/java/com/elect3/mobdev/personalfinance/MainActivity.kt` — bounded snapshots at lifecycle callbacks, attachment correlation, numeric new-intent flags, observation of Back key-up/legacy callback while delegating unchanged.
3. `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/auth/AuthDiagnostics.kt` — fixed events, enabled flag, typed lifecycle sink method and payload-free event formatter.
4. `android/app/src/debug/java/com/elect3/mobdev/personalfinance/data/auth/AuthDiagnostics.kt` — debug-only rendering of typed records to `OwliAuth`.
5. `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/app/OwliApp.kt` — typed tab-navigation and Transactions Back observations.
6. `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/app/OwliEntryGate.kt` — routes existing Demo Back through its observation wrapper.
7. `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/auth/LoginViewModel.kt` — Demo Back wrapper; existing authentication/session methods and ownership are unchanged.
8. `android/app/src/test/java/com/elect3/mobdev/personalfinance/testutil/RecordingAuthDiagnostics.kt` — records typed lifecycle snapshots for offline assertions.
9. `android/app/src/test/java/com/elect3/mobdev/personalfinance/ui/auth/LoginViewModelTest.kt` — confirms lifecycle observation and Demo Back do not add authentication operations or change session behavior.
10. `android/app/src/androidTest/java/com/elect3/mobdev/personalfinance/ui/app/AuthGateTest.kt` — verifies navigation/Back diagnostic events in the fake-repository harness; compiled only.

New:

11. `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/auth/ActivityLifecycleDiagnostics.kt` — primitive-only snapshot/record types, classification, bounded formatting and pass-through Back observer.
12. `android/app/src/test/java/com/elect3/mobdev/personalfinance/data/auth/ActivityLifecycleDiagnosticsTest.kt` — eight classification, privacy, formatting and Back-delegation tests.
13. `android/app/src/testRelease/java/com/elect3/mobdev/personalfinance/data/auth/ReleaseAuthDiagnosticsTest.kt` — two tests exercising the actual release object with Android logging unavailable.
14. `docs/OWLI_AUTH-5K-D2_Lifecycle_Diagnostics_Offline_Verification.md` — this report.

The existing release logger remains unchanged and delegates to `NoAuthDiagnostics`. Repository, HTTP authentication client and logout-request observation implementations were not modified in this phase.

## Diagnostic event inventory

Existing auth/session events remain available. Lifecycle/navigation observations now include:

- Creation: `ACTIVITY_CREATED` or `ACTIVITY_RESTORED` according to incoming saved-instance-state presence.
- Correlation: `ACTIVITY_VIEW_MODEL_ATTACHED`.
- Lifecycle: `ACTIVITY_STARTED`, `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`.
- Destruction: `ACTIVITY_DESTROYED` or `ACTIVITY_DESTROYED_FOR_CONFIGURATION`.
- Relaunch delivery: `ACTIVITY_NEW_INTENT`.
- Back: `BACK_KEY_UP`, `BACK_ACTIVITY_CALLBACK`, `BACK_TRANSACTIONS_TO_DASHBOARD`, `BACK_DEMO_TO_LOGIN`.
- Navigation: `NAV_DASHBOARD_SELECTED`, `NAV_TRANSACTIONS_SELECTED`.

Each lifecycle line has only:

```text
event=<fixed enum> instance=<Activity counter> related=<ViewModel counter or 0>
finishing=<boolean> changingConfig=<boolean> task=<integer> taskRoot=<boolean>
savedState=<boolean> flags=0x<unsigned 32-bit hexadecimal>
```

`savedState` describes the bundle received when that Activity was created; it does not claim restoration of authentication. `ACTIVITY_NEW_INTENT` records only the incoming numeric flags, without reading extras/data/actions or changing the Activity's intent. Other callbacks report the Activity's current intent flags. Instance counters are process-local, not account/session identifiers.

The formatting/sink APIs accept no arbitrary payload strings, credentials, tokens/hashes, headers, account identifiers, financial values, exception messages or full system logs. Extreme numeric values remain bounded to a line under 256 characters in tests. Release logging is disabled; lifecycle snapshot collection is guarded by the variant's enabled flag.

Back observation delegates the original handler exactly once and preserves its result/exception. It installs no consuming callback. The legacy Activity callback is particularly relevant to the ASUS/API 28; newer predictive-Back entry paths are not comprehensively observed by that callback. Existing Transactions and Demo handlers also record their own fixed event independently.

## Offline verification

Final command, from `android`, using existing Gradle/SDK caches:

```text
gradlew.bat --offline :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain
```

Final result: **BUILD SUCCESSFUL**.

| Check | Result |
|---|---|
| Debug JVM suite | 103 tests, 12 suites; 0 failures/errors/skips |
| Release JVM suite | 105 tests, 13 suites; 0 failures/errors/skips |
| New lifecycle/privacy/Back suite | 8 passed per variant |
| Login ViewModel suite | 17 passed per variant |
| Actual release no-op suite | 2 passed |
| Debug APK assembly | Passed |
| Debug instrumentation Kotlin compilation | Passed |
| Instrumentation execution | Not executed; no device/emulator contact |
| Scoped Android diff whitespace check | Passed |

The initial verification command found no release JVM task. After enabling that test component and correcting the Gradle builder typing, the final command above passed. No dependency versions were changed.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `5129E1EFB08120A6FA1A3CF1FC4EF51E9F468430F8B0FC2A78E2272958BB6F93`.
The APK was not deployed. The existing APK from the prior phase has been replaced only in ignored local build output.

## Remaining unknowns

The historical Activity-destruction initiator is still unverified. These diagnostics can distinguish finishing/configuration flags, task context and observed Back/navigation entry paths on a future authorized run, but cannot retroactively establish an Android/OEM task-manager decision. `isFinishing=true` would indicate finishing state, not identify its caller. Absence of a Back event alone is not conclusive if logs were lost or an unobserved platform path was used.

The earlier test server session's status was not rechecked or changed. The new UI/lifecycle instrumentation tests were compiled, not executed physically.

## Proposed fake-session device procedure — not authorized/executed here

1. Obtain separate authorization for device installation, sanitized log collection and test-only instrumentation. Keep API, forwarding, database and genuine authentication disabled.
2. Use actual `MainActivity` at Login and in explicit Mock Demo to observe foreground, Home/background/reopen, rotation, and coordinated Back. Record only the new bounded fields and `OwliAuth` events. Mock Demo is not authenticated and its root Back policy differs from authenticated Dashboard.
3. Separately package/run the existing `AuthGateTest` fake-repository fixture to verify gate continuity, ordinary recreation and Back behavior without network or credentials. This fixture uses a `ComponentActivity` host; it does not reproduce every actual `MainActivity` lifecycle path.
4. For an exact fake-authenticated `MainActivity` finish/relaunch scenario, first authorize a test-only dependency-injection harness/scenario. The current production APK deliberately has no fake authenticated-login switch. Do not use real credentials to fill that gap implicitly.
5. Compare Activity/ViewModel IDs, task IDs, finishing/configuration flags and Back events before drawing a trigger conclusion. Stop on unexpected behavior, preserve sanitized evidence and clean up only test-owned resources.

No deployment or runtime testing should begin automatically from this report.
