# OWLI — Hermes Handoff (2026-11-21)

OWLI is the Personal Finance and Budget Monitoring Platform: an ASP.NET Core Web API, an ASP.NET Core MVC
administrator site ("Owlight" admin UI), and a Kotlin/Jetpack Compose Android app (Lavender & Midnight design).
This document is sanitized for a **public** repository: it contains no secrets, hostnames or personal paths.

## 1. What to clone

```
git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git switch integration/hermes-handoff-20261121
```

**Recommended branch:** `integration/hermes-handoff-20261121`. It contains every committed Android, MVC, Web API,
reports, FX and Hangfire implementation in one tree. It has **not** been merged into `main`, and must not be
merged there without the owner's review. `main` is still the initial commit.

## 2. Branch map and commit hashes (all verified on GitHub on 2026-11-21)

| Branch | Tip | Contents | In integration? |
| --- | --- | --- | --- |
| `integration/hermes-handoff-20261121` | (this commit) | deployment + backend demo docs + all Android + FX wording fix | — |
| `deployment/owli-server` | `b43c547` | Backend + MVC + reports + FX + Hangfire integration, deployment templates/evidence | yes (base) |
| `feature/backend-hangfire-demo` | `cef531c` | Recurring bills, finance jobs, notifications, dev job trigger, FX; ELECT3 demo docs | yes |
| `feature/backend-fx-quote` | `c47bcd9` | `GET /api/exchange-rates/quote` (Frankfurter) | yes |
| `feature/mvc-admin-dashboard` | `07d4b23` | MVC admin UI and session handling | yes |
| `review/mvc-admin-dashboard-c77cd54` | `c77cd54` | Older MVC review snapshot (ancestor of the above) | yes |
| `feature/reports-monthly-handoff` | `81e3715` | User-scoped `GET /api/reports/monthly` | yes |
| `feature/android-lavender-midnight-ui` | `36b6a9f` | Android integration branch: Room offline-first sync, Owlight screens, budgets, currency, reports, FX quote | yes |
| `feature/android-budgets-ui` | `4be608a` | Budget month navigation, stale and offline states | yes |
| `feature/android-currency-ui` | `37fdf26` | Currency conversion UX | yes |
| `feature/android-financial-reports` | `0d23c3d` | Reports and insights screens | yes |
| `feature/android-fx-quote` | `3307871` | Live FX quotes in converter + Mock Demo wording fix | yes |
| `feature/android-transaction-list-mock` | `ce1a93c` | Early navigation work | yes |
| `feature/admin-provisioning-username` | `3f5ca8b` | Separate admin username from email in the provisioning tool (35/35 offline checks pass) | **no**: merges cleanly, owner decision |
| `wip/android-worktree-snapshot-20261121` | `446d3c3` | Preserved unreviewed Android edits + a stale backend copy | **no**: see §8 |
| `main` | `e843462` | Initial commit | base |

## 3. Architecture

```
PersonalFinance.Api/             ASP.NET Core Web API: controllers, JWT, HTTPS/no-cache transport middleware
  Controllers/                   Auth, Categories, Transactions, Budgets, SavingsGoals, RecurringBills,
                                 Reports, Notifications, ExchangeRates, DevJobs (Development only)
PersonalFinance.Domain/          Entities, enums, pure financial policies
PersonalFinance.Infrastructure/  EF Core (SQL Server), ASP.NET Identity, services, Hangfire jobs, migrations
PersonalFinance.AdminMvc/        MVC administrator site; talks to the API over HTTPS only (BackendApi:BaseUrl)
android/                         Android app (single :app module): Compose, Navigation, Room, native HTTPS
tests/                           Standalone console test programs (offline by default)
tools/PersonalFinance.AdminProvisioning/  Guarded dev admin-account tool (LocalDB only; do not run casually)
deployment/                      Publish script, env templates (placeholders only), offline validation evidence
docs/                            Implementation/verification reports, elect3-demo runbook and guarded SQL
```

Key rules that must be preserved: every finance endpoint is user-scoped (data isolation tested); HTTP is
rejected on finance/auth routes; `/api/dev/*` returns 404 outside Development; transactions are idempotent
by `ClientId` (Android offline sync depends on this); Hangfire never creates its own schema.

## 4. Dependencies

| Area | Version / requirement |
| --- | --- |
| .NET SDK | 10.0.x (validated with 10.0.402); all projects target `net10.0`; solution file is `.slnx` |
| EF Core / Identity / JWT | 10.0.13; local tool `dotnet-ef` 10.0.13 (`dotnet tool restore`) |
| Hangfire | 1.8.25 (Core, NetCore, SqlServer), **disabled by default** (`Hangfire:Enabled` must be `true` to wire it) |
| SQL Server | Development target is SQL Server LocalDB `(localdb)\MSSQLLocalDB`, database `PersonalFinanceDb`, Windows auth |
| FX provider | Internet access to `https://api.frankfurter.dev/v2/` |
| Android | JDK 17, Android SDK 36 (minSdk 26), Gradle 9.1.0 wrapper, AGP 9.0.1, Kotlin 2.2.10, Compose BOM 2025.08.00, Room 2.8.4 |
| IDE | A Visual Studio or Rider version that supports .NET 10 and `.slnx`; Android Studio for `android/` |

## 5. Safe configuration and startup

Nothing secret is in Git. On the new PC you must create your own local values:

1. **API secrets** (user-secrets, never committed):
   `dotnet user-secrets --project PersonalFinance.Api set "Jwt:SigningKeyBase64" "<base64 of ≥32 random bytes>"`
   `dotnet user-secrets --project PersonalFinance.Api set "ConnectionStrings:DefaultConnection" "Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Trusted_Connection=True;Encrypt=True;TrustServerCertificate=True"`
2. **HTTPS:** `dotnet dev-certs https --trust`. API: `https://localhost:7027`. MVC: `https://localhost:62978`.
3. **MVC:** set `BackendApi:BaseUrl` to `https://localhost:7027` (user-secrets or environment variable).
4. **Keep Hangfire off:** `Hangfire__Enabled=false`. The dev job trigger runs job code directly without Hangfire tables.
5. **Run:** `dotnet run --project PersonalFinance.Api --launch-profile https`, then `dotnet run --project PersonalFinance.AdminMvc`.
6. **Android (debug):** the debug build calls `https://localhost:7027/` and trusts only the public certificate at
   `android/app/src/debug/res/raw/owli_localhost_dev_certificate.cer`. **This certificate is from the original PC,
   so the app will not trust the new PC's API until you replace it** (see §7, item 1). Then:
   start the API with `ASPNETCORE_ENVIRONMENT=Development` and `OWLI_DEV_CERT_THUMBPRINT=<thumbprint of that certificate>`
   (no launch profile), run `adb reverse tcp:7027 tcp:7027`, and use the in-app HTTPS probe.
   Release builds have no API configured on purpose.
7. **Mock Demo** mode in the Android app works with no server at all.

### Database: read before doing anything

The database is **not** in Git, and the new PC starts with none. The original PC's development database has
migrations 1 and 2 applied and 3 and 4 **not** applied.

- **Do not run a blanket `dotnet ef database update`.** Migration `20261107054048_AddBillPaymentReceiptsAndGuards`
  (migration 4) is marked **UNSAFE TO APPLY**. Migration 3 (`AddSavingsGoalContributionLedgerAndRowVersions`) is
  unapplied and has a guarded plan in `docs/elect3-demo/`.
- The safe path for a fresh LocalDB is for the owner to decide: either apply only migrations 1 and 2 to a new empty
  database (`dotnet ef database update 20261031115500_AddCategoryNameUniqueness --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api`),
  or restore a database backup transferred privately (never through GitHub).
- An Administrator account must then be created with the guarded provisioning tool, after owner approval.

## 6. Verification status

Run on 2026-11-21 against this integration tree, offline only (isolated user-secrets profile, `Testing`
environment, empty connection string, Hangfire disabled, no SQL or startup-smoke switches):

| Check | Result |
| --- | --- |
| Release build: API, MVC, AdminProvisioning, 7 test projects, ReportsContract | 11/11 succeeded, 0 warnings, 0 errors |
| FinanceTests | 411 passed, 0 failed (in-memory EF + real JWT/MVC routing) |
| ApiSecurityTests / AuthenticationTests | 27 / 27 passed |
| AdminProvisioningTests | 22 passed (35 on `feature/admin-provisioning-username`) |
| CategoryUniquenessTests / SchemaValidation | 15 / 65 passed (no DB connection opened) |
| AdminMvc.UiTests, ReportsContract | PASS |
| Android `:app:assembleDebug` + `:app:testDebugUnitTest` | BUILD SUCCESSFUL; 523 unit tests, 0 failures |

**Not verified:** API startup, any SQL Server execution, migrations, Android instrumented/device tests,
phone↔API synchronization, MVC↔API in a browser, Hangfire scheduling, and deployment. Offline test passes are
**not** evidence of end-to-end synchronization.

### Feature status (source + offline tests, not live-verified)
- Implemented end to end in source: registration/login/refresh/logout; categories (personal and admin defaults);
  transactions with FX snapshots; monthly budgets; savings goals and contributions (API); monthly reports (API + MVC);
  FX quote (API + Android); notifications and dev job trigger (API, Development only); MVC admin dashboard,
  categories, account budgets and reports; Android dashboard, transactions, budgets, currency converter,
  reports and insights, Room offline-first sync, Mock Demo.
- Incomplete or not wired: Android Bills tab (the screen still says the API has no recurring-bill endpoint; the endpoint
  now exists but depends on unapplied migration 4); Android Goals/savings not using `/api/savings-goals`;
  Android Reports are computed on the device from synced data rather than from `/api/reports/monthly`;
  Android registration; Hangfire scheduling in any real environment.

## 7. Known integration problems

1. **Android dev certificate is machine-specific.** On the new PC, export only the public part of the localhost
   dev certificate. In PowerShell: `Get-ChildItem Cert:\CurrentUser\My | Where-Object Subject -eq 'CN=localhost'`
   to find the thumbprint, then `Export-Certificate -Cert Cert:\CurrentUser\My\<thumbprint> -FilePath owli_localhost_dev_certificate.cer`
   (DER, public key only). Replace the `.cer` under `android/app/src/debug/res/raw/` and use the same thumbprint
   for `OWLI_DEV_CERT_THUMBPRINT`. Never export or commit a private key, `.pfx` or `.key` file.
2. **Schema readiness:** reports query savings contributions, and recurring-bill and payment routes may depend on
   migration 3 and 4 structures. Keep those features out of live demos until the schema question is resolved.
3. **README.md is outdated.** It still says no MVC or Android project exists. Trust this document instead.
4. Port 7027 is shared by API runs and Android sync tests, so run one API at a time.
5. Restarting the API ends in-memory phone sessions and interrupts an in-progress sync.

## 8. Work that is preserved but deliberately not integrated

- `wip/android-worktree-snapshot-20261121`, commit `d23c424`: removes the moon from the Login screen and hard-codes the
  Plan → Budgets tab count to `0`, which undoes the `budgetCount` wiring. Owner must confirm intent before cherry-picking.
- Same branch, commit `446d3c3`: an **older** copy of recurring-bill, report and Hangfire backend files. The integrated
  backend is newer (transactional monthly summary, `/api/dev` production guard, FX, notifications). **Do not merge.**
- `feature/admin-provisioning-username`: reviewed, tested, and merges cleanly. Merge it only after owner approval.
- Internal SQL-safety harnesses and database-verification tools from the original PC remain **local only** (see §9).

## 9. Repository exposure note

On 2026-11-21 a preservation branch `wip/main-worktree-20261121` was briefly pushed to this **public** repository.
It contained internal SQL-safety test harnesses, database-verification tools and repository-preparation notes,
with no credentials but with a machine name and local paths. It was deleted from GitHub the same evening and
replaced by the reviewed `feature/admin-provisioning-username`. **Deleting a branch does not guarantee removal**
from GitHub caches, forks, the events API, or clones and fetches made while it was public. The original commits
(`d694be4`, `5054bb6`) remain only in the owner's local repository and verified offline backup.

## 10. Priorities for the Monday presentation

1. On the new PC: clone, build, and run the offline suites above to confirm the toolchain. This needs no database.
2. Decide the database path (§5) with the owner. Without it, only Mock Demo (Android) and offline tests can be shown.
3. Replace the Android debug certificate (§7.1), then do one real phone↔API login and transaction sync over `adb reverse`.
4. Show the MVC admin dashboard, categories, budgets and monthly reports against the same API.
5. Keep Bills, Hangfire and anything that depends on migrations 3 or 4 out of the live demo. Present them as
   implemented-but-pending-schema, using offline test evidence.
6. After the demo, consider: wiring Android Goals to `/api/savings-goals`, using `/api/reports/monthly` on Android,
   updating `README.md`, and opening a reviewed PR from the integration branch into `main`.

Safety rules carried forward: never force-push; never apply migrations without owner approval; never commit
`appsettings.Development.json`, `local.properties`, `.hprof` heap dumps, `.pfx` or keys; keep OWLI as the product name.
