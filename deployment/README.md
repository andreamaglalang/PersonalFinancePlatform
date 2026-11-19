# OWLI server handoff

Status: Phase 2 source-integrated candidate, validated offline, NOT authorized for live deployment. No server platform has been supplied. No server, database, certificate, DNS, service or background-job changes were performed.

## Baseline and source discovery

Deployment worktree: <deployment-worktree>
Branch: deployment/owli-server
MVC baseline: cab4e2b61c3707b8bff2a8f4d018fa69da4ae565. Backend: e454262b2c371dcc98631735ada36cc9df0cf948. Both remote branches fetched and verified in Phase 2; MVC unchanged. Common ancestor e843462186e913e09437ffa04e9483fd31824788. Integrated with git merge --no-commit --no-ff into deployment/owli-server only. Integration merge finalized as ad126680fb9e55a6a1da6f73348b7a3bbc307bf2 with exactly the approved MVC and backend parents. There were no overlapping changed paths or conflicts. No uncommitted source was copied. All six Phase 1 files are preserved byte-for-byte under the local-only evidence/phase1 archive with a sanitized hash manifest; Phase 1 publish output remains untouched.

MVC: PersonalFinance.AdminMvc/Program.cs, net10.0, cookie authentication with server-side bearer/refresh tokens; administrator policy, antiforgery and secure cookies. Session storage is in process (256-session capacity); restart loses sessions. Use one MVC process, no autoscaling/web garden. Dashboard currently renders a view, not live aggregate financial metrics.

API: PersonalFinance.Api/Program.cs, net10.0; Domain and Infrastructure dependencies; SQL Server EF Core/Identity 10.0.13, JWT bearer 10.0.13, Identity UI 10.0.14. Application startup does not call Migrate/EnsureCreated. OpenAPI is development-only. Authentication and financial transports reject HTTP before redirect handling.

Reports integration is now present: GET /api/reports/monthly?month=YYYY-MM-01 is authenticated, user-scoped and registered through IReportService. Backend DTOs and nested budgets serialize into the unchanged MVC client; the deployment/checks/ReportsContract check verifies empty/populated payloads and request/route/auth metadata offline. Finance regressions exercise actual routing, JWT, month validation, isolation and totals with in-memory data. This does not prove SQL schema readiness. The backend includes recurring bills, notifications and finance-job source plus Hangfire 1.8.25 libraries and Newtonsoft.Json 13.0.4. Library dependency restore/publish is not worker/storage installation: no Hangfire service or schema was installed or enabled. Explicitly set Hangfire__Enabled=false; registration returns before storage/server setup. Never enable it under current authorization. Production hides /api/dev before auth and the controller also rejects non-Development execution. Do not call job triggers. The new OWLI_DEV_CERT_THUMBPRINT opt-in is development-only and binds localhost:7027; leave it unset and use Production on the server.

## Simple hosting architecture and capacity

Use an existing always-on host, two independently supervised applications and a non-LocalDB SQL Server reachable privately by API only. Separate HTTPS origins (admin and api) avoid path-base changes: browser -> MVC -> API -> SQL Server; Android -> API. MVC must call a certificate-valid absolute HTTPS origin without a subpath, credentials, query or fragment. MVC calls API server-side; browser CORS is not required for that path. Do not add permissive CORS.

Prefer existing Windows/IIS if available: two sites, separate application pools/identities, .NET 10 Hosting Bundle, HTTPS bindings, generated web.config retained. Otherwise use existing Linux/cloud hosting with .NET 10 ASP.NET Core runtime, a supervisor and reviewed TLS/proxy setup. Docker is optional, not required and no image has been prepared. Target OS remains undecided.

Planning allowance, not a measured minimum: x64 host with 2 vCPU, 4 GB RAM and 20 GB free storage for both apps, excluding SQL Server data/backups. SQL sizing/licensing/version, capacity and backup policy must be confirmed by DBA. Use a supported OS and maintained .NET 10 ASP.NET Core shared runtime. Build requires .NET 10 SDK (validated SDK 10.0.402); runtime-only hosting does not need SDK. Permit inbound HTTPS 443, optional HTTP 80 solely for redirect; keep app listeners and SQL port private. Permit API outbound HTTPS/DNS to api.frankfurter.dev/v2/ (fixed in source, five-second timeout, no API key configured), plus approved SQL and certificate infrastructure. Keep clocks synchronized for zero-skew JWT validation.

## Build and publish (offline, safe to execute)

From the deployment worktree with .NET SDK installed:

```powershell
dotnet restore PersonalFinance.Api/PersonalFinance.Api.csproj
dotnet restore PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj
dotnet build PersonalFinance.Api/PersonalFinance.Api.csproj -c Release --no-restore
dotnet build PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj -c Release --no-restore
./deployment/Publish-OWLI.ps1
```

Publish script uses fresh deployment/out/api and out/mvc directories and hashes output. It does not launch either app or run EF tools. It refuses existing output rather than mixing releases. Package only the two publish directories plus checksums and sanitized handoff documents; never copy the checkout, bin/obj, .git, user secrets, LocalDB files or local development settings. Framework-dependent portable output uses dotnet DLL entrypoints; target-specific native dependencies still require server smoke testing. Equivalent commands on any build OS:

```text
dotnet publish PersonalFinance.Api/PersonalFinance.Api.csproj -c Release --no-restore --self-contained false -p:UseAppHost=false -o deployment/out/api
dotnet publish PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj -c Release --no-restore --self-contained false -p:UseAppHost=false -o deployment/out/mvc
```

## Configuration and secrets

api.env.template and mvc.env.template are documentation, not auto-loaded by ASP.NET Core. Map values into separate service environments or the hosting secret store; do not source them as shell scripts (SQL delimiters are shell-sensitive). Keep populated files outside release directories/source control with restrictive permissions. Never pass secrets on command lines or log request bodies/Authorization/Cookie headers.

API requires ConnectionStrings__DefaultConnection, Jwt__Issuer, Jwt__Audience, Jwt__SigningKeyBase64, ASPNETCORE_ENVIRONMENT=Production, AllowedHosts and Hangfire__Enabled=false. Leave OWLI_DEV_CERT_THUMBPRINT unset; do not import developer launch settings. Signing key must decode to at least 32 cryptographically random bytes; administrator generates/stores it outside this task. Issuer/audience are protocol identifiers, not branding text; coordinate them with all clients and token lifetimes before changing. Optional CurrencyPolicy__MaximumRateAgeDays defaults to 7, cannot be negative.

MVC requires BackendApi__BaseUrl, ASPNETCORE_ENVIRONMENT=Production and AllowedHosts. Never inject SQL credentials or JWT signing keys into MVC or Android. Set AllowedHosts to actual hostname(s), semicolon-separated if needed, without scheme/port; templates use reserved .example names.

Direct Kestrel TLS needs ASPNETCORE_URLS and Kestrel__Certificates__Default__Path/Password (or reviewed host-specific certificate configuration); commented template examples use isolated ports 8443/8444, never shared 7027. IIS manages certificates/bindings instead. Persist ASP.NET Core Data Protection keys using the chosen host's protected storage; do not put keys in publish output. Existing source has no custom key-ring configuration. MVC sessions still expire on restart even with persistent keys.

Existing ignore rules cover .env variants, .secrets, PFX/private keys, API appsettings.Development.json, local JSON overlays and database backups. MVC appsettings.Development.json is NOT explicitly ignored: do not create it or copy local settings into publish output. Do not inspect/copy machine user-secret values. Local launchSettings localhost:7027/5015 and MVC 62978/62979 are development-only and ignored by dotnet DLL startup. No runtime localhost API fallback exists; BackendApi__BaseUrl is mandatory.

## HTTPS / proxy release gate

Both apps need Request.IsHttps=true. Neither Program.cs explicitly enables forwarded headers. IIS/ANCM integration handles its hosting-specific scheme forwarding; validate chosen IIS mode. For Linux/cloud TLS termination, do not simply forward HTTP into these apps: secure requests may be rejected or redirected in loops. Before release either use certificate-validated HTTPS upstreams to Kestrel, or implement/review explicit forwarded-header middleware before transport/HSTS/redirect logic with ONLY the exact trusted proxy addresses/networks. Preserve host, restrict AllowedHosts, have the proxy replace spoofed forwarding headers, and block direct public backend access. Do not globally trust arbitrary forwarded headers or disable HTTPS validation. This package intentionally does not guess proxy trust settings.

References: https://learn.microsoft.com/en-us/aspnet/core/host-and-deploy/proxy-load-balancer?view=aspnetcore-10.0 and https://learn.microsoft.com/en-us/aspnet/core/tutorials/publish-to-iis

## SQL and release blockers

Production must use a separately approved SQL Server service, never (localdb), AttachDbFilename or a developer workstation. Use encrypted connections with TrustServerCertificate=False and a verifiable server certificate. Use a dedicated least-privilege runtime identity, no database creation/DDL rights. Only API receives its connection string. Credentials in the template are explicit placeholders.

User reports migrations 3 and 4 unapplied. Migration 3: 20261103141326_AddSavingsGoalContributionLedgerAndRowVersions. Migration 4: 20261107054048_AddBillPaymentReceiptsAndGuards is UNSAFE TO APPLY. Do not run dotnet ef database update, migration bundles, EnsureCreated, backup restore or provisioning tools. Do not run SQL tests or connect to the live database. Source model/schema compatibility has NOT been established. DBA must provide a separately reviewed compatible schema plan and authorization; hosting cannot fix missing tables. No schema script is shipped as an installer. Historical live-activation blockers remain unresolved. Existing development provisioning is machine/target-bound and is not a production account setup path; request a separately authorized Administrator account and demo dataset. Do not bypass the Administrator policy.

## Installation and startup (future administrator actions, approval required)

1. Resolve schema, administrator account, hosting/TLS and proxy gates above and verify the approved recorded release; obtain live deployment approval before performing these steps.
2. Install maintained .NET 10 ASP.NET Core runtime; for IIS install Hosting Bundle and use separate pools/sites. Verify dotnet --list-runtimes on the target. Configure service identities with read access to release files and controlled write access only for keys/logs.
3. Transfer reviewed, checksummed api and mvc publish folders into separate versioned release directories. Retain previous release. Configure per-app environment securely; never import developer launch profiles or secrets. Confirm approved database identity and schema through the separately authorized DBA process.
4. Configure the actual admin/API HTTPS origins, trusted certificate chain, restricted hostnames, firewalls and chosen proxy strategy. Set BackendApi__BaseUrl to the API origin accessible from the server, not localhost:7027. Do not change DNS/certificates under this preparation authorization.
5. IIS: point each site's physical path at its published folder, retain generated web.config and configure separate pools and Production variables. Non-IIS: configure the selected supervisor with working directory equal to each publish folder and these entrypoints (run API first):

```text
dotnet PersonalFinance.Api.dll
dotnet PersonalFinance.AdminMvc.dll
```

6. Enable supervisor boot/restart and restricted logs only under live approval. No Hangfire process/jobs. Run the approved smoke tests below before routing public traffic. A root API 404 alone is not evidence of failure or health: no dedicated health endpoint exists. Never expose a database health endpoint to anonymous users.

## Smoke tests (not executed; require approved server and test data)

- TLS: valid certificate chain and correct hostname on both origins; HTTP credentials rejected; no redirect loops; secure cookies and no secrets in responses/logs. Private listeners inaccessible externally.
- Login: anonymous Dashboard redirects to Account/Login; invalid login has safe error, valid designated Administrator reaches Dashboard; ordinary user denied; logout invalidates session; no tokens in browser storage. Respect lockout threshold, do not brute-force.
- Dashboard: renders navigation/layout correctly, no expectation of live totals at this baseline.
- Categories: authorized list and default categories; approved test create/edit/delete only with disposable data and explicit write authorization; ownership and antiforgery enforced.
- Budgets: selected month list and usage; test validation/approved disposable mutations; distinguish missing schema from configuration errors.
- Financial Reports: source compatibility now passes offline; during separately authorized server rehearsal verify authenticated GET /api/reports/monthly for first day of month, valid JSON matches MVC, empty month works, no cross-user data. Source defines reports as user-scoped, not global administrator aggregation. Account for migration-3 savings dependencies.
- FX: under separate approval verify conversion behavior and provider outage handling without recording personal data. Avoid generating real transactions for diagnostics.
- Restart: MVC sign-in required again; API and MVC auto-recover on host reboot; no jobs or automatic schema changes. Verify Android public HTTPS connectivity separately.

## Rollback

Retain previous binaries, checksums and configuration version. Under separate service-change approval, withdraw traffic, stop only deployment service instances, switch both release directories to the previous compatible pair and restart through their supervisor. Restore matching non-secret configuration references; preserve signing key unless a separately reviewed rotation requires otherwise. MVC sessions are lost; users sign in again. Do not touch shared port 7027. No database rollback/restore/migration is part of this procedure. If schema compatibility is uncertain, stop and refer to DBA rather than launch incompatible binaries. First deployment has no prior release: keep service offline on failure. Document version, reason and smoke results.

## Android handoff

Android checkout was untouched. Later change its API base URL from workstation/emulator/local LAN addresses to the approved public HTTPS API origin, retaining the API route prefix expected by Retrofit. Check release network configuration trusts the public certificate, do not enable cleartext or bypass certificate verification. Keep JWT key/SQL credentials out of APK. Verify issuer/audience expectations, token refresh/logout, error mapping and Room synchronization against approved schema/contracts. Do not queue financial writes without an agreed conflict/replay policy. No background-job endpoint is promised here.

## Administrator handoff: required answers and evidence

- Hosting owner, existing provider/service, OS/version/architecture, access process and budget approval; whether IIS, managed .NET, container or VM is available.
- Available CPU/RAM/storage, supported .NET 10 runtime, persistent writable protected key storage, service supervisor and release directory conventions.
- Actual admin/API hostnames or provider HTTPS URLs, certificate owner/renewal, TLS termination topology, upstream HTTPS support, exact proxy addresses and header behavior.
- Private SQL Server endpoint/version/authentication, certificate chain, database owner, least-privilege identity, approved schema compatibility plan, backup/recovery ownership. Supply secrets through secure channel, never chat or repo.
- Target-server authentication/role verification, explicitly approved Administrator provisioning and disposable demonstration data.
- Outbound FX/DNS policy, logging/redaction, monitoring owner, maintenance window, rollback owner, live-deployment authorization. Confirm demonstration timezone/time on November 20, 2026.

No paid resources, DNS changes, schema changes or live operations are included in this package.

Validation evidence: see VALIDATION.md. On this machine execution policy blocks .ps1 execution; leave policy unchanged and use the documented direct publish commands or an administrator-approved signed script. Do not rerun into existing output directories; build the next release in a fresh workspace/output location.

## Phase 2 candidate outputs and reproduction

Current validated candidate output is deployment/out-phase2/api and deployment/out-phase2/mvc (288 files total), not Phase 1 deployment/out. Checksums are evidence/phase2/publish-hashes.json. Test results are evidence/phase2/test-summary.json; detailed build/test/publish .log files are retained locally and ignored by root Git rules. Phase 1 copies retain historical statements; use this current README and VALIDATION for present status.

Exact Phase 2 publish commands (use fresh output locations; do not overwrite prior releases):

```text
dotnet publish PersonalFinance.Api/PersonalFinance.Api.csproj -c Release --no-restore --self-contained false -p:UseAppHost=false -o deployment/out-phase2/api
dotnet publish PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj -c Release --no-restore --self-contained false -p:UseAppHost=false -o deployment/out-phase2/mvc
```

Build all tests individually in Release; run the seven tests/*/*.csproj default modes only, no --sql or --startup-smoke flags. For safe configuration-factory tests use an isolated empty APPDATA directory in the test process so Development configuration loading cannot access actual user secrets. Force Hangfire__Enabled=false and clear OWLI_DEV_CERT_THUMBPRINT and ConnectionStrings__DefaultConnection in that test process. Existing fixtures replace data with synthetic/in-memory data and ephemeral keys. Build and run deployment/checks/ReportsContract/ReportsContract.csproj as the eighth check; it uses an in-process HTTP message handler, no network.

The source candidate is ready for hosting-plan review and a future authorized rehearsal only after SQL schema/account and HTTPS/proxy gates are resolved. It is not currently ready to run against the existing development database or a public server. No migration or Hangfire activation is a prerequisite performed by this package.
## Publication status and production safety

**NOT YET APPROVED FOR LIVE DEPLOYMENT.** Branch publication records an offline candidate only. Production MUST inject `Hangfire__Enabled=false` through its service environment or server-side configuration provider. The environment template is documentation and is not auto-loaded; committing it does not set the runtime value. Keep `DOTNET_ENVIRONMENT` unset or set it consistently to Production so it cannot override the intended environment. Verify the effective environment before any separately approved startup. Leave the development certificate thumbprint unset.

Supply SQL credentials, JWT signing keys and certificate passwords only through the hosting platform's protected server-side secret injection. Never commit populated runtime settings, pass secrets in command arguments, or distribute the local archive/raw logs. The six byte-exact Phase 1 files remain local and ignored; their relative-path hashes are retained for preservation evidence. Raw logs are local only. GitHub evidence contains sanitized test/build summaries, relative publish hashes and a source-validation record. Paths in publish hashes describe local output layout; published binaries themselves are excluded.

The source-validation record binds the reviewed merge tree to the unchanged application/test inputs and the Reports contract-check source. No rebuild was necessary for documentation/metadata sanitization: no application or check code changed after validation. Rebuilding later may yield different binary hashes; record a new validation run instead of replacing historical evidence.
