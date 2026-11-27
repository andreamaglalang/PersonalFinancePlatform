# OWLI Phase 2 — Prioritized Implementation Roadmap

> Status: **approved with review amendments**, 26 November 2026 (Asia/Manila).
> Decisions D1–D5, D7–D9, D11 and D12 are approved. D6 and D10 remain owner-confirmation items (§7).
> Baseline: `dev` at `8083c72` (Phase 1 merge). Inputs: [PRODUCT.md](../../PRODUCT.md),
> [Phase 1 audit](../audits/OWLI_Phase1_Audit.md) (finding IDs below refer to it),
> [backend integration](../backend-integration.md), [worker preparation](../worker-config/README.md),
> [AGENTS.md](../../AGENTS.md). Planning only: no application, schema or data change was made.
> Target: feature-complete Friday 27 Nov night; Saturday 28 Nov night is final testing and contingency.

## 0. Targeted verification for this plan

These facts were re-checked at `8083c72`. They shape the plan.

| Fact | Evidence | Planning consequence |
| --- | --- | --- |
| `Transaction` has no rowversion. Account profile already uses a computed SHA-256 body `version` with stale → 409 | `Domain/Entities/Transaction.cs`; `AccountService.cs:34,76` | M3 can reuse the account pattern, **no migration** (D8) |
| `HangfireSetup.AddFinanceJobs` adds the server and `FinanceJobRegistration` whenever enabled, and storage is guarded only by catalog name | `HangfireSetup.cs:60-77`; `HangfireJobRegistration.cs:27-45` | The worker split is a DI move. The guard already supports a disposable finance catalog |
| `DevJobsController` is Development + Administrator, `POST api/dev/jobs/{job}/run` | `DevJobsController.cs:15,21` | Removing it is a single-file delete plus tests (D3) |
| SQL-live suites are pinned to Windows `(localdb)\MSSQLLocalDB`. `AuthenticationTests` already uses an env-var disposable target (`FINANCE_AUTH_SQL_TEST_CONNECTION`, `PersonalFinanceAuthTests_*` prefix, never `PersonalFinanceDb`) | `tests/*SqlLive*`, `HangfireTests/Program.cs:24-30`, `AuthenticationTests/SqlIntegration.cs:18-27` | Generalize the existing env-var pattern. Do not invent a new harness |
| 5 EF migrations; API startup never migrates | `Infrastructure/Migrations/`; deployment README | Disposable catalogs are built with `dotnet ef database update --connection` from empty |
| A local SQL Server container already hosts `PersonalFinanceDb` and the empty `OWLI_Hangfire` | worker-config README | The portable target already exists on the dev machine. No new infrastructure |
| Android debug base URL is `https://localhost:7027/`; release returns `unconfigured()` | `src/debug/.../ApiEnvironment.kt:16`; `src/release/.../ApiEnvironment.kt:5` | Emulator runtime tests use `adb reverse tcp:7027 tcp:7027` + dev-cert trust. Release needs a build-time endpoint (D10) |
| Android `ApiConfiguration.authUrl` allows only login/logout/refresh; there is no register screen | `ApiConfiguration.kt:10-13`; `ui/auth/` has Login only | H2 is a client-only change |
| No WorkManager dependency; Android report recalculates FX with `HALF_UP` and `require`s equality | `build.gradle.kts`; `FinancialReportCalculator.kt:74` | M5: no new dependency (D11). M2: switch Android to the server report and remove a throw path |

## 1. Prioritized roadmap

Priority: **P0** blocks acceptance; **P1** essential academic requirement; **P2** essential correctness; **P3** optional/improvement. Security and financial correctness are never demoted for schedule.

| # | Milestone | Findings | Pri | Owner |
| --- | --- | --- | --- | --- |
| R0 | Portable disposable SQL + runtime baseline + CI on `dev` | H5, L4, carry: portable provisioning | P0 | Codex |
| R1 | Early bidirectional smoke (Android ↔ MVC on one API/DB) | H5, H6 repro | P0 | Claude |
| R2 | Independent `PersonalFinance.Worker`; API processor-free | H1, M7, L5, L6 | P0 | Codex |
| R3 | Registration: MVC 201 fix, Android registration | H6, H2 | P1 | Claude |
| R4 | Transaction integrity: version token, ClientId mismatch (server) | M3, L1 | P2 | Codex |
| R5 | Android transaction delete + edit with version | H4, M3 | P1 | Claude |
| R6 | Durable uncertain-write identities (contributions, bill payments) | M4, carry: contribution retry | P2 | Claude |
| R7 | Notifications mark-read + stored summary read (server) | H3, M1, M6 | P1 | Codex |
| R8 | Client consumption: inboxes, summaries, Android server report | H3, M1, M2 | P1 | Claude |
| R9 | Goals/bills UI stabilization (Android typed UI, strings) | L3 | P1 | Claude |
| R10 | MVC correctness: Manila dates, dashboard paging | L2, M9 | P3 | Claude |
| R11 | Android release endpoint + deployment readiness | carry: release endpoint, M8 | P2 | Claude (Gradle/app), Codex (API/worker publish) |
| R12 | Final cross-platform runtime, accessibility and regression pass | H5, all | P0 | Both |

Decision-only items (no code unless approved): M5 offline scope (D11), M6 budget deletion (D5), M10 admin scope (D6), account contract split (D7), M8 session store (D12).

## 2. Milestones, dependencies and acceptance

Every branch starts from synchronized `dev` in its own worktree (`../owli-wt/<branch-leaf>`), is reviewed by the other agent, and merges into `dev` only with green CI and its acceptance evidence. All mutation tests use synthetic accounts on disposable catalogs only.

### R0 — Portable disposable SQL and runtime baseline (Codex, P0)

- **Branch:** `test/portable-sql-runtime`. **Depends on:** none. **Parallel with:** R2 (offline coding), R3, R9.
- **Files:** `tests/PersonalFinance.{SavingsSqlLiveTests,BillPaymentSqlTests}` target policies (**not** `HangfireTests`, which R2 owns); `tools/PersonalFinance.AdminProvisioning` target check + `docs/Development_Admin_Provisioning.md`; new short `docs/runtime/README.md` (commands only); `.github/workflows/handoff.yml` (add `dev` to push branches).
- **Outcomes:**
  - One documented procedure creates `OWLI_Runtime_<yyyymmdd>` (shared runtime/demo) and per-suite `PersonalFinance*Tests_<runId>` catalogs on the approved local SQL container. It applies all migrations from empty via `dotnet ef database update --connection`.
  - The same procedure creates a disposable `OWLI_HangfireTests_<runId>` catalog from the pinned Hangfire 1.8.25 `install.sql`, using the Phase 0 guarded method on an empty catalog. Operational `OWLI_Hangfire` and `PersonalFinanceDb` are never test targets; existing job history is never deleted.
  - SQL-live suites accept an explicitly authorized non-LocalDB target via env var, reusing the `AuthenticationTests` guards: required prefix, never `PersonalFinanceDb`/system catalogs, no `AttachDbFilename`. LocalDB remains supported.
  - Admin provisioning accepts the same explicit disposable target (Windows auth or SQL auth from a private env var). It never prints the connection.
- **Acceptance:** migration from empty PASS; `AuthenticationTests`, `SavingsSqlLiveTests` and `BillPaymentSqlTests` PASS against the container; an empty `OWLI_HangfireTests_*` catalog is provisioned (schema version 9, zero servers/jobs); an Administrator is provisioned on `OWLI_Runtime_*`. The `PersonalFinanceDb` schema/migration fingerprint is unchanged before and after. CI runs on a push to `dev`.
- **Risks:** SQL auth vs Windows-auth assumptions in policies; collation differences. **Done when:** the evidence is recorded in the PR and R1 can start.

### R1 — Early bidirectional smoke (Claude, P0)

- **Branch:** `test/runtime-smoke-baseline` (evidence/docs only). **Depends on:** R0 runtime catalog. **Parallel with:** R2, R4, R7.
- **Setup:** API (Development, `https://localhost:7027`) → `OWLI_Runtime_*`; MVC → API; Android debug emulator with `adb reverse tcp:7027 tcp:7027`.
- **Script (repeat after each client merge):**
  1. Register/log in (MVC; Android after R3).
  2. Android create transaction → MVC list/report shows it; MVC edit → Android refresh shows it.
  3. The same for category, budget, goal + contribution, bill + payment.
  4. FX: a non-base-currency transaction shows the identical snapshot rate/amountBase in both clients.
  5. Offline: Android airplane mode → create → pending → reconnect → confirmed, with no duplicate in MVC.
- **Acceptance:** an evidence table (PASS/FAIL/BLOCKED per step, commit SHAs). H6 is reproduced or refuted. Any new defect is logged against the milestone that owns it, with no fix on this branch.

### R2 — Independent worker (Codex, P0)

- **Branch:** `feature/worker-host`. **Depends on:** D1, D3 approval; runtime acceptance needs R0. **Parallel with:** all client work.
- **Files:**
  - New `PersonalFinance.Worker/` (csproj, `Program.cs`); `PersonalFinancePlatform.slnx`.
  - `Infrastructure/DependencyInjection.cs`; `Infrastructure/Jobs/HangfireSetup.cs`; `Api/Program.cs`.
  - Delete `Api/Controllers/DevJobsController.cs` and the alternative family `Jobs/HangfireJobRegistration.cs` registration/`FinanceAutomationJobs.cs`. Keep `EnsureDedicatedStorage`.
  - `tests/PersonalFinance.HangfireTests` (**R2 owns all changes**, including its target guard, using R0's disposable procedure); `docs/worker-config/README.md`.
- **Outcomes:**
  - Split `AddInfrastructure` into shared (DbContext, Identity stores, `FinanceJobs`, `IBudgetUsageService`, `IFinancialWriteScope`, `TimeProvider`) and HTTP-scoped (`ICurrentUser`-dependent) registrations.
  - Hangfire server + `FinanceJobRegistration` become worker-only. The API has **no** code path to a server or recurring registration, regardless of flags.
  - The worker loads `.local/worker/appsettings.Worker.Local.json` or env vars, validates the same instance/separate catalogs, keeps `PrepareSchemaIfNecessary=false`, and fails fast when invalid or disabled.
  - Same stable IDs, crons, `Asia/Manila`, job type/assembly identity and serializer settings.
- **HangfireTests adaptation:** retarget from the alternative family to `FinanceJobs`. Assert:
  - exactly the approved IDs `owli-bill-reminders`, `owli-budget-alerts`, `owli-monthly-summaries`, with their crons and `Asia/Manila`;
  - the persisted job type/assembly names are unchanged;
  - `AutomaticRetry(Attempts = 3)` and `DisableConcurrentExecution` behavior: a failing attempt is retried and a retry creates no duplicate financial effect;
  - worker host start, stop and restart;
  - the target is only `OWLI_HangfireTests_*` + a disposable finance catalog; it refuses `OWLI_Hangfire` and `PersonalFinanceDb`;
  - `EnsureDedicatedStorage` guards are preserved.
- **Other tests:** a worker host builds with `ValidateOnBuild`.
- **Acceptance A — API processor-free (separate check):** the API starts with `Hangfire:Enabled` both false and true. A host test asserts no Hangfire server/`IHostedService` and no recurring registration. At runtime, the API alone registers zero rows in `HangFire.Server` and writes nothing to `HangFire.Set`/recurring jobs.
- **Acceptance B — only the worker processes jobs:**
  - Against `OWLI_HangfireTests_*` + `OWLI_Runtime_*`: the worker registers 3 jobs, then stops, restarts and handles Ctrl+C.
  - With the worker stopped and the API running, a triggered job stays enqueued and unprocessed. Starting the worker processes it.
  - Each job triggered via a test-harness Hangfire client (`RecurringJob.TriggerJob`) gives synthetic users the expected reminders, alerts and summaries. A re-trigger creates no duplicates, and two concurrent triggers create no duplicate financial effects.
- **Operational cutover:** first use of operational `OWLI_Hangfire` follows the worker-config README (inspect state first; no history deletion). It is not part of automated tests.
- **Risks:** `Microsoft.AspNetCore.App` framework reference (Identity) is required by the worker — accepted. Persisted job type names must not change.

### R3 — Registration (Claude, P1)

- **Branches:** `fix/mvc-registration-status` (first, tiny), then `feature/android-registration`. **Depends on:** none (API unchanged). **Parallel with:** everything.
- **MVC files:** `AdminMvc/Services/AuthenticationApiClient.cs` (accept `201 Created` for register, keep 200 for others); `tests/PersonalFinance.AdminMvc.UiTests` regression (201 → success/redirect, 409 → "already registered").
- **Android files:** `data/remote/ApiConfiguration.kt` (`register` op), `AuthApiClient.kt`, `data/auth/AuthRepository.kt`, new `ui/auth/RegisterScreen.kt` + `RegisterViewModel.kt`, navigation from Login, `res/values/strings.xml`, unit tests + one Compose UI test.
- **Behavior:** mirrors MVC. Success shows "account ready, sign in" with no auto-login (the API returns no session). Field errors map from 400 ProblemDetails, 409 → already registered, 429 → wait. The password is never retained or logged.
- **Acceptance:** UI test PASS; runtime: register in MVC → log in on Android, and register on Android → log in on MVC; a duplicate email gives 409 in both.

### R4 — Transaction integrity, server (Codex, P2)

- **Branch:** `fix/transaction-concurrency-idempotency`. **Depends on:** contract C1/C2 agreement. **Parallel with:** R3, R7.
- **Files:** `Infrastructure/Finance/TransactionService.cs`, `Finance/Contracts/*Transaction*`, `Api/Controllers/TransactionsController.cs`, `tests/PersonalFinance.FinanceTests`, `docs/backend-integration.md`.
- **Outcomes:** C1 computed `version` on every transaction response; PUT requires the matching `version` → stale 409. C2 replayed ClientId with a different canonical payload → 409; an identical replay keeps today's success.
- **Acceptance:** unit tests for stale, missing and malformed versions and payload mismatch/match; a SQL-live concurrent PUT test on a disposable catalog (one 200, one 409); MVC edit continues to work after R5's client change.
- **Integration:** R4 never merges to `dev` alone. See R4+R5 integration below.

### R5 — Transaction delete and versioned edit, clients (Claude, P1)

- **Branch:** `feature/transaction-delete-version`. **Depends on:** R4 contract (code can start against C1 spec).
- **Files:**
  - Android: `domain/repository/TransactionUpdateRepository.kt`, `data/remote/RemoteTransactionRepository.kt`, `ApiConfiguration.kt` (DELETE route), `ui/transactions/edit/*` (delete with confirm), list refresh, strings, tests.
  - MVC: transaction edit form/client sends `version` and shows a 409 "changed elsewhere, reload" message.
- **Acceptance:** Android delete → MVC no longer lists it, and the reverse. A concurrent edit from both clients → one succeeds and the other shows a conflict without losing data. Deleting a pending offline item removes it locally only.

### R4 + R5 integration — one compatible contract change (Codex + Claude)

- **Branch:** `integration/transaction-contract`, created from synchronized `dev`. Codex merges `fix/transaction-concurrency-idempotency` into it; Claude merges `feature/transaction-delete-version` into it. Neither task branch merges to `dev` directly.
- **Gate:** on the combined head, all .NET suites, MVC UiTests and Android unit tests PASS, and the R1 script plus versioned edit/conflict/delete steps PASS at runtime for MVC and Android against one API build.
- **Single PR** from the integration branch to `dev`, reviewed by both agents. `dev` therefore never contains a server that requires `version` with clients that do not send it, or the reverse. Android delete (no contract change) may ship earlier on its own branch if the edit path is excluded.

### R6 — Durable uncertain-write identities (Claude, P2)

- **Branch:** `fix/android-durable-write-identity`. **Depends on:** none (server already dedupes `(UserId, ClientId)` and `(BillId, DueDate)`).
- **Files:** `data/remote/LiveAccountPlanRepository.kt`, `ui/plan/LivePlanSection.kt`, Room (new `pending_requests` table + migration in `OwliDatabase.kt`), tests.
- **Outcome:** a contribution or bill-payment ClientId and payload are persisted **before** sending, reused on every retry, including after process death, and cleared only on a definitive server answer. Uncertain records are shown as pending, never as confirmed.
- **Acceptance:** a unit test simulating process death between send and response; runtime: kill the app mid-request → relaunch → retry → exactly one contribution in MVC.

### R7 — Notifications and stored summaries, server (Codex, P1)

- **Branch:** `feature/notification-summary-contracts`. **Depends on:** C3/C4 agreement, D1, D2, D5.
- **Files:** `Finance/NotificationService.cs`, `Api/Controllers/NotificationsController.cs`, new summary read in `ReportService.cs`/`ReportsController.cs`, `BudgetService.cs:97-104` (if D5 is approved), FinanceTests, `docs/backend-integration.md`.
- **Acceptance:** owner-scoped mark-read (another user's ID → 404), idempotent; `unreadOnly` works; summaries list is owner-scoped and empty-safe; tests PASS; runtime: a worker-generated summary/alert is visible via the API.

### R8 — Client consumption and report parity (Claude, P1)

- **Branch:** `feature/client-notifications-summaries`. **Depends on:** R7 merged (UI can start against C3/C4).
- **Files:**
  - Android: new `ui/notifications/` + repository, `domain/reports/LoadFinancialReport.kt` → call `GET /api/reports/monthly` (C6), summary list, navigation, strings.
  - MVC: the notifications view gets mark-read/mark-all; a summaries section in Reports.
- **Acceptance:** for the same synthetic month, Android and MVC report totals (income, expense, savings contributed, budgets) are identical to the cent. Marking read in one client shows as read in the other after refresh.

### R9 — Goals and bills UI stabilization (Claude, P1)

- **Branch:** `fix/android-goals-bills-ui`. **Depends on:** none; coordinate with R6 (same files — **R6 merges first**, R9 rebases).
- **Outcome:** typed models replace raw `JsonObject` rendering; ~49 strings move to resources; existing OWLI Compose components; loading/empty/error/pending states; TalkBack labels.
- **Acceptance:** Compose UI tests for the list/create/contribute/pay flows; runtime parity with MVC values.

### R10 — MVC correctness polish (Claude, P3, optional)

- **Branch:** `fix/mvc-manila-dates-dashboard`. L2: use a shared Manila `TimeProvider`/helper for default months. M9: dashboard default month instead of all-time, or bounded paging. Acceptance: UiTests; dashboard loads within one page fetch for the default view.

### R11 — Release endpoint and deployment readiness (Claude + Codex, P2)

- **Branch:** `chore/android-release-endpoint` (Claude); worker/API publish additions on `chore/deployment-worker-publish` (Codex).
- **Android:** release `ApiEnvironment` reads a Gradle property (`owliApiBaseUrl` → `BuildConfig`), validated by the existing `ApiConfiguration.https`. If it is absent, the release build shows the existing unconfigured state; there is no default host. `assembleRelease` with a placeholder HTTPS URL succeeds in CI.
- **Server:** `Publish-OWLI.ps1`/README add the worker as a third supervised process. No deployment is performed without approval and a supplied host (D10).

### R12 — Final runtime, accessibility and regression pass (Both, P0)

- **Branch:** `test/final-acceptance` (evidence only). **Depends on:** all merged milestones.
- **Scope:** the full checklist in §5 on fresh `OWLI_Runtime_*`; all offline suites; SQL-live suites; Android `testDebugUnitTest`, `testReleaseUnitTest`, `connectedDebugAndroidTest` on an emulator; MVC rendered at 360/768/1280 px with keyboard and screen reader spot checks; Android font scale 200% and TalkBack spot checks; worker lifecycle.
- **Done when:** every essential checklist row is PASS. An essential row that is BLOCKED or NOT TESTED stays **incomplete** and is reported as such; it is never counted as accepted or complete. `dev` → `main` is requested from the owner (explicit approval only).

## 3. Dependency matrix and parallel lanes

| Milestone | Hard dependency | Can run in parallel with | Shared-file risk |
| --- | --- | --- | --- |
| R0 | — | R2 (offline), R3, R9 | `handoff.yml`; does **not** edit `HangfireTests` |
| R1 | R0 | R2, R4, R7 | none (evidence) |
| R2 | D1, D3; R0 for runtime | all client lanes | `DependencyInjection.cs`, `Api/Program.cs`, all of `tests/PersonalFinance.HangfireTests` (Codex, R2 only) |
| R3 | — | all | `AuthenticationApiClient.cs` (Claude only) |
| R4 | C1, C2 | R3, R7 | `TransactionService.cs` (Codex only); merges only via `integration/transaction-contract` |
| R5 | R4 (joint integration branch) | R6, R9 | `ApiConfiguration.kt` with R3 — R3 first |
| R6 | — | R5, R7 | `LivePlanSection.kt` with R9 — R6 first |
| R7 | C3, C4, D1, D2, D5 | R4, client lanes | `ReportService.cs` (Codex only) |
| R8 | R7 | R9, R10 | Android nav graph with R3 — R3 first |
| R9 | R6 | R8, R10 | see R6 |
| R10 | — | all | MVC controllers (Claude only) |
| R11 | R2 (worker publish) | all | `build.gradle.kts` (Claude only) |
| R12 | all essential | — | — |

**Critical path:** R0 → R2 runtime acceptance → R7 → R8 → R12. Client registration/delete/durability (R3, R5, R6) run beside it.

## 4. Codex / Claude Code allocation and contracts

**Codex owns:** R0, R2, R4, R7, the R11 server part. Files: `PersonalFinance.Api/**`, `PersonalFinance.Infrastructure/**`, `PersonalFinance.Domain/**`, new `PersonalFinance.Worker/**`, `tests/` (except `AdminMvc.UiTests`), `tools/**`, `.github/**`, `docs/backend-integration.md`, `docs/worker-config/**`.

**Claude Code owns:** R1, R3, R5, R6, R8, R9, R10, the R11 Android part. Files: `PersonalFinance.AdminMvc/**`, `tests/PersonalFinance.AdminMvc.UiTests/**`, `android/**`, runtime evidence docs.

**Both:** independent review of the other's PRs; R12; wiki updates for their own milestones. Contract PRs (C1–C4) land server-side first with `docs/backend-integration.md` updated in the same PR; the client agent approves the contract section before merge.

### Required API contract agreements

| ID | Contract (proposed default) | Server | Clients |
| --- | --- | --- | --- |
| C1 | `TransactionResponse.version`: uppercase 64-hex SHA-256 of mutable fields (account-profile pattern). `PUT /api/transactions/{id}` body requires `version`; stale → 409 ProblemDetails. DELETE needs no version; already deleted → 404 | R4 | R5 (both) |
| C2 | Create replay: same `clientId` + same canonical payload → existing success; different payload → 409 "ClientId reused with different data" | R4 | Android treats 409 as rejected, not retryable |
| C3 | `POST /api/notifications/{id}/read` → 204 (idempotent; unowned → 404); `POST /api/notifications/read-all` → 204 | R7 | R8 (both) |
| C4 | `GET /api/reports/summaries?page&pageSize` → paged, newest first, owner-scoped; fields mirror `MonthlySummary`: summaryMonth, totalIncome, totalExpense, netCashFlow, savingsAllocated, generatedAtUtc, plus the user's baseCurrency. `EmailSentAtUtc` is not exposed. No write endpoint | R7 | R8 (both) |
| C5 | Registration unchanged: `POST /api/auth/register` → 201 `CurrentUserResponse`, no session | none | R3 |
| C6 | Android report uses the existing `GET /api/reports/monthly?month=YYYY-MM-01` | none | R8 |
| C7 | Contributions/bill payments: no change; the client must reuse the persisted ClientId | none | R6 |

## 5. Academic requirements and parity acceptance checklist

E = essential (PRODUCT.md); O = optional. Each row needs PASS evidence on runtime catalogs in R12. BLOCKED/NOT TESTED essential rows remain incomplete.

| # | Requirement | E/O | Android | MVC | Evidence |
| --- | --- | --- | --- | --- | --- |
| 1 | Register, login, refresh, logout, profile, password | E | R3 | R3 | runtime both directions |
| 2 | Transactions create/list/edit/delete with FX snapshot | E | R5 | ✓ | cross-client + SQL concurrency |
| 3 | Categories (member) + admin defaults | E | ✓ | ✓ | runtime |
| 4 | Budgets with status/threshold and alerts | E | ✓ | ✓ | worker alert visible in both |
| 5 | Savings goals + contributions (If-Match, receipts, no duplicates) | E | R6, R9 | ✓ | process-death retry |
| 6 | Recurring bills + payments (receipts) | E | R6, R9 | ✓ | duplicate payment rejected |
| 7 | Monthly report identical across clients | E | R8 | ✓ | same-cent totals |
| 8 | In-app notifications, read state | E | R8 | R8 | mark-read cross-client |
| 9 | Stored monthly summaries readable (if D2) | E | R8 | R8 | worker-generated row visible |
| 10 | Currency preferences + Frankfurter quote, unavailable rate → 503 handled | E | ✓ | ✓ | runtime + offline test |
| 11 | Android offline create, pending/rejected/confirmed, retry, no duplicates | E | ✓/R6 | n/a | emulator airplane-mode |
| 12 | Separate worker, API processor-free, 3 Manila schedules, restart/retry safe | E | — | — | R2 runtime |
| 13 | Admin role-based budgets/categories/reports preserved; portable provisioning; cross-user scope per D6 | E | n/a | ✓ (D6 pending) | R0 + runtime |
| 14 | Responsive, accessible, consistent OWLI branding | E | R9 | ✓ | R12 rendered checks |
| 15 | Ownership/isolation (user A cannot see/modify B) | E | ✓ | ✓ | API + runtime two users |
| 16 | Android release endpoint configurable | E (deploy readiness) | R11 | n/a | `assembleRelease` |
| 16a | Live deployment of API, MVC, worker and release APK endpoint | E (per D10, pending) | R11 | R11 | deployed smoke test; BLOCKED until host/credentials supplied |
| 17 | MVC Manila default months, dashboard paging | O | n/a | R10 | UiTests |
| 18 | Background/WorkManager sync, distributed MVC sessions | O | — | — | not planned (D11, D12) |

## 6. Runtime verification and deployment strategy

- **Targets:** the approved local SQL container only. `OWLI_Runtime_<date>` for shared runtime/demo; `PersonalFinance*Tests_<runId>` per SQL suite; `OWLI_HangfireTests_<runId>` for worker/Hangfire tests. Operational `OWLI_Hangfire` is used only after controlled cutover, never by tests, and its history is preserved. `PersonalFinanceDb` is not migrated or written without separate approval (D9).
- **Cadence:** R1 smoke after R0, then re-run its script after every client merge (≈15 min). Bidirectional verification is continuous, not end-loaded.
- **Evidence:** each PR records commands, commit, PASS/FAIL/BLOCKED/NOT TESTED; R12 consolidates into one acceptance record (`docs/audits/OWLI_Phase3_Acceptance.md`). No secrets, hosts, tokens or real financial data.
- **Deployment:** live deployment stays in the final deliverable unless the owner decides otherwise (D10). R11 produces deployment-ready artifacts (API, MVC, worker; Android release APK with configured endpoint). The live deployment and its smoke test run once the owner supplies the host, certificates and secrets and approves. Until then, row 16a is BLOCKED/incomplete. `dev` → `main` needs explicit owner approval after R12.

## 7. Decisions

Approved with the review safeguards: D1–D5, D7–D9, D11, D12. **Owner confirmation still required: D6.** D10 is resolved (Phase 5: external deployment team).

| ID | Decision | Status / resolution | Trade-off |
| --- | --- | --- | --- |
| D1 | Summary immutability | **Approved.** Keep immutable snapshots (current `FinanceJobs`); the live report stays the source of truth for corrected numbers | Late transactions are not reflected in a snapshot; recomputation would need a rewrite policy and new tests |
| D2 | Summary access from both clients | **Approved.** Yes, read-only list (C4) | Small API + 2 client screens; skipping leaves summary notifications pointing at nothing (M1) |
| D3 | Development manual-job endpoint | **Approved.** Delete `DevJobsController`; trigger jobs in tests via a Hangfire client against disposable `OWLI_HangfireTests_*` storage | Loses a convenience endpoint; removes an API execution path PRODUCT.md forbids |
| D4 | Overdue-bill reminders | **Approved.** Keep the current due-soon-only behavior | Overdue bills get no reminder; adding one needs a new dedup key and tests |
| D5 | Budget deletion after alerts | **Approved.** Allow deletion; keep notification history as text with a stale reference. Verified: `Notification` has an FK only to the user (`NotificationConfiguration.cs:19`), so no migration is needed | Alternative (archive flag) needs a migration |
| D6 | Administrator cross-user scope | **Owner confirmation required.** Until confirmed, preserve all existing admin features unchanged; nothing is removed or restricted. If the academic requirement includes cross-user reports/budgets, add an Administrator milestone (Codex endpoints with role checks and privacy rules; Claude MVC views) | Cross-user views need new endpoints and privacy rules; not confirming risks an unmet academic requirement |
| D7 | Account contract split (`auth/*` vs `account/*`) | **Approved.** Keep both, documented | Duplicate contracts remain; unifying risks both clients late |
| D8 | Transaction version mechanism | **Approved.** Computed hash body version (no migration) | A rowversion would need migration 6 and an If-Match style matching savings |
| D9 | Runtime/demo database | **Approved.** Disposable `OWLI_Runtime_<date>`; leave `PersonalFinanceDb` untouched | The demo uses synthetic data; any real migration of `PersonalFinanceDb` is a separate approval |
| D10 | Release endpoint and live deployment | **Resolved (Phase 5, 27 Nov 2026):** the external deployment team hosts OWLI through its existing Cloudflare configuration and owns hosting, production data, secrets, signing and Hangfire activation. This team delivers a verified `dev` branch and [the deployment handoff guide](../../deployment/README.md). The release endpoint stays a build-time property with no default host | Production deployment and production runtime verification are reported by the deployment team, separately from local release readiness |
| D11 | Android offline scope | **Approved.** Keep transaction-create offline + durable retry IDs (R6); retry on app start/connectivity; no WorkManager, no offline edit/delete | No background sync while the app is closed |
| D12 | MVC session store | **Approved.** Accept the in-process store with a single MVC instance (already documented) | Restart signs users out |

## 8. Progress estimate and schedule risks

| | Now (judgement) | Target Friday night |
| --- | --- | --- |
| Source implementation | ~70% | ~90–95% (all essential rows) |
| Runtime-verified | ~0–10% | ~70% essential rows; rest Saturday |

**Proposed timeline (Asia/Manila):**

| Window | Codex | Claude Code |
| --- | --- | --- |
| Thu 26 night | R0; R2 DI split + tests (offline) | R3 MVC fix → R3 Android; R6 |
| Fri 27 morning | R2 runtime acceptance; R4 | R1 smoke; R5 (against C1) |
| Fri 27 afternoon | R7 | R9; R8 (against C3/C4) |
| Fri 27 night | R11 server; R4+R5 integration gate; review Claude PRs | R8 finish; R11 Android; R4+R5 integration gate; review Codex PRs; feature freeze |
| Sat 28 | R12 jointly; live deployment if D10 supplied; fixes only; R10 if time | R12 jointly; deployed smoke test |

**Risks:**
- **Very tight:** ~12 milestones in about 30 working hours. R10 and parts of R9 polish are the first to drop; no essential correctness item is dropped.
- **R0 slip** blocks all runtime evidence. Mitigation: start R0 first; offline work continues meanwhile.
- **Emulator ↔ localhost HTTPS** (dev certificate trust on the emulator) may block R1. Mitigation: verify in the first 30 minutes of R1; fall back to an instrumentation test with the existing fixture engine and report BLOCKED honestly.
- **Coordinated breaking change** (R4/R5 PUT version). Mitigation: same-day paired merge.
- **Worker job identity drift** breaks persisted definitions. Mitigation: a registration test asserts type/assembly names.
- **D6/D10 confirmation latency:** a cross-user admin requirement adds a milestone, and late hosting details compress live-deployment testing. Mitigation: request both now; R11 artifacts are prepared regardless.

## 9. Recommended first parallel tasks (after approval)

1. **Codex — R0** `test/portable-sql-runtime`: disposable catalogs + migrations from empty; generalize SQL-live targets; CI on `dev`.
2. **Codex — R2 (offline part)** `feature/worker-host`: DI split, worker project, API processor-free host test, remove `DevJobsController`, adapt `HangfireTests` to `FinanceJobs` (sole owner of those files).
3. **Claude — R3** `fix/mvc-registration-status` (≈1 h, merge first), then `feature/android-registration`.
4. **Claude — R6** `fix/android-durable-write-identity` (independent of all server work).

R1 starts as soon as R0 delivers `OWLI_Runtime_*`.
