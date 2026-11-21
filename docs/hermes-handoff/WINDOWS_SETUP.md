# Windows Setup

Versions below are the ones the project was built and tested with on 2026-11-21.

| Tool | Version / notes |
| --- | --- |
| Git for Windows | Any recent version. Keep `core.autocrlf=true` (the repository has CRLF files) |
| .NET SDK | **10.0.x** (validated: 10.0.402). All projects target `net10.0`. No `global.json` pins the SDK |
| Visual Studio or Rider | A version that supports .NET 10 and `.slnx`. Workload: ASP.NET and web development |
| SQL Server Express LocalDB | Validated instance version 17.0 (SQL Server 2025 LocalDB). Default instance `MSSQLLocalDB` |
| `sqlcmd` (optional) | For read-only inspection only |
| dotnet-ef | Pinned local tool 10.0.13: run `dotnet tool restore` in the repo root |
| Android Studio | A version that supports AGP 9.0.1 / Gradle 9.1 and compileSdk 36 |
| JDK | **17** (Android Studio's bundled JBR 17 works; set `JAVA_HOME` for command-line Gradle) |
| Android SDK | Platform 36, matching build-tools, platform-tools (`adb`). Set `ANDROID_HOME` |
| Internet | NuGet, Maven/Google, Gradle distribution, and `api.frankfurter.dev` (runtime FX) |

## Steps

```powershell
winget install Git.Git
winget install Microsoft.DotNet.SDK.10
# SQL Server Express LocalDB: install from the SQL Server Express installer ("LocalDB" option) or with Visual Studio
winget install Google.AndroidStudio

git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git switch integration/hermes-handoff-20261121
dotnet tool restore
dotnet --list-sdks
sqllocaldb info          # should list MSSQLLocalDB
```

After this, follow VERIFICATION.md to confirm the toolchain before configuring anything else.

## Files that must stay local (already git-ignored)

`PersonalFinance.Api/appsettings.Development.json`, `android/local.properties`, `*.hprof` heap dumps, `*.pfx`/`*.key`,
`bin/`, `obj/`, `android/**/build/`, `deployment/out*/`, and `.env` files. Check `git status` before every commit.

**Not ignored:** `PersonalFinance.AdminMvc/appsettings.Development.json`. Configure the MVC site with environment
variables instead (MVC_SETUP.md), so nothing machine-specific gets committed by accident.
