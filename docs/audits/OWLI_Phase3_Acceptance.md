# OWLI Phase 3–4 — Implementation, integration and acceptance record

> Status: 27 November 2026 (Asia/Manila). Evidence labels: PASS / FAIL / BLOCKED / NOT TESTED.
> An essential row that is not PASS remains **incomplete**. Nothing is merged into `main`.

## Commits

| Item | Commit |
| --- | --- |
| `dev` head (PRs #4–#11 merged) | `0cb05e7` |
| Combined review candidate (draft PR #19, **do not merge**) | `integration/phase3-candidate` at `4c74878` |
| Independent-review gate | **Pending** for #12–#24. The owner chose to hold further merges for review |

The candidate is `dev` plus the open PRs, joined only by merge commits (no duplicated implementation commits).
Every result below was produced on the candidate unless stated otherwise.

## Pull requests

| PR | Content | State |
| --- | --- | --- |
| #4–#11 | R0, R2, R3a, R3b, R6, R7, R8 (MVC), R10 | merged in `dev` |
| #12 | R4+R5 transaction contract (server + both clients) + SQL Server concurrency tests | open |
| #13 | R11 Android release endpoint | open |
| #14 | R8 Android inbox, snapshots, server report | open |
| #15 | R9 goals/bills UI (+ Phase 4 message fix) | open |
| #16 | Auth rate limit per user + MVC session-check reuse | open |
| #17 | MVC WCAG AA contrast, 360 px overflow, ARIA | open |
| #18 | This record | open |
| #19 | Combined candidate (draft, do not merge) | review vehicle |
| #20 | Root-cause fixes for the 4 failing Android instrumented tests | open (Phase 4) |
| #21 | API: contribution dates on the Philippine calendar | open (Phase 4) |
| #22 | MVC: Record payment only for a due bill occurrence | open (Phase 4) |
| #23 | Android: offline banner category, stale profile name, misleading error, lost separators | open (Phase 4) |
| #24 | Opt-in Android device runtime tests (cross-client steps + device UI flows) | open (stacked on #19) |

**Integration order** (each step simulated on current `dev` with the real PR branches; every step merges cleanly):
#12 → #13 → #14 → #15 → #16 → #17 → #20 → #21 → #22 → #23 → #18, then #24 last. Do not merge #19.

- **#14:** carries a merge of #12 (`649d178`) that resolves `MainActivity.kt`/`FinanceRepositories.kt`. Its diff also shows #12 until #12 merges.
- **#16:** `dev` merged (`a67509a`); the UiTests registration conflict is resolved by keeping all registrations.
- **#15, #12:** already contain `dev`. The others need no change.
- **#24:** it was cut from an early candidate commit. After its dependencies merge, merge `dev` into it, keeping `dev` for every file except its two runtime test files. Simulated: its diff against `dev` is then only `CrossClientRuntimeTest.kt` (+79/−4) and `DeviceUiRuntimeTest.kt` (+360).
- **Equivalence with the tested candidate (`4c74878`):** the simulated merge of #12–#24 differs only in the order of four UiTests registrations, the position of the registration string block (the same 823 strings and 3 plurals with identical values), and stale duplicate wiki-log lines that only the candidate had. No other file differs, so no functionality is lost or duplicated.

## Defects found and fixed in Phase 4

| Defect | Found by | Fix |
| --- | --- | --- |
| Contributions dated "today" rejected 00:00–08:00 Manila (API used the UTC date; both clients default to the Manila date) | browser + device runs at 07:00 Manila | #21 (Philippine calendar, the same rule as bill payments) |
| MVC offered Record payment for future bills and then showed a misleading "conflict" message | browser run | #22 |
| Android offline banner showed "#8" instead of the category | device UI run | #23 |
| Android Account kept the old name after "Profile saved." | device UI run | #23 |
| Android plan write rejection said "The current password may be incorrect" | device UI run | #23, #15 |
| Lost "·" separators in offline history ("Offline ? On-device history") | device UI run | #23 |
| 4 failing Android instrumented tests: keyboard covering the category button, non-unique text matches, a pre-FX label | investigation (none was a product defect) | #20 |

The Phase 3 R12 defects (shared MVC auth rate limit; WCAG/overflow) are fixed in #16 and #17.

## Regression results on the candidate (`4c74878`)

| Suite | Result |
| --- | --- |
| .NET solution build | PASS |
| FinanceTests | 440/440 |
| AuthenticationTests | 30/30 |
| ApiSecurityTests | 34/34 |
| CategoryUniquenessTests | 15/15 |
| SchemaValidation | 65/65 |
| AdminProvisioningTests | 40/40 |
| AdminMvc.UiTests | exit 0 |
| SqlIntegrationTests (real SQL Server; 5 offline + 11 live) | 16/16 |
| HangfireTests (real SQL Server; 7 offline + 6 live) | 13/13 |
| Android unit (debug / release) | 656 / 662, 0 failures |
| Android instrumented, full suite, emulator | 195 run, 0 failures, 15 skipped (opt-in runtime steps) |
| Android device UI runtime test (#24, opt-in) | 5/5 PASS, two consecutive runs |
| Android cross-client runtime steps (opt-in) | PASS, see rows 1, 5, 7, 8 and 11 |
| Hosted CI (#12, #18, #20, #21; dotnet + android + sql) | green |

## Runtime acceptance evidence

Environment: local SQL Server container, disposable `OWLI_Runtime_20261126` and `OWLI_HangfireTests_runtime20261126`;
API and MVC from the candidate; Android 16 emulator using the debug build against the same API; synthetic accounts only.
`PersonalFinanceDb` was never connected to. Operational `OWLI_Hangfire` was read only.

| # | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Register, login, renewal, logout, profile, password | **PASS** | Registration both ways (MVC form ↔ Android). API refresh rotates and a replayed token gets 401; logout revokes the refresh token. MVC profile rename is visible through Android's contract. Android profile save shows the new name after #23. MVC password change revokes old tokens (security stamp) and sign-in with the new password works. MVC and Android logout return to sign-in |
| 2 | Transactions CRUD + FX snapshot | **PASS** | Cross-client create/edit/conflict/delete. USD expense with a live Frankfurter snapshot. SQL Server: concurrent same-version edits always produce one 200 + one 409 with no lost update (5 rounds); edit-vs-delete race and a rejected locked write leave no partial change |
| 3 | Member categories + admin defaults | **PASS** | API/MVC; system default 201 for admin / 403 for member |
| 4 | Budgets + threshold alerts | **PASS** | Worker alert visible in MVC and Android |
| 5 | Savings goals + contributions | **PASS** | Android UI: goal, contribution with the default date (07:22 Manila), progress updates. MVC browser: goal, contribution with the default date (07:20 Manila, after #21). Durable retry after a real outage gives one contribution |
| 6 | Recurring bills + payments | **PASS** | Android UI: bill, payment, next due date advances. MVC browser: due bill paid (one receipt + one transaction); a future bill shows "Payment opens on …" (#22). SQL: concurrent payments → one receipt |
| 7 | Report identical across clients | **PASS** | Android = API = MVC to the cent, including savings and budgets |
| 8 | Notifications + read state | **PASS** | Both directions |
| 9 | Stored summaries | **PASS (MVC) / partial (Android)** | Worker snapshot in MVC; the Android snapshot section is covered by unit tests only, and its device rendering was not exercised |
| 10 | Currency + Frankfurter | **PASS / partial** | Live quote and snapshot PASS; unavailable-rate (503) handling is offline tests only |
| 11 | Android offline create, pending, sync, no duplicates | **PASS** | Device UI: offline save → "1 waiting to sync" with the category name → reconnect → auto-sync → one confirmed row. A second sync attempt makes no copy. SQL: each of 7 offline expenses stored exactly once |
| 12 | Worker lifecycle, schedules, retry, API isolation | **PASS** | HangfireTests live (retry after forced failure, dedupe, stop/restart, no processing without the worker). API has 0 Hangfire servers. At startup the worker ran the missed 07:00 Manila daily reminder and hourly alert on its own and created a correct "due in 2 days" reminder. Then, with no manual trigger, the hourly schedule created job 9 at **00:00:00.86 UTC (08:00 Manila)** and it succeeded (next run 01:00 UTC). The worker was stopped with SIGTERM and deregistered |
| 13 | Admin provisioning + role-based access | **PASS** | Interactive provisioning (pseudo-terminal) on `OWLI_Runtime_*`: catalog-specific confirmation, User + Administrator roles, admin pages 200; a rerun for the same email is refused. Members are redirected to Access Denied. D6 (cross-user scope) is still an owner decision |
| 14 | Responsive, accessible, keyboard, screen reader, TalkBack | **Incomplete** | MVC: 12 pages × 3 widths × 2 themes, 0 overflow, 0 axe A/AA violations (#17). Keyboard-only sign-in; every control reachable by Tab with visible focus. Android: every clickable node on 8 screens has an accessible name. **NOT TESTED:** a real screen reader (VoiceOver/NVDA) and TalkBack spoken navigation |
| 15 | Ownership isolation | **PASS** | SQL harness + API/MVC role checks |
| 16 | Android release endpoint | **PASS** | Build-time property; an invalid value fails the build |
| 16a | Production deployment | **Out of scope (deployment team)** | D10: the external deployment team deploys through its existing Cloudflare setup using [the handoff guide](../../deployment/README.md). Not verified by this team; production status is reported separately |
| 17 | MVC Manila dates | PASS | M9 paging remains optional |

## Phase 5: release readiness and deployment handoff

Two separate statuses:

| Status | Result |
| --- | --- |
| Local release readiness | Candidate verified (sections above). Publish of API, MVC and worker is verified; no forbidden files in the output; the published worker starts disabled (exit 0). The secrets scan of `dev` and the candidate found no passwords, tokens, keys or private configuration. **Final `dev` commit: recorded after the reviewed merges** |
| Production deployment | **Not performed by this team.** Owned by the external deployment team (D10); it is not claimed as verified here |

Handoff: [deployment/README.md](../../deployment/README.md) covers software, pulling `dev`, configuration, backup and migration rehearsal, publish, API/MVC startup, worker supervision, `OWLI_Hangfire` preparation and controlled activation, Cloudflare, Android signing, verification and rollback (PR #25).

## Remaining blockers

- Independent review of #12–#24, then merges in the order above and a re-run of these suites on the merged `dev` head.
- D6 owner decision (administrator cross-user scope).
- Production deployment belongs to the external deployment team (D10). Local release readiness does not imply production readiness, and production runtime verification is NOT TESTED by this team.
- NOT TESTED: real screen reader and TalkBack navigation; Android snapshot section rendering on a device; FX-unavailable handling at runtime.
