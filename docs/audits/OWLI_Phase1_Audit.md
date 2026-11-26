# OWLI Phase 1 — System Audit and Gap Analysis

> Status: Phase 1 assessment record, 26 November 2026. Investigation only. No
> application code, schema, configuration, data or branch history was changed.
> Expected behavior: [PRODUCT.md](../../PRODUCT.md). Contracts:
> [backend integration](../backend-integration.md). Worker target:
> [worker preparation](../worker-config/README.md). This is not the
> implementation roadmap.

## Baseline

| Item | Value |
| --- | --- |
| Audited source | `origin/dev` at `e15d859e3b98df6d0e9ed5ef5549b5a3730aec88` (merge of PR #1, the Phase 0 configuration) |
| How verified | Audit commands ran in a checkout of `6d86928` (PR #1 head). `git diff 6d86928 e15d859` is empty, so the audited tree is identical to the merged `dev` |
| Application source | Unchanged since `6687eb9`. Phase 0 changed only documentation, the CI workflow, `.gitignore`, `.claude/settings.json` and two MVC UI-test files |
| Record branch | `docs/phase1-system-audit`, created from fast-forwarded `dev` at `e15d859` |

## Evidence labels

- **Confirmed (source)**: established by reading current source at the baseline. Runtime behavior is not proven.
- **Suspected**: inferred from source or framework conventions, but not directly demonstrated.
- **Decision**: behavior is clear in source, but whether it satisfies PRODUCT.md needs an owner decision.
- **Verified**: demonstrated by a command run during this audit (see the next section).
- Requirement status values: Implemented–unverified, Partial, Missing, Blocked. None is Runtime-verified.

## Verification performed

| Check | Result | Notes |
| --- | --- | --- |
| `dotnet build PersonalFinancePlatform.slnx` (.NET SDK 10.0.400, macOS) | PASS | 0 warnings, 0 errors |
| `FinanceTests` | PASS | 430 checks; in-memory EF; SQL locking/constraints not executed |
| `AuthenticationTests` | PASS | 30 checks; SQL concurrency not run |
| `ApiSecurityTests` | PASS | 32 checks; real JWT/MVC middleware, in-memory persistence |
| `CategoryUniquenessTests` | PASS | 15 checks; synthetic SQL errors |
| `SchemaValidation` | PASS | 65 checks; no database connection |
| `AdminProvisioningTests` | PASS | 35 checks; no SQL |
| `AdminMvc.UiTests` | PASS | 43 PASS lines, exit 0 |
| `HangfireTests` | BLOCKED | Refuses to run without its dated disposable LocalDB target (by design) |
| Android `./gradlew testDebugUnitTest` (JDK 26) | PASS | 596 tests, 0 failures, 0 skipped |
| Android `testReleaseUnitTest`, `assembleDebug` | NOT TESTED | CI covers these; not rerun in this audit |
| SQL migration from an empty database, SQL concurrency/rollback | NOT TESTED | Needs an approved disposable target |
| Live API, MVC rendered/responsive/accessibility, Android device/emulator | NOT TESTED | Out of audit scope |
| Cross-client consistency, worker lifecycle | NOT TESTED | No worker exists; no shared runtime environment |

Passing builds and offline tests do not establish functional acceptance (PRODUCT.md).

## 1. Executive summary

- **Backend: strongest layer (Confirmed, source).** It has per-user ownership filtering, serializable transactions with `sp_getapplock` user/category locks, ClientId idempotency, bill-payment receipts and contribution ledgers, decimal(18,2) money and decimal(18,8) rates with AwayFromZero rounding, stored per-transaction FX snapshots, and a database guard on base-currency changes.
- **MVC Member is broad.** It covers transactions, categories, budgets, savings goals, bills, currency, reports, notifications, account and registration. However, MVC registration rejects the API's success response (H6).
- **Android has real parity gaps against MVC:** no registration, no notifications, no transaction deletion, a locally computed report, and offline support limited to transaction creation.
- **The mandatory `PersonalFinance.Worker` is Missing.** If Hangfire were enabled today, it would run its server and recurring registration inside the API.
- **No requirement is runtime-verified end to end.** All automated evidence is offline or in-memory.

## 2. Requirements and parity matrix

| Capability (PRODUCT.md) | API | MVC Member | Android | Status |
| --- | --- | --- | --- | --- |
| Registration | `POST /api/auth/register` (201 Created) | Form exists, but the success response is rejected (H6) | **Missing** | Partial (MVC defect, Android Missing) |
| Login / refresh / logout | Yes | Yes | Yes | Implemented–unverified |
| Profile / password | Two contracts: `/api/auth/*` and `/api/account/*` | Uses `auth/*` | Uses `account/*` | Implemented–unverified; contract split is a known deferred decision |
| Transactions: create / list / edit | Yes | Yes | Yes, plus Room offline create | Implemented–unverified |
| Transactions: delete | Yes | Yes | **Missing** | Partial |
| Member categories CRUD | Yes | Yes | Yes | Implemented–unverified |
| Admin default categories | Yes, Administrator role | Yes, admin area | n/a | Implemented–unverified |
| Budgets CRUD, status and threshold | Yes | Yes | Yes | Implemented–unverified |
| Savings goals and contributions (If-Match, receipts) | Yes | Yes | Yes, but generic JSON-driven UI, online only | Partial (Android UI/offline) |
| Recurring bills and payments (receipts) | Yes | Yes | Yes, but generic JSON-driven UI, online only | Partial (Android UI/offline) |
| Currency: Frankfurter quote, preferences, historical snapshot | Yes | Yes | Yes | Implemented–unverified |
| Monthly report | `GET /api/reports/monthly` | Uses the API | Computed locally from transactions | Partial (parity risk) |
| In-app notifications | Read-only list | Read-only inbox | **Missing** | Partial; mark-read Missing on all platforms |
| Stored monthly summaries | Written by the job; **no read endpoint** | None | None | Missing (consumption) |
| Android offline recording / history, pending / rejected / confirmed states | ClientId idempotency | n/a | Room for transaction create and category cache only | Partial |
| Admin budgets and reports | User-scoped endpoints only | Show the admin's own data | n/a | Decision |
| Separate worker console host | Not present; opt-in hosting inside the API | — | — | Missing (mandatory) |
| Jobs: reminders 07:00, alerts hourly, summaries day 1 00:30, Asia/Manila | `FinanceJobs` + `HangfireSetup` | — | — | Implemented–unverified; activation Blocked by the worker |

## 3. Findings

### High

| ID | Finding | Evidence | Label |
| --- | --- | --- | --- |
| H1 | No `PersonalFinance.Worker`. When `Hangfire:Enabled=true`, the API registers the Hangfire server and startup recurring registration | `PersonalFinance.Infrastructure/Jobs/HangfireSetup.cs:76-77`, called from `DependencyInjection.cs:90`, which the API's `Program.cs` calls via `AddInfrastructure` | Confirmed (source) |
| H2 | Android has no registration. The auth client implements only login, logout and refresh, and there are no sign-up resources | `android/.../data/remote/AuthApiClient.kt:53-57`; no register strings in `res/values/strings.xml` | Confirmed (source) |
| H3 | Notifications are read-only everywhere. No code sets `IsRead` to true, so the `unreadOnly` filter returns everything. Android has no inbox | `PersonalFinance.Infrastructure/Finance/NotificationService.cs`; `NotificationsController.cs`; no Android `notifications` reference | Confirmed (source) |
| H4 | Android cannot delete transactions. MVC and the API can | `android/.../domain/repository/TransactionUpdateRepository.kt:10-11` (update only) | Confirmed (source) |
| H5 | No runtime acceptance evidence for SQL Server, the live API, rendered clients, cross-client behavior or the worker | Verification table above | Blocked / Not tested |
| H6 | MVC registration rejects a successful registration. The API returns **201 Created** with a `CurrentUserResponse`. For JSON results, MVC `AuthenticationApiClient.SendAsync` accepts only **200 OK** and maps anything else to `InvalidResponse`. The account is created, but `AccountController` shows "Registration is temporarily unavailable" with HTTP 400, and a retry reports "already registered". The offline UI tests did not catch this | `PersonalFinance.Infrastructure/Authentication/AuthenticationService.cs:51` (`Success(..., 201)`), returned by `AuthController.Respond` (`AuthController.cs:20-22, 78-81`); `PersonalFinance.AdminMvc/Services/AuthenticationApiClient.cs:51`; `Controllers/AccountController.cs:29-46` | Confirmed (source); runtime reproduction pending |

### Medium

| ID | Finding | Evidence | Label |
| --- | --- | --- | --- |
| M1 | `MonthlySummary` rows are produced but no API or client reads them. Summary notifications reference a resource clients cannot fetch | `MonthlySummaries` is used only by jobs, `DevJobsController` and persistence | Confirmed (source) |
| M2 | Android computes the report locally from paged transactions. Its `MonthlyReport` has no savings-contributed or budget data, unlike the API report. Its doc comment saying transactions are "the API's only reporting-capable contract" is stale | `android/.../domain/reports/LoadFinancialReport.kt:23`; `FinancialReport.kt:32-40`; `PersonalFinance.Infrastructure/Finance/ReportService.cs` | Confirmed (source); numeric parity not tested |
| M3 | Transaction updates have no client version token, so concurrent edits from MVC and Android are last-writer-wins. `SameState` only detects changes within one request | `TransactionService.cs:150` onward; `FinanceData.SameState` | Confirmed (source) |
| M4 | Android keeps uncertain savings/bill request identities only in memory. After process death, a retried contribution gets a new ClientId, and the server's `(UserId, ClientId)` index would accept it as new. Bill payments are additionally protected by unique `(BillId, DueDate)` | `LiveAccountPlanRepository.kt:21-22`; `ui/plan/LivePlanSection.kt:256`; `GoalContributionRequestConfiguration.cs:22`; `BillPaymentReceiptConfiguration.cs:48` | Confirmed (source); duplicate not reproduced |
| M5 | Android offline scope is limited. Room holds only `local_transactions` and `local_categories`. Pending sync is retried from a Compose `NetworkCallback` while the UI is active, with no WorkManager. Edits, deletes, budgets, goals and bills are online-only | `data/local/room/OwliDatabase.kt:122`; `ui/app/OwliFinanceContent.kt:173-178` | Confirmed (source); device behavior not tested |
| M6 | A budget referenced by any alert notification can never be deleted. Now that jobs produce alerts, this becomes permanent. The comment "No notification producer exists yet" is stale | `PersonalFinance.Infrastructure/Finance/BudgetService.cs:97-104` | Confirmed (source); Decision on intent |
| M7 | Worker DI prerequisite. Several shared finance services need `ICurrentUser`, which only the API registers. A Generic Host that validates the service graph on build (the Development default) is expected to fail unless shared and HTTP-scoped registrations are split | `AuthenticationConfiguration.cs:27`; `TransactionService.cs`, `CategoryService.cs`, `ReportService.cs` and others take `ICurrentUser` | Confirmed (dependency); failure Suspected (not executed) |
| M8 | MVC API sessions live in an in-process dictionary and are lost on restart or scale-out | `PersonalFinance.AdminMvc/Authentication/AdminSessionStore.cs:29` | Confirmed (source); previously known |
| M9 | The default MVC Member dashboard ("all-time") reads every transaction page sequentially, up to 1,000 requests of 100 | `Areas/Member/Controllers/HomeController.cs:41`; `Services/TransactionApiClient.cs:32-57` | Confirmed (source); performance not measured |
| M10 | MVC Administrator "Budgets" and "Reports" call user-scoped endpoints, so an administrator sees only their own data. PRODUCT.md preserves these features but does not define cross-user scope | `PersonalFinance.Api/Controllers/ReportsController.cs:8`; `PersonalFinance.AdminMvc/Controllers/BudgetsController.cs`, `ReportsController.cs` | Decision |

### Low

| ID | Finding | Evidence | Label |
| --- | --- | --- | --- |
| L1 | Replaying a transaction ClientId with a different payload silently returns the original. Contributions and bill payments compare canonical payloads; transactions do not | `TransactionService.cs:21-24` versus `GoalContributionIdempotency.Matches` | Confirmed (source) |
| L2 | MVC default months use server-local `DateTime.Today`, not Asia/Manila | `HomeController.cs:22`, `MemberDashboardViewModel.cs:37`, admin `BudgetsController.cs:41`, `ReportsController.cs:18`, `SavingsGoalsController.cs:178` | Confirmed (source); impact only near midnight or on a non-Manila host |
| L3 | Android goals/bills UI renders raw `JsonObject` records with about 49 hard-coded English strings (localization, accessibility, brand consistency) | `ui/plan/LivePlanSection.kt`, `GoalsBillsSections.kt` | Confirmed (source) |
| L4 | CI runs on `handoff/**` pushes and pull requests only; direct pushes to `dev` are not built | `.github/workflows/handoff.yml:4-5` | Confirmed (source) |
| L5 | The unused alternative job family remains and is exercised by `HangfireTests`. It has different IDs and schedules, so it risks accidental double scheduling | `Jobs/HangfireJobRegistration.cs`, `Jobs/FinanceAutomationJobs.cs` | Confirmed (source); previously known |
| L6 | Stale `DevJobsController` comment: bill reminders are excluded "because they need migration 4", which already exists | `PersonalFinance.Api/Controllers/DevJobsController.cs:13` | Confirmed (source) |

## 4. Missing or incomplete functionality

- `PersonalFinance.Worker` console host (H1, M7).
- Android: registration (H2), transaction deletion (H4), notifications inbox (H3), summary access (M1), durable uncertain-write recovery beyond transaction creation (M4, M5).
- API: notification mark-read and a stored-summary read contract (H3, M1). Both are subject to a PRODUCT.md decision on summary access by both clients.
- Account contract unification: deferred decision; do not change silently.
- Administrator cross-user features: not assumed; requirement confirmation is needed (M10).

## 5. Architecture and integration

- **Dependency direction is clean (Confirmed, source):** API → Infrastructure → Domain. MVC and Android use HTTP only, and MVC has no Infrastructure reference.
- **Worker needs the ASP.NET runtime.** Infrastructure declares `FrameworkReference Microsoft.AspNetCore.App` (Identity), so the worker will require the ASP.NET shared runtime. Expected by the Phase 0 notes.
- **Main contract drift:** two account contracts; server versus local report computation (M2); stored summaries without a reader (M1).
- **No real-time sync.** Clients refresh on demand. PRODUCT.md does not require push; refresh-based consistency is Not tested.

## 6. Hangfire worker readiness

Ready (Confirmed, source):
- `FinanceJobs` depends only on `ApplicationDbContext`, `IBudgetUsageService`, `IFinancialWriteScope` and `TimeProvider`, with no HTTP or `ICurrentUser` dependency.
- Stable IDs and schedules: `owli-bill-reminders` daily 07:00, `owli-budget-alerts` hourly, `owli-monthly-summaries` day 1 00:30, all in `Asia/Manila`.
- `EnsureDedicatedStorage` enforces the same instance and the separate `OWLI_Hangfire` catalog. `PrepareSchemaIfNecessary=false`. Storage was provisioned in Phase 0.
- `DisableConcurrentExecution` and `AutomaticRetry(Attempts = 3)` are set on each job.
- Deduplication:
  - Reminders use the unique `(UserId, DeduplicationKey)` index.
  - Budget alerts record `LastAlertStatus` transitions under a serializable user applock. Their notification key embeds a GUID, so correctness depends on the lock and state, not the index.
  - Summaries use the unique `(UserId, SummaryMonth)` index plus a recheck inside the lock.

Remaining prerequisites:
1. Split shared Infrastructure registration from HTTP-scoped (`ICurrentUser`) services and worker-only Hangfire server/schedule registration. The API must never add a server (H1, M7).
2. Create the Generic Host console in the solution. It loads its own configuration and needs no Kestrel, JWT or tunnel.
3. Retire or replace the direct `DevJobsController` execution path.
4. Decide summary immutability. Today an existing month is never recomputed, so late transactions are not reflected.
5. Decide overdue-bill policy. Bills with `NextDueDate < today` currently get no reminder.
6. Build a portable disposable SQL harness for retry, concurrency, timezone and restart tests.
7. Remove or quarantine the alternative job family (L5).

Runtime retry, concurrency and exactly-once behavior is Not tested.

## 7. Security, financial integrity and synchronization risks

- **Strengths (Confirmed, source):**
  - Every JWT revalidates user existence, lockout, security stamp and roles.
  - Financial and auth routes reject plain HTTP and set no-store headers.
  - Auth endpoints are rate-limited. Identity enforces a 12-character complex password policy and lockout.
  - Every finance query is owner-scoped.
  - Writes use applocks and serializable transactions, with receipt and ledger uniqueness.
- **Risks:**
  - M3 cross-client lost updates.
  - M4 contribution retry identity after process death.
  - L1 silent ClientId payload mismatch.
  - M8 session loss.
  - SQL-specific behavior (locks, collation, triggers, rollback) is Not tested; all offline suites use in-memory persistence or synthetic errors.
- **Sensitive data:** this audit contains no credentials, connection values, financial records or machine details.

## 8. Test coverage and verification gaps

- The .NET suites are custom console harnesses with about 650 checks; Android has 596 JVM unit tests. They are strong on validation, contracts, idempotency logic and UI-model behavior.
- Missing or blocked:
  - Execution against SQL Server.
  - HTTP API integration against real SQL.
  - Android instrumentation on a device or emulator.
  - MVC rendered, responsive and accessibility checks.
  - Cross-client scenarios.
  - Worker lifecycle and job retry.
  - Report numeric parity between clients.
  - Release-variant Android tests in this audit.

## 9. Recommended priorities and dependencies

This is a priority outline, not the implementation roadmap.

- **P0:** A disposable SQL target with migrations applied from an empty database. This gates all runtime evidence. Then the worker DI split and host (H1, M7).
- **P1:**
  - MVC registration status contract (H6): accept 201 for register, with a regression test. Then confirm that an MVC-registered account can sign in on Android.
  - Android registration and transaction deletion (H2, H4). No API change is needed.
  - Notification mark-read and summary-read contracts (H3, M1), then client inboxes.
- **P2:**
  - Android report parity (M2).
  - A transaction version token (M3). This is a contract change for both clients.
  - Persisting Android uncertain-write identities (M4).
  - Budget-deletion policy (M6).
  - Admin scope decision (M10).
- **P3:** MVC dashboard paging (M9), Manila dates (L2), Android goals/bills UI (L3), CI on `dev` (L4), stale comments and dead job family (L5, L6).
- **Final:** A cross-platform runtime pass with synthetic accounts on approved disposable targets.

## 10. Suggested Codex / Claude Code division

Follows the default ownership in AGENTS.md.

- **Codex:** worker and DI split, `DevJobsController` retirement, notification mark-read and summary endpoints, server-side transaction version token, budget-deletion policy, ClientId mismatch handling, SQL harness, alternative-family cleanup, CI trigger.
- **Claude Code:** MVC registration status fix (H6), Android release endpoint configuration, Android registration, deletion, inbox and summaries, report parity, durable uncertain-write identities, goals/bills UI and strings; MVC inbox mark-read, dashboard performance, Manila defaults, session-store hardening; rendered and accessibility verification.
- **Joint contract agreements:** notification and summary DTOs, transaction version token, account contract.

## 11. Completion estimate

These are judgement-based, not measured.

| Layer | Implemented in source | Runtime-verified |
| --- | --- | --- |
| Backend API / financial services | ~85% | ~0% |
| MVC Member | ~85% | ~0% |
| MVC Administrator (current scope) | ~75% | ~0% |
| Android | ~65% | ~0% (unit tests only) |
| Worker | 0% | 0% |
| **Overall** | **~70%** | **~0–10%** (offline/unit evidence only) |

## Items explicitly carried into Phase 2 planning

Phase 2 planning must include each item below. None is fixed here.

| Item | Current state (Confirmed, source) | Refs |
| --- | --- | --- |
| MVC registration status mismatch | Register returns 201; MVC requires 200 and reports failure after creating the account. Runtime reproduction pending | H6 |
| Android release endpoint configuration | The release `ApiEnvironment` returns `ApiConfiguration.unconfigured()`. Only the debug source set selects an HTTPS endpoint, so a release build cannot reach any API. An approved release endpoint and its selection mechanism are needed | `android/app/src/release/.../ApiEnvironment.kt:5`; `src/debug/.../ApiEnvironment.kt:19` |
| Portable Administrator provisioning | `tools/PersonalFinance.AdminProvisioning` accepts only `(localdb)\MSSQLLocalDB` with Windows Authentication, so Administrator bootstrap is Windows-only. A portable approved target is needed | [Development admin provisioning](../Development_Admin_Provisioning.md) |
| Transaction idempotency payload mismatch | A replayed ClientId with a different payload returns the original silently | L1 |
| Savings contribution retry integrity | Android's uncertain contribution identity is memory-only, and contributions have no natural unique key (unlike bill payments' `(BillId, DueDate)`) | M4 |

## Unresolved decisions carried forward

Summary immutability versus recomputation; stored-summary access from both
clients; Development manual-job endpoint retirement; overdue-bill reminders;
budget deletion after alerts; administrator cross-user scope; account contract
unification; portable disposable SQL targets. All are from PRODUCT.md or newly
surfaced here. None is approved as new scope.
