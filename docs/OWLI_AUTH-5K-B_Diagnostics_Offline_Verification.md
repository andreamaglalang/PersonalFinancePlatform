# OWLI AUTH-5K-B — diagnostics and offline verification

## Scope and baseline

Authorized checkout: `<Android checkout>`.
Branch: `feature/android-lavender-midnight-ui`.
HEAD: `6c7f4c24c0bc2eac54e0cb3aa8d0708c5f3375d0`.
All pre-existing modified/untracked work was preserved. No API, database, device, emulator or real authentication request was used. No commits or pushes were made.

## Changes made in this phase

Paths below are relative to the checkout. Some modified files were already pending before this phase.

Runtime source:

- `android/app/src/main/java/com/elect3/mobdev/personalfinance/MainActivity.kt`: activity lifecycle and retained ViewModel correlation diagnostics; explicit diagnostic injection.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/auth/AuthRepository.kt`: injectable `java.time.Clock`, local-clear/expiry/no-session-logout diagnostics. In-memory storage and existing return contracts remain intact.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/auth/AuthDiagnostics.kt` (new): enum-only events, sink and process-local instance counters.
- `android/app/src/debug/java/com/elect3/mobdev/personalfinance/data/auth/AuthDiagnostics.kt` (new): `OwliAuth` Logcat implementation accepting only event enums and numeric instance IDs.
- `android/app/src/release/java/com/elect3/mobdev/personalfinance/data/auth/AuthDiagnostics.kt` (new): no-op implementation.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/remote/AuthApiClient.kt`: observes scoped logout outcomes; HTTP status handling and authentication contract are unchanged.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/data/remote/LogoutRequestObservation.kt` (new): pending/acknowledged/failed/interrupted request diagnostics. Every completed observation states that server revocation is not independently verified.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/auth/LoginViewModel.kt`: creation/clearing, successful entry, explicit logout, interruption and reset diagnostics.
- `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/app/OwliEntryGate.kt`: side-effect-only gate transition diagnostics; no authentication mutation during composition.

Test source:

- `android/app/src/test/java/com/elect3/mobdev/personalfinance/data/auth/InMemoryAuthRepositoryTest.kt`: immediately-before, exactly-at and after-expiry boundaries; scoped local clearing and no-session logout semantics.
- `android/app/src/test/java/com/elect3/mobdev/personalfinance/ui/auth/LoginViewModelTest.kt`: retained/fresh owners, gate observation, real repository/fake-clock expiry watcher and owner clearing during logout.
- `android/app/src/test/java/com/elect3/mobdev/personalfinance/data/remote/LogoutRequestObservationTest.kt` (new): synthetic acknowledgement, rejection, cancellation and exception privacy; no HTTP transport instantiated.
- `android/app/src/test/java/com/elect3/mobdev/personalfinance/testutil/RecordingAuthDiagnostics.kt` (new): typed recorder and mutable test clock.
- `android/app/src/androidTest/java/com/elect3/mobdev/personalfinance/MainActivityTest.kt`: Login-first startup/recreation; older finance-navigation tests explicitly enter Mock Demo.
- `android/app/src/androidTest/java/com/elect3/mobdev/personalfinance/ui/app/AuthGateTest.kt` (new): fake-repository authenticated gate, recomposition/navigation, Activity recreation, visible logout success/failure and Back-stack protection.

This report is also new. No unrelated UI, token persistence, token lifetime or automatic refresh changes were made.

## Diagnostics interpretation

The `OwliAuth` tag records only a fixed event name and process-local numeric IDs. Android Logcat supplies process/time context. These IDs do not represent account IDs or token families. Diagnostic APIs accept no credentials, account strings, headers, URLs, payloads or exception text.

- `ACTIVITY_CREATED` / `ACTIVITY_RESTORED`, destruction reason and `ACTIVITY_VIEW_MODEL_ATTACHED` distinguish a new Activity from reuse/replacement of its authentication model.
- `VIEW_MODEL_CREATED` includes the repository's diagnostic ID when applicable.
- `LOGIN_SUCCEEDED` and `GATE_AUTHENTICATED` identify successful local entry.
- `ACCESS_TOKEN_EXPIRED`, `LOCAL_SESSION_CLEARED`, `SESSION_RESET` and `GATE_SIGNED_OUT` identify the local expiry path.
- `EXPLICIT_LOGOUT_INITIATED` identifies a deliberate logout handler invocation.
- `LOGOUT_REQUEST_INITIATED`, `LOGOUT_HTTP_204_ACKNOWLEDGED`, `LOGOUT_REQUEST_FAILED` and `LOGOUT_REQUEST_INTERRUPTED` distinguish transport outcomes.
- `LOGOUT_NO_LOCAL_SESSION` distinguishes local no-op logout from a request.
- `SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED` explicitly avoids equating local clearing or an idempotent HTTP 204 with a changed database row.

Release uses the no-op variant. JVM tests inject a typed recorder or no-op sink, avoiding Android logging and network dependencies.

## Verification performed

Command, from `android`, using Andrea's cached Gradle/SDK environment:

```text
gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain
```

Final result: **BUILD SUCCESSFUL**.

| Verification | Result |
|---|---|
| Entire JVM unit suite | 93 tests, 11 suites; 0 failures, errors or skips |
| Repository authentication suite | 8 passed |
| Login ViewModel suite | 15 passed |
| Logout observation suite | 4 passed |
| Debug APK assembly | Passed |
| Android UI-test Kotlin compilation | Passed |
| Android UI/instrumentation execution | Not executed: device operations are outside this authorization |

The first sandbox build could not access its cached Gradle distribution and its wrapper attempted a blocked download. Verification succeeded in the normal Windows context using `--offline` and existing caches. No dependency versions were added or changed.

Built APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `39D8D2888F39035116EDA4D3BD9743015D5D03E686A63B239805F9D3D03F346A`.
This diagnostic APK has not been deployed.

## Confirmed behavior and remaining uncertainty

Offline tests establish that sessions are active immediately before expiry and invalid at/after expiry; the watcher clears the authenticated gate without calling logout. Reusing the ViewModelStore retains the authenticated model; replacing/clearing its owner starts a fresh signed-out model. Pending, failed and interrupted logout requests cannot be diagnosed as HTTP acknowledgements. Local clearing does not imply server revocation.

Actual Android recreation, recomposition and Back interactions now have compiled instrumentation coverage, but those cases have not been executed in this phase. The release variant was inspected, not built. The historical AUTH-5J-R1 cause and its server session's revocation remain unverified.

A separately authorized ASUS test is justified. Use this exact diagnostic APK, one private phone-side login and sanitized `OwliAuth` events. Record lifecycle/gate transitions, navigate promptly, invoke visible scoped Logout and check Back. Report HTTP acknowledgement separately from independent server revocation evidence. No device or authentication work should begin automatically from this report.
