# OWLI project handoff — 25 November 2026

This is the current setup entry point. It supersedes dated claims in `docs/developer-guide`, `docs/hermes-handoff`, and deployment evidence. Those documents remain useful design/history references, but their old implementation and verification status is not current.

## Clone and prerequisites

Development now follows the [Git workflow](../DEVELOPMENT_WORKFLOW.md). The clone
command below requires `dev` initialization to be complete. Handoff is the
original baseline and recovery reference; new work belongs on task branches
from synchronized `dev`, with verified changes integrated back into `dev`.

```powershell
git clone --branch dev https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
```

Install .NET SDK 10 (verified with 10.0.402), JDK 17, Android Studio with Android SDK platform 36 and Build Tools 36.0.0, and SQL Server Express LocalDB on Windows. Visual Studio is optional; use a version supporting .NET 10 and `.slnx`. SQL Server and Android Studio are unnecessary for offline .NET tests. Restore needs NuGet, Google Maven, Maven Central, and Gradle distribution access. Exact application package versions are declared in `.csproj` and `android/gradle/libs.versions.toml`; Gradle wrapper 9.1.0, AGP 9.0.1, Kotlin 2.2.10, Room 2.8.4 and KSP 2.3.4 are included. Do not substitute a globally installed Gradle.

```powershell
dotnet restore PersonalFinancePlatform.slnx
dotnet build PersonalFinancePlatform.slnx --no-restore
./tools/Test-Handoff.ps1
cd android
./gradlew.bat assembleDebug testDebugUnitTest testReleaseUnitTest
cd ..
```

Android Studio creates the ignored `android/local.properties`; alternatively set `ANDROID_HOME` to your own SDK directory. The wrapper, including its JAR, is in source. APK output is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Configure and run

Read [DATABASE.md](DATABASE.md) before initializing a database. All commands below are for a new private development machine and a fresh local database. Do not point them at the existing deployment or another person's database.

Copy `config/development.env.example.ps1` to an ignored local file outside the repository, replace its placeholders, then dot-source it in each terminal that needs the settings. Generate a different JWT key on each development machine:

```powershell
$bytes = New-Object byte[] 64
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:Jwt__SigningKeyBase64 = [Convert]::ToBase64String($bytes)
```

Keep that value in your private environment or API user-secret store if you want sessions to survive restarts. Do not print it, commit it, or share it with clients. `dotnet user-secrets set --project PersonalFinance.Api Jwt:SigningKeyBase64 $env:Jwt__SigningKeyBase64` stores it locally. Environment variables override JSON and user secrets.

```powershell
dotnet dev-certs https --trust
# API terminal, after database initialization and environment configuration:
dotnet run --project PersonalFinance.Api --no-launch-profile --urls https://localhost:7027
# Separate MVC terminal, with BackendApi__BaseUrl and Development environment:
dotnet run --project PersonalFinance.AdminMvc --no-launch-profile --urls https://localhost:7150
```

Open `https://localhost:7150` for MVC. Members and administrators land in different areas. Register a synthetic member through MVC registration or `POST /api/auth/register`, then sign in; registration does not issue a login session. API OpenAPI is `https://localhost:7027/openapi/v1.json` in Development. API and MVC enforce HTTPS; MVC calls the API server-side and keeps bearer/refresh tokens out of browser scripts. Restarting MVC loses its in-process sessions. Do not run multiple MVC instances without replacing session storage.

For a local administrator, run `dotnet run --project tools/PersonalFinance.AdminProvisioning` interactively from the repository root with both environment names set to Development. It prompts for a new username, email and password and deliberately permits only Windows-authenticated `(localdb)\MSSQLLocalDB / PersonalFinanceDb`. Do not disable its target guard or import the owner's accounts.

## Android

Open `android/` in Android Studio and use a new emulator or a dedicated test device. Debug currently selects `https://localhost:7027/`; use `adb reverse tcp:7027 tcp:7027` for that device. Export your own public localhost development certificate (`dotnet dev-certs https --export-path <private-local-path>/localhost.cer --format PEM`, without `--password`), and install it as a trusted user certificate on the emulator. Confirm its hostname and trust before signing in. Only debug localhost trusts user certificates; release trusts system roots and has no selected API endpoint. Do not ship debug trust in release or bypass hostname verification. The repository contains no original laptop certificate or private key.

Mock Demo is available for synthetic UI exploration; it does not establish a server login. For live mode, start your local API and use a synthetic registered account. Local Room/session/preferences state belongs to the device. Sign out before handing a device to another person. Release endpoint selection is a remaining integration task; configure `src/release/.../data/remote/ApiEnvironment.kt` for a production HTTPS origin when deploying your own installation.

## Hangfire

Leave `Hangfire__Enabled=false` for ordinary setup. The active integration uses `FinanceJobs` and `HangfireSetup`: daily bill reminders at 07:00, hourly budget alerts, and monthly summaries on day 1 at 00:30, all Asia/Manila. Explicit activation requires a separate `OWLI_Hangfire` catalog on the finance SQL instance, `ConnectionStrings__Hangfire`, and the schema from the installed Hangfire.SqlServer package reviewed and installed into that disposable catalog. Automatic schema preparation is disabled. No dashboard endpoint is exposed. Starting the API with the flag true starts the worker and registers recurring jobs.

The newer alternative `*AutomationJobs` / `HangfireJobRegistration` classes are preserved as source with their guarded SQL tests but are not registered alongside `FinanceJobs`; see [ISSUES.md](ISSUES.md) before choosing one implementation. Never enable both job families.

## Continue development

Read [SOURCE_MAP.md](SOURCE_MAP.md), [ISSUES.md](ISSUES.md), and [VERIFICATION.md](VERIFICATION.md). Create your own task branch from the latest synchronized `dev`; preserve this handoff as the original baseline. No original database, Cloudflare tunnel, account, certificate, secret store, absolute SDK path, or deployment service is required. Existing deployment scripts are historical opt-in material; none runs during setup, build, or tests.
