# Backend (Web API) Setup

Project: `PersonalFinance.Api/PersonalFinance.Api.csproj` (references Domain and Infrastructure). Target `net10.0`.

## Key NuGet packages (from the project files)

EF Core SqlServer/Design 10.0.13 · Identity.EntityFrameworkCore 10.0.13 · Authentication.JwtBearer 10.0.13 ·
AspNetCore.OpenApi 10.0.13 · Hangfire.Core/NetCore/SqlServer 1.8.25 · System.Security.Cryptography.Pkcs 10.0.13.
Local tool: `dotnet-ef` 10.0.13 (`.config/dotnet-tools.json`).

## Configuration keys (exact names used by the code)

| Key | Where it is read | Required | Notes |
| --- | --- | --- | --- |
| `ConnectionStrings:DefaultConnection` | `Infrastructure/DependencyInjection.cs`, design-time factory | for any DB access | Empty means the API starts, but every DB call fails. There is no fallback database |
| `Jwt:Issuer` | `Api/Authentication/JwtSettings.cs` | yes | `appsettings.json` = `PersonalFinance.Api` |
| `Jwt:Audience` | same | yes | `appsettings.json` = `PersonalFinance.Clients` |
| `Jwt:SigningKeyBase64` | same | **yes, secret** | Base64 of ≥ 32 random bytes; validated at startup (`ValidateOnStart`) |
| `CurrencyPolicy:MaximumRateAgeDays` | `Infrastructure/CurrencyPolicyOptions.cs` | no | Default 7 |
| `Hangfire:Enabled` | `Infrastructure/Jobs/HangfireSetup.cs` | no | Only the string `true` enables it. **Keep `false`** |
| `OWLI_DEV_CERT_THUMBPRINT` (environment variable) | `Api/Program.cs` | only for the Android phone mode | Development only; see "HTTPS" below |
| `ASPNETCORE_ENVIRONMENT` | ASP.NET Core | yes | `Development` locally. `/api/dev/*` exists only in Development |
| `Logging:LogLevel:*` | `appsettings.json` | no | Default `Information`, `Microsoft.AspNetCore` `Warning` |
| `AllowedHosts` | ASP.NET Core | no | `*` in `appsettings.json` |

Hard-coded (not configurable): Frankfurter base URL `https://api.frankfurter.dev/v2/` (5 s timeout), access-token
lifetime **15 minutes**, refresh-token lifetime **7 days**, JWT `ClockSkew = 0`, auth rate limit **20 requests/minute
per IP**, Identity password policy (12+ characters, upper, lower, digit, symbol, 4 unique), lockout after 5 failures
for 15 minutes.

**Refresh tokens** rotate on every refresh. Reusing an already-revoked token revokes the whole token family.
`logout` revokes the presented family and `logout-all` revokes all of the user's sessions. Tokens are stored hashed
(`RefreshTokens` table). Every authenticated request rechecks that the user exists, is not locked out, and that
their roles match the token. A role change invalidates outstanding access tokens.

**CORS:** not configured. That is fine: Android (native) and MVC (server-side) are not browser cross-origin clients.
Do not add a permissive CORS policy.

## Local secrets (never committed)

```powershell
cd OWLI
# 32 random bytes, base64. Generate locally; never paste the value into chat, docs or issues.
$bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($bytes); $key = [Convert]::ToBase64String($bytes)
dotnet user-secrets --project PersonalFinance.Api set "Jwt:SigningKeyBase64" $key
dotnet user-secrets --project PersonalFinance.Api set "ConnectionStrings:DefaultConnection" "Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Trusted_Connection=True;Encrypt=True;TrustServerCertificate=True"
```

`TrustServerCertificate=True` is acceptable **only** for local LocalDB. See `config-examples/` for the shape of
`appsettings.Development.json` if you prefer a file (it is git-ignored). The administrator provisioning tool reads the
same API configuration (JSON → API user-secrets → environment variables).

## HTTPS and ports (`Properties/launchSettings.json`)

| Profile | URLs |
| --- | --- |
| `https` | `https://localhost:7027` and `http://localhost:5015` |
| `http` | `http://localhost:5015` (auth and finance routes **reject HTTP** with 400, so it is not useful) |

```powershell
dotnet dev-certs https --trust
```

**Android phone mode:** when `ASPNETCORE_ENVIRONMENT=Development` **and** `OWLI_DEV_CERT_THUMBPRINT` is set, the API
ignores the launch profile and listens **only** on `https://localhost:7027` using that exact certificate from
`CurrentUser\My`. The certificate must be currently valid, have a private key, an explicit `localhost` SAN and the
Server Authentication EKU. The ASP.NET Core dev certificate meets these. See ANDROID_SETUP.md.

## Run

```powershell
$env:ASPNETCORE_ENVIRONMENT = "Development"
$env:Hangfire__Enabled = "false"
dotnet build PersonalFinance.Api -c Debug
dotnet run --project PersonalFinance.Api --launch-profile https
# OpenAPI document (Development only): https://localhost:7027/openapi/v1.json
```

The API applies **no migrations at startup** (there is no `Migrate()` or `EnsureCreated()`). Create the database first
(DATABASE_SETUP.md). Only one process can hold port 7027.

## Administrator account

Accounts registered through `POST /api/auth/register` get the `User` role. An `Administrator` is created only by the
guarded tool `tools/PersonalFinance.AdminProvisioning`. Its requirements:

- `DOTNET_ENVIRONMENT`/`ASPNETCORE_ENVIRONMENT` = `Development`;
- the connection must be exactly `(localdb)\MSSQLLocalDB` / `PersonalFinanceDb` with Windows authentication. It then
  verifies `IsLocalDB = 1` and the database name before writing;
- typing the confirmation phrase `CREATE DEVELOPMENT ADMINISTRATOR IN PersonalFinanceDb`;
- email, a **separate username** (new in this branch), full name and a policy-compliant password, entered at prompts.

```powershell
$env:DOTNET_ENVIRONMENT = "Development"; $env:ASPNETCORE_ENVIRONMENT = "Development"
dotnet run --project tools/PersonalFinance.AdminProvisioning
```

The account receives both the `User` and `Administrator` roles. **Login stays email + password** (the username is
not a login identifier). Details: `docs/Development_Admin_Provisioning.md`. Run it only after the database exists and
the owner agrees.

## Hangfire

See [ARCHITECTURE.md](ARCHITECTURE.md) and [KNOWN_ISSUES.md](KNOWN_ISSUES.md). Summary: when `Hangfire:Enabled=true`,
the API registers SQL Server storage on `DefaultConnection` with `PrepareSchemaIfNecessary = false` (it **never** creates
the `HangFire.*` tables), a Hangfire server, and three recurring jobs in the machine's local time zone:

| Job id | Method | Cron | Retry / concurrency |
| --- | --- | --- | --- |
| bill reminders | `SendBillRemindersAsync` | `0 7 * * *` (daily 07:00) | `AutomaticRetry(3)`, `DisableConcurrentExecution(600s)` |
| budget alerts | `SendBudgetAlertsAsync` | `0 * * * *` (hourly) | same |
| monthly summaries | `GenerateMonthlySummariesAsync` | `30 0 1 * *` (1st of month 00:30) | `AutomaticRetry(3)`, `DisableConcurrentExecution(1800s)` |

There is no Hangfire dashboard. Enabling Hangfire without installing its schema makes the API fail at runtime,
so leave it disabled for local development and use the Development-only trigger
`POST /api/dev/jobs/{budget-alerts|monthly-summaries}/run` with an Administrator token. **There is no trigger for
bill reminders**, so that job only runs under Hangfire. Jobs are idempotent per deduplication key, so a second run
creates nothing new.
