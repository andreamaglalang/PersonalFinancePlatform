# OWLI Phase 3 — Implementation and acceptance record

> Status: 26–27 November 2026 (Asia/Manila). Single-agent implementation of the approved
> [Phase 2 roadmap](../plans/OWLI_Phase2_Roadmap.md). Every change is on a reviewed-PR branch from `dev`;
> **no PR is merged**, because the required independent review is pending. Evidence labels:
> PASS / FAIL / BLOCKED / NOT TESTED. An essential row that is not PASS remains **incomplete**.

## Environment used for runtime evidence

- Local SQL Server container: disposable `OWLI_Runtime_20261126` (all 5 migrations from empty) and
  `OWLI_HangfireTests_runtime20261126`. The harnesses also used per-run `PersonalFinanceSqlTests_*` /
  `OWLI_HangfireTests_*` catalogs, which were dropped afterwards. `PersonalFinanceDb` was never connected to.
  Operational `OWLI_Hangfire` was read once, read-only, and was empty.
- API (`https://localhost:7027`, Development) and MVC (`https://localhost:62978`) ran from a local, unpushed
  branch that merges every PR branch.
- Android 16 emulator (Google APIs image) with the ASP.NET development certificate as a user CA, plus
  `adb reverse tcp:7027`. Device steps use the app's real auth and finance repositories
  (`CrossClientRuntimeTest`, opt-in per step).
- Rendered MVC: headless Chrome plus axe-core 4.10 (WCAG 2 A/AA) at 360/768/1280 px, dark and light themes.
- Synthetic accounts only. No credentials, hosts or financial data are recorded here.

## Pull requests (all target `dev`; CI green unless noted; review pending)

| PR | Milestone | Findings |
| --- | --- | --- |
| #4 | R0 portable SQL harness, runtime catalog, portable admin provisioning, CI on `dev` + `sql` job | H5 (partial), L4, carry: provisioning |
| #5 | R3a MVC registration accepts 201 | H6 |
| #6 | R2 independent `PersonalFinance.Worker` (stacked on #4) | H1, M7, L5, L6 |
| #7 | R3b Android registration | H2 |
| #8 | R6 durable contribution/payment identities | M4, carry: contribution retry |
| #9 | R7 C3 mark-read, C4 summaries, D5 budget deletion | H3, M1, M6 (server) |
| #10 | R8 MVC inbox read state, snapshots, savings in overview (stacked on #9) | H3, M1, M2 (MVC) |
| #11 | R10 MVC Manila dates | L2 (M9 deferred) |
| #12 | R4+R5 single integration PR: C1 version token, C2 mismatch, Android delete/versioned edit, MVC versioned edit | M3, L1, H4 |
| #13 | R11 Android build-time release endpoint, deployment notes | carry: release endpoint |
| #14 | R8 Android inbox, snapshots, server report | H3, M1, M2 (Android) |
| #15 | R9 typed goals/bills UI and strings (stacked on #8) | L3 |
| #16 | Auth rate limit per user + MVC session-check reuse (defect found in R12) | new |
| #17 | MVC WCAG AA contrast, 360 px overflow, ARIA (defects found in R12) | new (pre-existing) |

**Merge order and known conflicts:**
- Merge order: #4 → #6; #8 → #15; #9 → #10.
- Expected small conflicts, all resolved in the local integration branch:
  - `tests/PersonalFinance.AdminMvc.UiTests/Program.cs`: adjacent check registrations from #5/#11/#16; keep all.
  - `android/.../MainActivity.kt` and `FinanceRepositories.kt`: #12/#8 vs #14; keep all constructor arguments.
  - `android/.../res/values/strings.xml`: #7 vs #15; keep both blocks.

## Finding resolution (source + evidence)

| ID | Resolution | Evidence |
| --- | --- | --- |
| H1 | API has no Hangfire; worker hosts processing | HangfireTests 20/20; runtime A/B |
| H2 | Android registration | device: register → MVC sign-in; duplicate → AlreadyRegistered |
| H3 | Mark-read API + both inboxes | MVC→API (Android view) and Android→MVC read state, runtime |
| H4 | Android delete | device delete → MVC no longer lists |
| H5 | Portable runtime evidence established | this record; see incomplete rows |
| H6 | MVC accepts 201 | reproduced (400 "unavailable") and fixed (302) at runtime |
| M1 | C4 summaries consumed | worker snapshot visible in MVC Reports |
| M2 | Android uses server report | Android = API = MVC to the cent (income, expenses, net, savings, budgets) |
| M3 | C1 version token | stale MVC form 409 after Android edit; no lost update |
| M4 | Durable identities | real outage → new process → same ClientId → one contribution |
| M5 | Offline scope kept (D11) | — (decision) |
| M6 | Budgets deletable after alerts (D5) | FinanceTests |
| M7 | Worker DI without `ICurrentUser` | ValidateOnBuild test |
| M8 | In-process MVC sessions kept (D12) | — (decision) |
| M9 | Deferred, optional (all-time default matches Android; 100k cap) | — |
| M10 | Admin scope unchanged pending D6 | admin pages verified |
| L1 | C2 mismatch → 409 | runtime 201/200/409 |
| L2 | Manila dates | regression check |
| L3 | Typed goals/bills UI | unit tests only |
| L4 | CI on `dev` + real SQL job | hosted `sql` job green |
| L5/L6 | Competing jobs and `DevJobsController` removed | tests + 404 at runtime |

## Academic requirements and parity checklist (roadmap §5)

| # | Requirement | Status | Evidence / gap |
| --- | --- | --- | --- |
| 1 | Register, login, refresh, logout, profile, password | **Incomplete** | Registration both directions and login PASS (runtime). Profile/password change NOT TESTED at runtime (offline suites pass) |
| 2 | Transactions CRUD with FX snapshot | PASS | Cross-client create/edit/conflict/delete; USD expense stored the live Frankfurter snapshot (rate, date, base amount). SQL concurrent PUT race NOT TESTED (conflict proven) |
| 3 | Member categories + admin defaults | PASS | API/MVC; system default 201 admin / 403 member |
| 4 | Budgets + threshold alerts | PASS | Worker alert visible in MVC and via API |
| 5 | Savings goals + contributions | **Incomplete** | Durable retry PASS on device; MVC shows goal. Android goals UI (R9) not rendered on a device |
| 6 | Recurring bills + payments | **Incomplete** | SQL: concurrent payments for one occurrence → one receipt. Worker reminders PASS. MVC/Android bill flows NOT TESTED at runtime |
| 7 | Monthly report identical across clients | PASS | Same cent on all values |
| 8 | Notifications with shared read state | PASS | Both directions at runtime |
| 9 | Stored monthly summaries readable | **Incomplete** | MVC PASS. Android snapshot section not rendered on a device |
| 10 | Currency preferences + Frankfurter, 503 handling | **Incomplete** | Live quote/snapshot PASS. Unavailable-rate handling offline tests only |
| 11 | Android offline create, pending/confirmed, no duplicates | **Incomplete** | Unit tests only; airplane-mode device run NOT TESTED |
| 12 | Separate worker, API processor-free, Manila schedules, retry/restart safety | PASS | Wall-clock firing and operational cutover NOT TESTED (cutover not approved) |
| 13 | Admin features preserved; portable provisioning | **Incomplete** | Admin pages and role checks PASS. Interactive provisioning run NOT TESTED. D6 pending |
| 14 | Responsive, accessible, consistent branding | **Incomplete** | MVC: 12 pages × 3 widths × 2 themes, 0 overflow, 0 axe A/AA violations (after #17). Android: 186/190 instrumented UI tests pass; the 4 failures also fail (3) or flake (1) on unmodified `dev`. Screen reader/TalkBack NOT TESTED |
| 15 | Ownership isolation | PASS | SQL harness (real SQL) + offline suites |
| 16 | Android release endpoint configurable | PASS | Build-time property; invalid value fails the build |
| 16a | Live deployment | **BLOCKED** | D10: host, certificates and secrets not supplied |
| 17 | MVC Manila dates / dashboard paging | PASS / deferred | L2 PASS; M9 optional |

## Automated suites (combined branch)

.NET: Finance 440, Authentication 30, ApiSecurity 34, CategoryUniqueness 15, SchemaValidation 65,
AdminProvisioning 40, MVC UiTests (exit 0), SqlIntegration 13 live, Hangfire 20 live — all PASS.
Android: 626 debug / 632 release unit tests PASS. Instrumented: 190 run, 186 PASS, 4 pre-existing failures,
10 skipped (opt-in runtime steps).

## Remaining work and blockers

- **Review:** independent review of PRs #4–#17 is pending. No PR may merge until then.
- **Owner decisions:** D6 (administrator cross-user scope) and D10 (live deployment host and credentials).
- **NOT TESTED:**
  - Android Compose UI for the new screens on a device (inbox, snapshots, goals/bills, delete).
  - Android offline airplane-mode flow.
  - Profile and password changes at runtime.
  - Bill flows from both clients at runtime.
  - Screen readers.
  - SQL-level concurrent PUT race.
  - Wall-clock job firing.
  - Operational `OWLI_Hangfire` cutover.
  - Signed release APK and live deployment.
- **Pre-existing Android UI test failures (4):** recorded for follow-up; not caused by Phase 3.
