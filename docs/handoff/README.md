# OWLI project handoff — 25 November 2026

**Current startup procedure:** [Persistent local development setup](../DEVELOPER_SETUP.md). This dated handoff is historical; the API no longer hosts Hangfire.

This was the original setup entry point. It supersedes dated claims in `docs/developer-guide`, `docs/hermes-handoff`, and deployment evidence. Those documents remain useful design/history references, but their old implementation and verification status is not current.

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

For current one-time private configuration and exact Windows/macOS/Linux commands,
use [persistent local development setup](../DEVELOPER_SETUP.md). API, MVC and Worker
have project user-secret stores; ordinary startup does not require dot-sourcing
an environment file. The API's default profile is HTTPS. Root commands:

```sh
dotnet run --project PersonalFinance.Api
dotnet run --project PersonalFinance.AdminMvc
```

MVC is `https://localhost:62978`; API is `https://localhost:7027`. Use approved local
testing storage and preserve existing credentials/certificates. Administrator
creation remains a separate guarded interactive operation; see
[the current provisioning guide](../Development_Admin_Provisioning.md).

## Android

Open `android/` in Android Studio and use a new emulator or a dedicated test device. Debug currently selects `https://localhost:7027/`; use `adb reverse tcp:7027 tcp:7027` for that device. Export your own public localhost development certificate (`dotnet dev-certs https --export-path <private-local-path>/localhost.cer --format PEM`, without `--password`), and install it as a trusted user certificate on the emulator. Confirm its hostname and trust before signing in. Only debug localhost trusts user certificates; release trusts system roots and has no selected API endpoint. Do not ship debug trust in release or bypass hostname verification. The repository contains no original laptop certificate or private key.

Mock Demo is available for synthetic UI exploration; it does not establish a server login. For live mode, start your local API and use a synthetic registered account. Local Room/session/preferences state belongs to the device. Sign out before handing a device to another person. Release endpoint selection is a remaining integration task; configure `src/release/.../data/remote/ApiEnvironment.kt` for a production HTTPS origin when deploying your own installation.

## Hangfire

The API does not host processors or recurring registration. `PersonalFinance.Worker`
is the independent console host. Ordinary launch exits disabled. The explicit
`DevelopmentTest` profile validates disposable SQL catalogs, certificate validation,
compatible prepared schema and absence of another registered worker before starting.
It never provisions storage. See [current startup](../DEVELOPER_SETUP.md) and
[worker implementation](../worker-config/README.md#implementation-and-verification-r2).
Operational storage activation requires separate approval; never enable the removed
alternative job family or infer production readiness from local test evidence.

## Continue development

Read [SOURCE_MAP.md](SOURCE_MAP.md), [ISSUES.md](ISSUES.md), and [VERIFICATION.md](VERIFICATION.md). Create your own task branch from the latest synchronized `dev`; preserve this handoff as the original baseline. No original database, Cloudflare tunnel, account, certificate, secret store, absolute SDK path, or deployment service is required. Existing deployment scripts are historical opt-in material; none runs during setup, build, or tests.
