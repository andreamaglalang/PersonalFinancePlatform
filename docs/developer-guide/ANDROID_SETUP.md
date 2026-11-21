# Android Setup

Project root: `android/` (Gradle root project, single module `:app`). Package/applicationId
`com.elect3.mobdev.personalfinance`, version 0.1.0.

## Toolchain (from the build files)

| Item | Version |
| --- | --- |
| Gradle wrapper | 9.1.0 (`android/gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.2.10, KSP 2.3.4 |
| JDK | 17 (`sourceCompatibility`/`jvmTarget` 17) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Compose BOM | 2025.08.00; Activity Compose 1.10.1; Lifecycle 2.9.2; Navigation 2.9.8 |
| Room | 2.8.4 (via KSP) |
| Coroutines | 1.10.2 |
| Tests | JUnit 4.13.2; AndroidX test runner 1.7.0, ext-junit 1.3.0 |

Versions live in `android/gradle/libs.versions.toml`. Networking uses the platform `HttpsURLConnection` and JSON
parsing; there is no Retrofit or OkHttp.

## First build

1. Open `android/` in Android Studio and let it sync. It creates `android/local.properties` with your SDK path.
   That file is **git-ignored** and machine-specific. Alternatively set `ANDROID_HOME`.
2. Command line (from `android/`):
   ```powershell
   .\gradlew.bat :app:assembleDebug            # APK: app\build\outputs\apk\debug\app-debug.apk
   .\gradlew.bat :app:testDebugUnitTest        # JVM unit tests (64 test classes)
   .\gradlew.bat :app:connectedDebugAndroidTest   # instrumented tests (23 classes): needs a device/emulator
   ```
3. Install and launch:
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n com.elect3.mobdev.personalfinance/.MainActivity
   ```

**Mock Demo** (selectable in the app) uses built-in sample data and needs **no server**. It is the safest way to show the UI.

## Architecture inside the app

MVVM: Compose screens under `ui/` with ViewModels; repositories under `data/`; pure logic under `domain/`.
- `data/auth/AuthRepository.kt`: login, refresh rotation shortly before expiry, logout. **Tokens are held in memory
  only** (no disk writes), so killing the app requires signing in again.
- `data/remote/`: `FinanceTransport` resources `categories`, `transactions`, `budgets`, `exchange-rates/quote`, plus `auth/*`.
- `data/local/room/OwliDatabase.kt`: Room database `owli-local.db`, **version 2**, entities `LocalTransactionEntity` and
  `LocalCategoryEntity`, explicit `MIGRATION_1_2`, **no destructive fallback** (it never drops pending expenses).
  Exported schemas: `android/app/schemas/`. Any entity change needs a new version **and** an explicit migration.
- Offline-first: new expenses are saved locally as `Pending`, then `PendingTransactionSync` posts them with a
  `ClientId` (server-side idempotent) and marks each `Synced` or `Rejected`. Categories and transaction history are cached.
- Reports and insights are computed **on the device** from synced data (`domain/reports/FinancialReportCalculator.kt`).
  The app does not call `/api/reports/monthly`.

## API base URL and networking

| Build | Base URL | Trust |
| --- | --- | --- |
| debug | `https://localhost:7027/` (`app/src/debug/.../data/remote/ApiEnvironment.kt`) | system CAs + the bundled `@raw/owli_localhost_dev_certificate`, for `localhost` only |
| release | **not configured, on purpose** | system CAs only; `usesCleartextTraffic="false"` |

Because the URL is `localhost`, the phone or emulator must forward its port 7027 to the PC with **`adb reverse`**.
This works the same for a USB phone and an emulator:

```powershell
adb devices                       # exactly one device/emulator
adb reverse tcp:7027 tcp:7027
```

Do **not** switch to `10.0.2.2` or a LAN IP: the certificate and network-security config only cover `localhost`, and
cleartext is disabled. Do not add trust-all managers or hostname-verifier overrides, and never relax the release config.

- **Emulator:** create an AVD with API 26–36 (a Google APIs image is fine), start it, run the `adb reverse` above.
- **Physical device:** enable Developer options and USB debugging, connect over USB, accept the RSA prompt, run `adb reverse`.
- **HTTPS probe:** `adb shell am start -n com.elect3.mobdev.personalfinance/.MainActivity --ez owli.debug.CHECK_HTTPS true`,
  then read `adb logcat -s OwliHttpsProbe`. It calls `/openapi/v1.json`, which exists only when the API runs in Development.

## Development certificate on the new PC (required for any real API connection)

The bundled `android/app/src/debug/res/raw/owli_localhost_dev_certificate.cer` is the **original PC's** public
localhost certificate, so the app will not trust the new PC's API until you replace it. Use only the public part:

```powershell
dotnet dev-certs https --trust
Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -eq 'CN=localhost' } | Format-List Thumbprint, NotAfter, HasPrivateKey
# choose the ASP.NET Core dev cert (HasPrivateKey = True, valid NotAfter); then export PUBLIC KEY ONLY (DER):
Export-Certificate -Cert Cert:\CurrentUser\My\<THUMBPRINT> -FilePath android\app\src\debug\res\raw\owli_localhost_dev_certificate.cer
```

Then start the API in phone mode (the API listens only on `https://localhost:7027` with that certificate):

```powershell
$env:ASPNETCORE_ENVIRONMENT = "Development"
$env:OWLI_DEV_CERT_THUMBPRINT = "<THUMBPRINT>"
$env:Hangfire__Enabled = "false"
dotnet run --project PersonalFinance.Api --no-launch-profile
```

Rebuild and reinstall the debug APK. **Never** export a `.pfx`, `.key` or private key, and never commit one (`*.pfx`,
`*.p12`, `*.key` are git-ignored). Whether to commit the replaced public `.cer` is the team's decision: it is public,
but machine-specific. Note the swap in your commit message if you do.

## Feature status (Android)

| Feature | Status |
| --- | --- |
| Login / logout / token refresh | Implemented (registration is not an Android workflow) |
| Dashboard, transaction entry (income/expense), history, edit online | Implemented |
| Categories (cached) | Implemented |
| Budgets (month navigation, stale/offline states) | Implemented |
| Currency converter + live FX quote | Implemented (quote requires sign-in; Mock Demo explains this) |
| Reports and insights | Implemented, computed on the device |
| Room offline-first recording + sync | Implemented; **device↔API sync not re-verified in the 2026-11-21 verification** |
| Savings goals | **Placeholder only** ("Savings goals are being added"); not wired to `/api/savings-goals` |
| Recurring bills | **Placeholder only** ("Recurring bills are coming soon"); not wired to `/api/recurring-bills` |
| App launcher icon | No custom `android:icon` in the manifest, so the system default icon is used |
