# OWLI AUTH-5K-D5 — Mock Demo diagnostic contract verification

## Baseline and change scope

Checkout: `<Android checkout>`.
Branch: `feature/android-lavender-midnight-ui`.
HEAD: `6c7f4c24c0bc2eac54e0cb3aa8d0708c5f3375d0`.

Preflight matched the AUTH-5K-D4 implementation and existing test infrastructure. No unexpected source differences or additional dependencies were found. Existing work was preserved. Only two new test files and this report were added; no existing files were edited.

Exact file inventory:

1. `android/app/src/test/java/com/elect3/mobdev/personalfinance/ui/auth/MockDemoDiagnosticContractTest.kt` — five offline JVM contract tests.
2. `android/app/src/androidTest/java/com/elect3/mobdev/personalfinance/ui/app/MockDemoDiagnosticContractTest.kt` — three Compose contract tests; compiled, not executed.
3. `docs/OWLI_AUTH-5K-D5_Mock_Demo_Diagnostic_Contract.md` — this report.

An aggregate SHA-256 fingerprint of all 61 files under `src/main`, `src/debug`, `src/release`, plus `app/build.gradle.kts` and the dependency catalog, was identical before and after:

```text
FF34B6A62A609EB6AA4B806F89614DEFF988C68F99D7D450D45767D619EBC550
```

Authentication ownership, token storage, expiry, refresh, logout behavior, diagnostic payload schemas and release logging were not changed. No runtime recorder was implemented.

## Tests added

The JVM fixture uses the real `InMemoryAuthRepository`, the real `LoginViewModel`, a fixed clock, a deferred fake `AuthApi`, and one shared typed recording sink. It never instantiates the HTTP transport. Fake transports count invocations without retaining credential/token arguments.

1. **Entry ordering:** the recording sink samples the model's state inside the synchronous `LOCAL_SESSION_CLEARED` callback. That observation is SignedOut; after the handler returns the state is MockDemo. The event's instance ID matches the real repository. No transport methods are called. The JVM test deliberately does not invoke the Compose effect manually.
2. **Repeated requests:** StateFlow publishes SignedOut then MockDemo only once, although a duplicate programmatic Demo request performs another existing local clear. No transport methods are called.
3. **Pending login guard:** with only a fake deferred login in progress, Demo entry creates no additional clear event or state publication.
4. **Authenticated guard:** a synthetic fake session cannot be silently converted to Demo or cleared by that request.
5. **Pending logout guard:** Demo cannot replace the in-progress signed-out/loading state; a simulated logout failure retains the unconfirmed-revocation result.

The Compose fixture uses the actual Login button/callback/gate with a real memory repository and fake transport:

1. Click Explore Mock Demo; assert the visible resource-backed header, repository clear before state publication, then `GATE_MOCK_DEMO` from the same model, with zero transport calls.
2. Repeat the request programmatically after the button leaves composition and trigger recomposition; expect another existing local clear but only one gate-transition claim.
3. During a fake pending login, verify the Demo button is disabled and a click does not publish Demo or clear again.

The instrumentation recorder is test-local and thread-safe. These cases require Android test execution to establish their runtime results; compilation alone is not reported as a pass of their UI assertions.

## Verification performed

Command from `android`, with existing caches and no dependency downloads:

```text
gradlew.bat --offline :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain
```

Result: **BUILD SUCCESSFUL**.

| Check | Result |
|---|---|
| Debug JVM suite | 108 tests, 13 suites; 0 failures/errors/skips |
| Release JVM suite | 110 tests, 14 suites; 0 failures/errors/skips |
| New Mock Demo JVM contract suite | 5 passed in each variant |
| Actual release no-op diagnostic tests | 2 passed |
| Debug APK assembly verification | Passed; existing production output was up to date |
| Instrumentation Kotlin compilation | Passed, including the three new Compose tests |
| Instrumentation/device execution | Not executed |

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
Its hash is unchanged from AUTH-5K-D2:

```text
5129E1EFB08120A6FA1A3CF1FC4EF51E9F468430F8B0FC2A78E2272958BB6F93
```

No device contact, real login, API startup, database access, revocation, migrations, commits or pushes occurred.

## Confirmed sequence and remaining uncertainty

Executed JVM tests establish:

```text
Demo handler accepted
  -> real repository clears local session
  -> shared sink receives LOCAL_SESSION_CLEARED while model is still SignedOut
  -> model publishes MockDemo
```

The remaining Compose stage has compiled coverage, not executed evidence in this phase:

```text
gate renders Mock Demo header
  -> gate's LaunchedEffect calls recordGateTransition
  -> shared sink receives GATE_MOCK_DEMO
```

These tests exercise diagnostic method calls and state semantics independently of Logcat. They do not prove the production Android logger accepted, retained or exposed either record on the ASUS. The historical AUTH-5K-D3 evidence gap and Activity destruction trigger remain unresolved. Dashboard idle must not be retroactively marked passed.

## Proposed bounded runtime recorder — requires separate approval

The production logger still has no independent history of attempted writes. If additional runtime infrastructure is approved, a minimal design would:

- Exist only in the debug variant; release retains its no-op implementation.
- Keep at most 128 typed records in process memory, evicting oldest entries with a numeric dropped-record count.
- Assign a synchronized monotonic sequence number before each Logcat attempt.
- Accept only the existing enum event, process-local IDs and approved primitive lifecycle fields; never credentials, tokens/hashes, account IDs, arbitrary strings, intent extras or financial data.
- Optionally retain only the numeric Log.d return result, never exception messages.
- Be process-scoped for diagnostic continuity, without owning or retaining authentication/session objects.
- Reset on process death and provide only an explicitly approved bounded snapshot operation; no disk persistence, background export or automatic network use.

This proposal is not implemented or authorized by this report. Snapshot access and its privacy/lifecycle tests should be reviewed before requesting approval to add it.

## Next recommendation

A separately authorized device run of only the new fake-transport Compose contract tests is justified before another unchanged manual Logcat-only test. It would verify the UI-to-effect path without credentials, API or database access. It does not itself verify the production Logcat transport.

If those assertions pass and the manual evidence gap remains, request explicit approval for the bounded debug recorder above. Do not begin device testing, recorder implementation, authentication redesign or transaction integration automatically.

Final Git baseline remains the expected branch/HEAD. The three added files join the pre-existing pending changes: 12 modified tracked files and 23 untracked files.
