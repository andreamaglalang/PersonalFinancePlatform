# OWLI — Academic requirements traceability (27 November 2026)

**Original requirement — Personal Finance and Budget Monitoring Platform:** track expenses, recurring bills, budgets,
savings goals and financial reports across web and mobile. Approved additions (PRODUCT.md, Phase 2 roadmap):
- complete Android–MVC Member parity;
- one shared API and database;
- bidirectional synchronization;
- an independent Hangfire console application.

**Code under test:** `dev` `bc652ce` (PRs #28, #29) plus the round-3 corrections (R3-1 to R3-6) on `test/final-functional-regression`.

**Evidence:**
- **E2E:** [end-to-end record](OWLI_E2E_Acceptance.md).
- **P3:** [Phase 3–5 record](OWLI_Phase3_Acceptance.md).
- **V:** [validation record](OWLI_Validation_Feedback.md).

**Labels:** PASS / FAIL / BLOCKED / NOT TESTED. A row is PASS only with runtime evidence through the intended user
interface.

## Matrix

| # | Original requirement | Implemented functionality | Source references | Platforms | Runtime evidence | Regression tests | Status | Limitations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | **MVC: budget administration** | Members: create, edit, delete monthly category budgets with alert threshold; Plan shows spent/remaining and On track / Near limit / Exceeded status. Administrator: Budget Administration (list, month filter, create) for the administrator's own account | `AdminMvc/Areas/Member/Controllers/BudgetsController.cs`, `AdminMvc/Controllers/BudgetsController.cs`, `Api/Controllers/BudgetsController.cs`, `Infrastructure/Finance/BudgetService.cs` | MVC member + administrator; Android Plan (parity) | E2E: create → over limit (300 of 200) → duplicate refused with reason → edit 500 → delete. Administrator month filter, Clear, create (₱250 at 75%, confirmed in SQL). All three status colours pass AA in both themes (E4) | MVC UiTests (`MemberBudgetChecks`, `PlanBudgetSummaryChecks`); FinanceTests `BudgetTests` | **PASS** | The administrator manages budgets in their own scope only. Cross-user administration is **D6, unresolved (owner decision)**; no cross-user access was added |
| A2 | **MVC: category management** | Members: personal categories (create, rename, colour, deactivate, delete unused; in-use deletion refused). Administrator: shared default categories (create, rename, colour, confirmed deactivation, duplicate check) | `AdminMvc/Areas/Member/Controllers/CategoriesController.cs`, `AdminMvc/Controllers/CategoriesController.cs`, `Api/Controllers/CategoriesController.cs` | MVC member + administrator; Android category manager | E2E: member create/rename/delete/in-use refusal. Administrator create, rename, deactivation dialog (Cancel keeps it active; confirm deactivates), duplicate reason, palette colour saved (`#238A87`) | CategoryUniquenessTests 15; MVC UiTests `MemberCategoryManagementChecks` | **PASS** | — |
| A3 | **MVC: financial reports** | Member Insights (monthly totals, categories, budgets, savings, stored summaries); administrator Financial Reports with month selection | `AdminMvc/Areas/Member/Controllers/ReportsController.cs`, `AdminMvc/Controllers/ReportsController.cs`, `Infrastructure/Finance/ReportService.cs` | MVC member + administrator; Android Insights | E2E: Insights totals equal `GET /api/reports/monthly` to the cent; previous month shows that month's expense (20.00). P3 row 7: Android = API = MVC. Administrator Apply month | FinanceTests `ReportsOnlyTests`, `MonthlySummaryReferenceTests`; UiTests `InsightsRenderChecks` | **PASS** | Administrator reports cover the administrator's own data (D6) |
| A4 | **Web API: expense recording** | `POST/PUT/DELETE /api/transactions` with ClientId idempotency, version concurrency (C1/C2), FX snapshot, budget usage | `Api/Controllers/TransactionsController.cs`, `Infrastructure/Finance/TransactionService.cs` | API; used by MVC and Android | E2E: MVC create/edit/delete persisted; cross-client gate 14/14 (stale form 409, no lost update); Android offline → sync once; lost response → same ClientId → one row; slow API with 3 taps → one row. Round 3 (R3-3): MVC same form ×3 → one row (was 2) | FinanceTests 441; SqlIntegration 16 (real concurrency); UiTests R3-3 | **PASS** (after R3-3) | — |
| A5 | **Web API: budget validation** | Budget limits, threshold 1–100, one budget per category and month, usage recalculated from expenses | `Api/Controllers/BudgetsController.cs`, `Infrastructure/Finance/BudgetService.cs`, `BudgetUsageService.cs` | API → MVC/Android | E2E: expense crossing the limit shows Exceeded; worker alert at 50% (80 of 100); duplicate refused with "A budget already exists…"; invalid amount/threshold give specific messages (V) | FinanceTests `BudgetTests`; ApiSecurity | **PASS** | — |
| A6 | **Web API: recurring bill endpoints** | Bills CRUD; `POST …/payments` pays the oldest due occurrence with ClientId replay; receipts; next-due advance on the Philippine calendar; protected history | `Api/Controllers/RecurringBillsController.cs`, `Infrastructure/Finance/RecurringBillService.cs`, `BillPaymentService.cs` | API → MVC/Android | E2E: payment → one transaction and next due 27 Nov → 27 Dec; early payment refused. Round 2 (F2): same form twice → one payment; stale form after Android paid → nothing else paid; three overdue occurrences paid oldest first. Android bill + payment | FinanceTests `BillPaymentTests` (month-end, Philippine midnight, retry); BillPaymentSql suites | **PASS** (after F2) | — |
| A7 | **Hangfire: upcoming bill reminders** | `owli-bill-reminders` daily 07:00 Asia/Manila in the independent worker | `PersonalFinance.Worker/Program.cs`, `Infrastructure/Jobs/FinanceJobs.cs`, `HangfireSetup.cs` | Worker → MVC Inbox, Android inbox | P3 row 12: the worker ran the missed 07:00 reminder and created "due in 2 days"; notifications visible on both clients (P3 row 8) | HangfireTests 13 (live: retry, dedupe, restart, no processing without the worker); FinanceTests `RecurringBillReportJobTests` | **PASS** | — |
| A8 | **Hangfire: monthly summaries** | `owli-monthly-summaries` day 1 at 00:30 Manila; stored snapshots (C4) | `Infrastructure/Jobs/FinanceJobs.cs`, `Infrastructure/Finance/ReportService.cs` | Worker → MVC Insights, Android Insights | P3 row 9: snapshot in MVC; Phase 5 closeout: the October 2026 snapshot renders on an Android device | HangfireTests; `MonthlySummaryReferenceTests` | **PASS** | Produced by triggering the job on test storage; a natural first-of-month run was not waited for |
| A9 | **Hangfire: budget threshold alerts** | `owli-budget-alerts` hourly | same | Worker → MVC, Android | E2E: one alert "E2E Dining budget reached 50%: PHP 80.00 of PHP 100.00."; re-run creates no duplicate; marked read on MVC (persisted). P3: an hourly run fired on its own (job 9, 08:00 Manila) and the alert was visible on Android | HangfireTests | **PASS** | — |
| A10 | **Independent Hangfire console** | `PersonalFinance.Worker` Generic Host; API hosts no Hangfire; dedicated `OWLI_Hangfire` (disposable `OWLI_HangfireTests_*` locally); exits 0 when disabled, 2 on a wrong storage pairing | `PersonalFinance.Worker/Program.cs` | Worker | P3 row 12: SIGTERM stop and deregistration; API has 0 servers; publish verified; worker running separately this round (1 server, 3 recurring jobs) | HangfireTests; FinanceTests `ApiWorkerIsolationTests` | **PASS** | Operational `OWLI_Hangfire` not activated (by design; deployment team, guide §9) |
| A11 | **Kotlin: expense entry** | Add expense/income with category picker, currency, date, description, validation; the server converts other currencies | `ui/transactions/add/AddTransactionScreen.kt`, `AddTransactionViewModel.kt`, `data/remote/FinanceCreationTransport.kt` | Android | E2E device steps: offline add, rotation keeps the form (E3), Back asks before discarding, slow API with 3 taps → one row; cross-client gate | Android unit 672/678; instrumented 200+; R3-6 unit test | **PASS** | Round 3 (R3-6): an invalid amount now names the problem instead of only disabling Save |
| A12 | **Kotlin: local transaction history** | Room `local_transactions` per account; on-device history (up to 200) with Pending / Rejected / Synced states | `data/local/room/OwliDatabase.kt`, `data/local/OfflineLedger.kt` | Android | E2E: history shows pending and synced entries; account switch shows no other account's data; after the in-place upgrade the history was kept | `OwliDatabaseMigrationTest` (v1 → 3, v2 → 3); unit tests | **PASS** | — |
| A13 | **Kotlin: offline-first recording using Room** | Offline save queues with a durable ClientId; auto-sync on reconnect; durable pending contributions/payments (`pending_requests`) | `data/local/OfflineFirstCreation.kt`, `OfflineLedger.kt`, `data/remote/LiveAccountPlanRepository.kt` | Android → API → MVC | E2E device step: offline → "1 waiting to sync" → reconnect → exactly one row (second sync attempt makes no copy). Round 2: lost response → queued → synced once; **process death** with a pending contribution → recovered after restart, one contribution; **application upgrade** v2 → v3 kept and synced a pending expense once | Instrumented `OfflineSaveNavigationTest`, migration tests; `PersistentSessionReliabilityTest` | **PASS** | Upgrade verified with the debug key; the deployment team must repeat it between release-signed builds. Round 3: API-only outage (network kept) → saved on device, then synced **automatically** once the API returned (bounded retry, no tap), one row |
| A14 | **External API: Frankfurter reference rates** | Fixed HTTPS provider; quotes; historical rate snapshot stored with each foreign transaction; 503 on outage; cached rates within the age policy | `Infrastructure/Finance/FrankfurterClient.cs`, `ConversionService.cs`, `ExchangeRateQuoteService.cs` | API → MVC converter/forms, Android currency picker | P3 row 2/10: live USD quote and snapshot. Phase 5 closeout: real provider outage → 503 on API, MVC message, Android message; nothing stored; PHP unaffected. E2E: MVC converter USD→PHP with rate date. Round 3: MVC rate review fixed (R3-2), USD expense and USD bill payment stored with the quoted rate; Android converter equals the API quote (USD, EUR) | FinanceTests `ExchangeRateQuoteTests`; UiTests R3-2 | **PASS** (after R3-2) | — |
| A15 | **Android–MVC Member parity** | Same features on both clients: dashboard, transactions, categories, budgets, goals and contributions, bills and payments, converter, reports, notifications, account | Both clients; shared contracts in `docs/backend-integration.md` | Android, MVC | P3 parity rows; E2E coverage table; cross-client gate; shared validation messages and allowlist (V) | Both suites | **PASS** | Round 3: all 273 MVC controls exercised; Android search/filters, Insights months, converter, category edit/reorder and inbox actions run on a device. Goal progress: same real percentage on both clients (bars capped at 100%) |
| A16 | **Shared API and database; bidirectional sync** | One API and one SQL Server database for both clients; changes visible both ways | API + `PersonalFinanceDb` schema (5 migrations) | All | Cross-client gate 14/14 (Android → MVC and MVC → Android); stale-form protection both ways (F3 and the transaction gate); notifications read both ways (P3 row 8) | SqlIntegration, SchemaValidation 65 | **PASS** | — |

## Error handling, integrity and recovery (applies across rows)

- **Validation:** every rule names the actual problem on both clients (V, 17 fixes). No safeguard was weakened.
- **Idempotency:**
  - transactions, contributions and payments replay instead of duplicating, whether the same form is submitted again or the app retries after a timeout or a lost response;
  - an independent second action is still recorded (F1, F2; Android device runs).
- **Concurrency:** stale edits and deletes are refused on both clients (C1, F3).
- **Outages:**
  - API down → specific messages, no false success;
  - provider down → 503 handling;
  - session expiry → "Your session has expired. Please sign in again."

## Integration status

- **Merged in `dev`:** A1–A16 were implemented by PRs up to #27.
- **Changed after #27:** the fixes in PR #28 (validation) and PR #29 (E1–E7, F1–F4, guide).
- **Round 3:** the fixes R3-1 to R3-6 (end-to-end record, round 3). The final verified commit is recorded there after merge.

## Not claimed

- Production deployment and production runtime: deployment team (D10).
- D6 (administrator cross-user scope): unresolved.
- Spoken screen-reader output; physical devices.
- A populated pre-migration-4 database upgrade: production rehearsal only.
