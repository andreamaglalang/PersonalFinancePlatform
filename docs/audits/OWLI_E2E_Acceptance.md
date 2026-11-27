# OWLI — End-to-end functional and user acceptance testing (27 November 2026)

- **Code under test:** branch `test/e2e-acceptance`, stacked on `fix/system-wide-validation-feedback` (PR #28), which
  is `dev` `be10ff1` plus the validation work.
- **Environment:**
  - API, MVC and worker run from this build on a local SQL Server container.
  - Databases are disposable: `OWLI_Runtime_20261126` for finance data and `OWLI_HangfireTests_runtime20261126` for jobs.
  - Chrome via Puppeteer for MVC; an Android 16 emulator for the debug app.
  - All accounts are synthetic (`@example.test`).
- **Not touched:** production, Cloudflare, operational `OWLI_Hangfire` and `PersonalFinanceDb`.
- **Evidence labels:** PASS / FAIL / BLOCKED / NOT TESTED. Earlier evidence is reused only where the code has not
  changed since (cited).

## Totals

| Measure | Count |
| --- | --- |
| Interactive elements inventoried (instances, not distinct controls) | **926**. MVC: 780 on 37 pages (member: 29 pages, 544 elements; administrator: 8 pages, 236). Android: 146 clickable nodes on 8 screens |
| MVC inventory checks on all 780 | Every page returns 200; 0 broken internal links; 0 unnamed controls; 0 POST forms without antiforgery; 1 console error per page (missing icon, fixed) |
| Functional scenarios executed through the real UI (round 1) | **151**: MVC member 41, MVC administrator + reliability + worker 16, MVC authentication 3, MVC validation 24, MVC session/outage 3, Android device steps 13, cross-client gate 14, rendered layout/axe 36 page-width-theme cases (counted as 36) |
| Passed after fixes | 151 |
| Failed (open) | 0 |
| Defects found and fixed in this round | 7 (E1–E7) plus 17 in PR #28 |
| Not tested | See "Limitations" |

Element classes in the MVC inventory:
- 431 links and 226 buttons;
- 36 radio buttons, 17 selects, 14 disclosure toggles, 8 checkboxes;
- 18 text, 8 number, 7 date, 7 month, 3 password, 2 search, 2 colour and 1 email input.

Data-dependent screens are exercised by the functional scenarios below:
- transaction edit and delete;
- bill pay, edit and delete;
- contribution edit and delete;
- notification actions.

## Defects found and fixed

| # | Platform · feature | Symptom (reproduced) | Root cause | Fix | Regression test | Interface re-check |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | MVC · savings goal contributions | Every **Edit** and **Delete** contribution link returned a bare 404 (blank browser error page); contributions could not be corrected on the website. Present since the handoff | Actions bound `contributionId` `[FromRoute]`, but the member route has only `{id}`; the links put it in the query string | Bind `[FromQuery]` (the URL the links already generate) | `E2eRegressionChecks` | PASS (edit 100→150, delete) |
| E2 | MVC · recurring bills | **Save bill** on an existing bill stayed on the form with no message; edits never saved. Present since the handoff | The edit form omits the creation-only Frequency and First due date, which stayed `[Required]`, and their messages are not rendered when editing | Ignore those two fields when editing | `E2eRegressionChecks` | PASS ("Bill saved.", name persisted) |
| E3 | Android · rotation | Turning the phone while adding a transaction closed the form and lost the typed amount and description | `MainActivity` was recreated on rotation; app state lives in Compose `remember` and non-activity stores | Activity handles orientation, size, layout, keyboard and dark-mode changes; Compose recomposes | Device step `configurationChangeKeepsSessionAndForm` (real rotation) | PASS (12.34 and description kept) |
| E4 | MVC · budgets, dark theme | "On track · ₱… left" failed WCAG AA contrast (3.19:1); the near-limit state was also below AA | Status text used undefined `--income`/`--expense`/`--owli-gold-deep` tokens, so light-theme fallbacks applied | Themed `--owli-income`, `--warning-text`, `--owli-expense` | `E2eRegressionChecks` (undefined-token guard) | PASS: axe 0 violations, all three states, both themes |
| E5 | MVC · every page | Console error on every page (`/favicon.ico` 404) | No site icon | SVG icon from the existing owl mark, linked from both layouts | `E2eRegressionChecks` | PASS |
| E7 | MVC · Add transaction | With no category of the chosen type (e.g. Income for a new member) the form offered nothing and, on submit, said "Choose a Income category." | No empty-type guidance; message interpolated the type verbatim | Hint "No categories of this type yet. Add a category, then come back." (server-rendered, kept in sync by the existing type script); "Choose an income category." | `E2eRegressionChecks`, `MemberActivityWriteChecks` | PASS (shown for Income, hidden for Expense) |
| E6 | Android · password sentence (also in PR #28) | "characters,an uppercase letter" | Android trims spaces in resource strings | Quoted separator | device step | PASS |

## Coverage and results by area

| Platform | Feature / elements | Scenarios | Expected | Result |
| --- | --- | --- | --- | --- |
| MVC member | Registration, sign-in, every navigation destination (15) | Register → sign in; open each page | 200, correct page | PASS |
| MVC member | Empty states | New account: Activity, Goals | Explains what to do next | PASS |
| MVC member | Categories: New, Edit, Delete, Type filter | Create; rename; delete unused; delete in-use | Persisted via API; in-use refused: "…referenced by financial records… Deactivate it instead." | PASS |
| MVC member | Transactions: Add (expense/income), Edit, Delete + confirm, Search, period | Create 250; reload after save; edit to 300; income; delete; search | One record (no resubmission on reload/Back/Forward); API values match | PASS |
| MVC member | Budgets: Create, Edit, Delete, status | Over limit (300 of 200); duplicate; edit 500; delete | Status shown; duplicate: "A budget already exists for this category and month." | PASS |
| MVC member | Goals: Create, Contribute, Details, Edit/Delete contribution, Edit, Cancel, Delete | Full lifecycle; delete with history; delete empty goal | Contribution recorded once; history protected: "…cannot be deleted. Cancel it instead."; cancel works | PASS (after E1) |
| MVC member | Bills: Create, Record payment, Edit, Delete | Pay due bill; next occurrence early; edit; delete with history | One transaction, due date 27 Nov → 27 Dec; early payment: "can be paid from its due date, December 27, 2026"; history protected | PASS (after E2) |
| MVC member | Currency converter, preferences | USD→PHP quote; save preferences | Converted amount with rate date; "Currency preferences saved" | PASS |
| MVC member | Insights | Month totals vs `GET /api/reports/monthly` | Equal to the cent | PASS |
| MVC member | Notifications: Mark read, Mark all, filter | Worker-produced alert; Mark read | Read state persisted through the API (seen by both clients) | PASS |
| MVC member | Account: Profile, Appearance, Password, Logout | Rename; logout; protected page after logout; sign in again | Persisted; redirected to sign-in | PASS |
| MVC member | Security | Member opens `/Dashboard`; anonymous opens `/Member/*` | Access denied / sign-in | PASS |
| MVC member | Reliability | Triple-click Save; Back, Forward, reload after saving | Exactly one transaction | PASS |
| MVC member | Session and outage | Site restart (real session loss); API stopped | "Your session has expired. Please sign in again."; specific outage text, no false success | PASS |
| MVC authentication | Show/Hide password; wrong password vs unknown email; lockout after 5 failures | Toggle; compare messages; correct password while locked | aria-pressed updates; identical safe message; locked account refused | PASS |
| MVC validation | All PR #28 browser scenarios | See `OWLI_Validation_Feedback.md` | Specific messages | PASS |
| MVC administrator | Dashboard, Categories, Budgets, Reports, Account (7 pages) | Open each | 200 | PASS |
| MVC administrator | Shared categories: Create, Edit, Deactivate dialog (Cancel / Deactivate and save), duplicate | Full flow | Dialog protects deactivation; Cancel keeps it active; confirm deactivates; duplicate reason shown | PASS |
| MVC administrator | Reports month filter; Logout | — | Works | PASS |
| MVC rendered | 12 pages × 360/768/1280 × light/dark | Overflow + axe WCAG 2 A/AA | 0 overflow, 0 violations | PASS (after E4) |
| Android | Offline expense → pending banner (category name) → reconnect → auto-sync → one row; second sync attempt | Device step | No duplicate, nothing lost | PASS |
| Android | Goal + contribution; bill + payment, next due date | Device steps | Progress ₱25.00 of ₱1,000.00; due 27 Nov → 27 Dec | PASS |
| Android | Profile save, logout, sign in | Device step | New name shown; logout returns to sign-in | PASS |
| Android | Registration and change-password validation; plan editor | Device steps | Specific messages (PR #28) | PASS |
| Android | Rotation with a half-filled form | Real rotation | Form and session kept | PASS (after E3) |
| Android | System Back on a filled form | Device step | "Discard this transaction?" asked; nothing saved | PASS |
| Android | Account switching | Sign out, sign in as another member | No data from the previous account | PASS |
| Android | Accessibility labels: Home, Activity, picker, form, Goals, Bills, Insights, Account | Device step, default and dark theme + 130 % font | Every clickable node named; layout intact | PASS |
| Cross-client | Android create → MVC list → MVC edit → Android edit → stale MVC form → Android delete | Gate | 409 for the stale form; no lost update; deletes reflected | 14/14 PASS |
| Worker | Budget threshold alert; re-run | Trigger the hourly job on test storage | One alert "E2E Dining budget reached 50%: PHP 80.00 of PHP 100.00."; re-run makes no duplicate; visible on MVC | PASS |

## Evidence reused (code unchanged since)

From `OWLI_Phase3_Acceptance.md`:
- registration both ways across clients;
- refresh rotation and replay rejection;
- reports identical on Android, API and MVC;
- notification read state in both directions;
- durable contribution retry after a real outage;
- scheduled Hangfire run and worker lifecycle;
- administrator provisioning.

From the Phase 5 closeout: the FX-provider outage on all clients, and the Android stored summary.

From Phase 3 R12: MVC rate limiting per user and per IP (429 message).

## Regression after the fixes

| Suite | Result |
| --- | --- |
| .NET: Finance 441, Authentication 34, ApiSecurity 35, CategoryUniqueness 15, SchemaValidation 65, AdminProvisioning 40, MVC UiTests (with `E2eRegressionChecks`) | PASS |
| SqlIntegration 16/16, Hangfire 13/13 (real SQL Server) | PASS |
| Android unit (debug / release) | 672 / 678, 0 failures |
| Android instrumented, full suite (with the manifest change) | 202 run, 0 failures, 22 skipped (opt-in device steps, run separately: 13/13 PASS) |

## Limitations (not represented as passed)

- **NOT TESTED:**
  - spoken TalkBack output; a desktop screen reader (VoiceOver/NVDA);
  - slow-API latency (only total outages were simulated);
  - force-closing the app during an in-flight write on this build (covered earlier by the durable-retry evidence);
  - installing over a previous APK on a device. Room migration v2→v3 is covered by instrumented migration tests, not by a manual reinstall;
  - physical devices and tablet-size emulators;
  - a release-build APK against a public endpoint (deployment team).
- **Design notes, not defects:**
  - Bill reminder days have no default; the field is required and explained.
  - New members start with no categories until an administrator adds shared defaults; the add form now explains
    this (E7).
- **D6** (administrator cross-user scope) remains unresolved; the administrator scope was not changed.
- **Production:** deployment and production verification belong to the external deployment team.

## Round 2 — reviewer findings (27 November 2026)

The 926 inventoried elements are **not** 926 tested elements; see "Exercised versus inventoried" below.

### Financial integrity (reproduced first, then fixed)

How it was reproduced: real MVC forms played twice or late, while a second client (standing in for Android) changed
the same records through the API. Results were checked in SQL Server. Script: `integrity.sh` (local evidence).

| # | Finding | Reproduced before the fix | Root cause | Fix | After the fix |
| --- | --- | --- | --- | --- | --- |
| F1 | MVC contribution: a fresh ClientId on every POST | Same form submitted twice → **2** contributions; posted 3× in parallel → **3** | ClientId minted in the POST action | ClientId minted when the form opens and posted back (hidden field); a form without it is refused as expired | 1 contribution (twice, 3× parallel, and after a real 10 s timeout followed by a resubmit); two separately opened forms → 2 (intended) |
| F2 | MVC bill payment: re-read `NextDueDate` and minted a ClientId at submit | Same form twice → **2** payments. A stale form submitted after Android paid that occurrence **paid the next occurrence** | Occurrence and identity taken at POST time | The form carries the confirmed due date and a ClientId minted when it opened; POST pays exactly that occurrence | Same form twice → 1. Stale form → nothing paid: "This due date was already paid or is not the next one due… Nothing was paid." Three overdue occurrences paid oldest first, one transaction each |
| F3 | MVC goals: row version re-read just before the write | Stale goal edit **overwrote** Android's rename; stale contribution edit overwrote, stale delete **deleted**, stale cancel **cancelled** after Android's change | Each write fetched the current version | Edit, cancel, delete, contribution edit and contribution delete post the version the page was opened with | All four refused: "This goal or contribution was updated elsewhere… Nothing was changed." Android's values kept |
| F4 | MVC goal client treated the API's 412 (stale version) as an unknown outcome | Stale cancel showed "could not confirm" | 412 not in the client's definitive statuses | 412 recognised and mapped to the message above | Correct message |

Regression tests:
- `E2eRegressionChecks` F1–F3 (identities and versions come from the form, never re-read at submit);
- the updated `MemberGoalBillChecks`;
- allowlist entries for the API's payment reasons on both clients, with the drift test.

### Handoff items

| Item | Result |
| --- | --- |
| Finance database name in templates | **Fixed.** `api.env.template` said `OWLI` and the worker said `PersonalFinanceDb`; both now say `PersonalFinanceDb`, each noting it must match the other |
| Room 1 → 3 and 2 → 3 | **PASS.** Instrumented tests: v1 (pending, rejected, synced transactions) and v2 (transactions + cached categories), with every column preserved. The v2 test is new |
| Real in-place APK upgrade | **PASS.** The handoff APK (`69626b3`, Room v2) was installed and signed in, and cached the account's data. A pending offline expense was added. The candidate was then installed over it with `adb install -r`, same debug certificate (SHA-256 `5e04b462…`), install time unchanged. After the upgrade: still signed in, Room v3, 6 cached categories kept, "1 waiting to sync" kept with the category name. On reconnect the expense synced **once** (one server row, local row Synced) |
| Release versionCode/versionName/signing | **Defined.** `-PowliVersionCode` / `-PowliVersionName` with validation (verified: 3 / 1.0.0; invalid code rejected). Guide §11 sets the rules: higher code every update; one release keystore held by the deployment team; debug→release needs a reinstall, so sync first. No signing credentials created |
| Migration rehearsal instructions | **Defect fixed in the guide.** Rehearsed: `dotnet ef migrations script` output (idempotent **and** plain) **fails** under `sqlcmd` (`EXEC(CONCAT…)`; columns referenced before they exist). Guide §5 now uses an EF migration bundle, verified from migration 3 → 5 and as a no-op re-run. Backup with checksum, `RESTORE VERIFYONLY` and restore under a new name were verified. A populated pre-migration-4 upgrade is **NOT TESTED** (needs production data; guards protect it) |
| Hangfire cutover guidance | Reviewed. §9 adds inspection queries, ordering after API/MVC/migrations, the expected catch-up runs, and "one supervised worker" |
| Historical vs current docs | The guide and `docs/README.md` state that only the guide, publish script and templates are current; Phase/Stage reports, `docs/handoff/`, `deployment/VALIDATION.md` and the original-PC notes are historical |

### Functional checks

| Check | Method | Result |
| --- | --- | --- |
| Slow API, Android, with Save tapped 3× | TCP fault proxy delays the HTTP response (~6 s, under the 10 s timeout) | **PASS:** one transaction, "Your account confirmed this transaction." |
| Response lost after a real write, Android | Proxy forwards the request and drops the response | **PASS:** the app queued it ("Saved on this device"). On reconnect it synced with the same ClientId: one server row, banner cleared |
| Process death with a pending contribution, Android | Response dropped, then `am force-stop`, network restored, app reopened | **PASS:** "A contribution's outcome is unknown… pending" survived the restart; "Check same contribution" → "Contribution saved."; one server contribution (progress ₱69.44) |
| Slow API beyond MVC's 10 s timeout | MVC routed through the proxy, 12 s delay | **PASS:** "The API could not confirm the change…" (no false success); API saved once; resubmitting the same form → still one |
| Repeated Pay / Contribute / Save | MVC: same form twice, 3× parallel, triple-click Save; Android: triple tap | **PASS** (F1, F2, round 1 reliability, slow-API Android) |
| Month-end, leap-year, Philippine-midnight boundaries | Existing deterministic-clock tests, unchanged code: `BillPaymentTests` (31 Jan → 28 Feb → 31 Mar anchoring; 15:59:59Z/16:00:00Z Philippine boundary; month rollover during conversion; next-day retry); `SavingsGoalTests` (contribution UTC+8 boundary); `DashboardSummaryCalculatorTest`, `ReportPeriodTest`, `ActivityParityChecks` (leap day) | **PASS** (suites green this round); no device clock manipulation |

### Exercised versus inventoried controls

MVC, distinct controls (one entry per unique control per page; repeated list rows counted once):

| Area | Distinct controls | Exercised | Not exercised |
| --- | --- | --- | --- |
| Member pages | 170 | 158 | Activity "All" and "Expenses" type links (Income tested); Budgets month picker (summary, input, Go); Reports month picker (summary, input, Go); default-currency select; colour on category edit; target date on goal edit; bill currency select |
| Member shell (every page) | 12 | 9 | "Skip to main content" link; shell theme toggle; "Stay signed in" (logout dialog cancel) |
| Administrator (shell + 8 pages) | 91 | 50 | Account-menu Close; category "API page options", page and page-size inputs; Name/Status column sort; 15 of 16 palette colours on create (Teal tested) and all 16 on edit; Income type on create; "Reload current category"; "Activate and save" |
| **MVC total** | **273** | **217** | **56** |

How a control counted as exercised:
- **Links** were followed to a 200 page during the crawl and functional runs.
- **Form controls and buttons** were operated and their effect checked in the page, URL or database. This round's control script covers 24 of them, all PASS. For the administrator palette and budget, the saved values were confirmed in SQL Server (`#238A87`; ₱250.00 at 75%).

Android (146 clickable instances on 8 screens), exercised by device steps:
- sign-in, registration, Home, Activity add/sync/Back-discard, the category picker, add form, currency picker;
- Plan goals/bills editors, contributions, Record payment;
- Account profile/password/logout, Insights stored summary, rotation, dark mode at 130% font.

Not exercised on a device in this round (still covered by instrumented Compose tests):
- Activity search and filters;
- Insights month navigation;
- the currency converter screen;
- category manager edit and reorder;
- notification inbox actions (verified on a device in Phase 4, both directions).

### Regression after round 2

| Suite | Result |
| --- | --- |
| .NET: Finance 441, Authentication 34, ApiSecurity 35, CategoryUniqueness 15, SchemaValidation 65, AdminProvisioning 40, MVC UiTests | PASS |
| SqlIntegration 16/16, Hangfire 13/13 | PASS |
| MVC integrity scenarios | 10/10 PASS |
| Android unit (debug / release) | 672 / 678, 0 failures |
| Android instrumented, full suite (incl. new v2 → v3 migration test) | 203 run, 0 failures, 22 skipped (opt-in device steps) |

## Round 3: final functional regression (27 November 2026)

Started from `dev` `285d6cc` (code identical to `bc652ce`). Local API, MVC and worker rebuilt from that commit;
synthetic accounts only, disposable catalog `OWLI_Runtime_20261126`. Each financial result was checked in the
page, in the API and in SQL Server. Scripts (local evidence): `gap.js` (remaining MVC controls), `journeys.js`
(financial journeys), the uiautomator driver for Android, and curl replays of real MVC forms.

### Defects found and corrected

| # | Defect | How it was found | Root cause | Correction | Verified after the fix |
| --- | --- | --- | --- | --- | --- |
| R3-1 | An over-funded goal showed "-25.50 PHP remaining" (MVC goal details; Android Plan) | Contributed 75.50 to a 50.00 goal | The API's remaining amount is target minus saved (negative past the target; the surplus is a separate field); the details page and Android printed it unchanged | Show "X over target" when there is a surplus, otherwise remaining (never below zero) | MVC: "151.0% complete · 25.50 PHP over target". Android: "₱30.75 over target" |
| R3-2 | **MVC rate review could never save.** With "Review the rate before saving" on, confirming a fresh rate always failed with "The exchange rate changed or the confirmation expired" | USD expense through the real form | The confirmation token's expiry was compared with the expiry of the quote rebuilt at submit (always later) | Check the expiry carried by the token itself; the account, amount, currency, date, rate, rate date and source checks are unchanged | Confirmed USD 10.25 → one row at the quoted rate (63.661, ₱652.53). Expired, other-account and changed-rate confirmations still refused (regression check) |
| R3-3 | **MVC transaction created twice when the same form was resubmitted** | Same Add form posted twice with curl → **2** rows | ClientId minted in the POST action (the F1 pattern, not yet fixed for transactions) | ClientId minted when the form opens (hidden field); a form without it is refused as expired. The API's replay answer (200 with the original row) now counts as saved instead of "could not confirm" | Same form ×3 → **1** row, each answer "saved". Same form with a changed amount → 409 "already recorded with different details". A separately opened form → its own row |
| R3-4 | MVC validation showed framework wording: "The Name field is required.", "The field Type must match the regular expression '^(Income\|Expense)$'." | Tampered and whitespace-only inputs on the administrator and member category forms | Data-annotation attributes without messages | Plain messages on category, transaction type, bill frequency, currency preference and contribution note rules | "Enter a category name.", "Choose Income or Expense.", "Choose a valid color." |
| R3-5 | **Android Goals tab failed to load** ("Savings goals couldn't be loaded") once any goal was over-funded | Device run with an over-funded goal | Both goal parsers required every amount, including the signed remaining amount, to be zero or more | Remaining amount parsed signed, and must equal target minus saved (stricter than before) | Goals tab lists the goal; regression tests fail on the old code and pass on the fix |
| R3-6 | Android Add transaction: an amount such as 10.555 disabled Save with no reason | Device run | The error appeared only after the field lost focus; Save, which would also show it, is disabled | Show the specific reason as soon as the amount cannot become valid ("0" while typing "0.5" still waits until the field is left) | "Amounts can have no more than two decimal places." shown under the field |

Regression tests: `E2eRegressionChecks` (R3-1 to R3-4, including a real issue → confirm cycle of the rate token and the
replay status), `ActivityParityChecks` (confirmation saves once; a form without ClientId sends nothing),
`MemberCurrencyChecks` (the rendered Add form carries its ClientId), Android `LiveAccountPlanRepositoryTest`,
`GoalsBillsModelsTest` and `AddTransactionViewModelTest`. R3-2, R3-3 and R3-5 checks were run against the old code first
and failed.

### Remaining MVC controls: all 56 exercised

| Area | Controls | Result |
| --- | --- | --- |
| Member pages (12) | Activity "All" and "Expenses"; Budgets month picker (summary, input, Go); Reports month picker (summary, input, Go; API expense 20.00 for that month); default currency select (persists, preselects Add); colour on category edit (`#aa3355` in API); goal target date on edit (29 Feb 2028); bill currency select (USD) | PASS |
| Member shell (3) | Skip to main content (first Tab, moves to `#main`); theme toggle (switches, persists across pages); logout dialog "Stay signed in" (still signed in) | PASS |
| Administrator (41) | Form dialog Close; account menu Escape; every palette colour and No color on create (15/15 saved exactly) and on edit (16/16); Income type on create; "Reload current category" discards typing; Activate dialog Cancel keeps inactive, "Activate and save" activates; API page options (summary, page 2, page size 3); Name sort (39 names descending) and Status sort (active first) | PASS |
| **MVC total** | **273 distinct controls, 273 exercised** | |

### Financial journeys (MVC, verified in page, API and SQL)

| Journey | Result |
| --- | --- |
| USD expense with rate review: quote, nothing stored before confirmation, one row after, exact base amount | PASS (after R3-2) |
| Editing only the description keeps the historical rate snapshot (rate and base unchanged) | PASS |
| Unsupported currency → 400 with a specific reason, nothing stored | PASS |
| Budget spent/remaining/status after create (750, OK), edit (850, Warning), edit (1100, Exceeded), delete (0, OK) = SQL sums, shown on Plan | PASS |
| Contribution to target → Completed; deleting it → Active, saved 0 | PASS |
| Over-contribution → surplus 25.50 shown | PASS (after R3-1) |
| USD bill paid through MVC → one transaction, 7.99 USD × 63.661 = ₱508.65, next due one month later | PASS |
| Cross-account: member B on member A's edit, details, contribute, pay, category and delete pages sees "no longer available" or 404 and none of A's data; API 404 for read, update, pay, delete; A's data intact | PASS |
| Inbox: 25 notifications → page 1 and Next/Previous; Mark read; another member marking it → 404; Unread only; Mark all → 0 unread; empty account → "You have no notifications yet." | PASS |
| Lockout: 5 wrong passwords, then the right one → the same generic message; unknown account → same message; API 401 | PASS |
| Invalid category type (API and tampered form) → refused, nothing stored | PASS (wording fixed by R3-4) |
| Deactivated personal category hidden from Add; history keeps its name | PASS |
| Insights after every mutation = API = SQL | PASS |

### Android, on a device against the real API

| Area | Checked | Result |
| --- | --- | --- |
| Sign-in | Password Show/Hide; Home all-time totals equal the seeded data (income ₱49,000.00, expenses ₱1,645.49, net +₱47,354.51) | PASS |
| Activity search and filters | Search "jeepney" (1 result, totals re-filtered); Clear search; Previous month (October, 2 rows); All time (7); Income segment (2); Filters sheet: Food + Oldest first (2 rows, oldest first); remove chip; Clear all | PASS |
| Insights month navigation | Previous ×3 (September −₱999.99; August empty state), This month; Next is disabled in the current month | PASS |
| Currency converter | Preset $500 → ₱31,830.50 at 63.661 (equals API quote); Swap; picker to EUR → 0.01431 (equals API) | PASS |
| Category manager | Rename "Coffee" → "Cafe drinks" and colour Teal (both in the API); Reorder Food to the top; order kept after reopening (device-local, as the screen states) | PASS |
| Inbox actions | Mark read (SQL updated); Unread filter; read on MVC → disappears from Android's unread list after Refresh; Mark all as read → 0 unread in SQL | PASS |
| Over-funded goal | Goals tab loads; "₱30.75 over target" | PASS (after R3-5, R3-1) |
| Amount validation | 10.555 → specific message | PASS (after R3-6) |
| API restart | API stopped: MVC shows "…could not verify your session. Try opening the page again shortly." (503); Android adds from cached categories and saves "Saved on this device". API restarted: synced **once** (one SQL row) | PASS, see limitation |

### Limitations recorded in round 3 (not represented as passed)

- ~~No periodic retry after an API-only outage~~ and ~~Android capped the goal percentage at 100%~~: both corrected in
  the round-3 follow-up below.
- A cold category cache (an account that never opened the picker online) cannot offer categories while the API is down.
  This is the documented design.
- The administrator test palette created extra shared categories in the disposable catalog; they appear in every
  synthetic member's lists there. Not production data.
- Still NOT TESTED: spoken TalkBack/VoiceOver output, physical devices and tablets, upgrade with the release key,
  a populated pre-migration-4 database, production.

### Round 3 follow-up: reviewer items (27 November 2026)

| Item | Correction | Verified |
| --- | --- | --- |
| Automatic sync after an API-only outage | While pending (not rejected) entries exist and the app is in the foreground, Android retries with bounded backoff: 10 s, doubling to a 5-minute cap. It uses the same single-flight sync (never two at once), the stored ClientIds and the signed-in account only; nothing runs when the queue is empty, and a request in flight is not cancelled when the app leaves the foreground | API stopped with the device network unchanged → expense "Saved on this device". API restarted at 20:56:12; **no tap**; synced at 20:57:26 (within the backoff schedule), **one** server row, banner cleared. The active network stayed the same throughout, so no network event was involved. Unit test: backoff schedule and cap |
| Goal progress parity | Android and the MVC goals list show the real percentage; only the progress bars stay at 100%. Saved, surplus and status unchanged | Same goal: Android "115.4% · ₱30.75 over target"; MVC list "30.75 PHP over target · 115.4%"; MVC details "115.4% complete · 30.75 PHP over target"; MVC bar `aria-valuenow="100"`. Unit tests: text 115.4, bar fraction 1.0 |
| Expired transaction form | The posted ClientId (empty or malformed) and its binding error are removed from ModelState, so the new identity renders | Real form posted with a missing, empty and malformed ClientId: each returned "This form has expired. Check the details and save again." with a **new ClientId in the HTML**; that returned form submitted twice → **1** row each. Regression check fails without the fix |
| Contribution and payment forms | Contributions now treat a missing or malformed ClientId as expired **before** other validation (a malformed value used to re-render the same unusable form). Payments already redirected | Missing, empty, malformed → "This form has expired. Nothing was saved/paid…", nothing stored; reopened form submitted twice → 1 contribution, 1 payment |

### Regression after round 3

| Suite | Result |
| --- | --- |
| .NET: Finance 441, Authentication 34, ApiSecurity 35, CategoryUniqueness 15, SchemaValidation 65, AdminProvisioning 40, MVC UiTests (with R3 checks) | PASS |
| SqlIntegration 16/16, Hangfire 13/13 (real SQL Server) | PASS |
| Android unit (debug / release) | 675 / 681, 0 failures |
| Android instrumented, full suite | 203 run, 0 failures, 22 skipped (opt-in). A first run had 7 `MainActivityTest` failures because the emulator was still signed in from the manual device run; after signing out, the full suite passed |
| Hosted CI on PR #31 | dotnet, android, sql green |
| **After the follow-up** (`a0d882a`): all .NET suites above, SqlIntegration 16/16, Hangfire 13/13 | PASS |
| Android unit (debug / release) after the follow-up | 677 / 683, 0 failures |
| Android instrumented after the follow-up | 203 run, 0 failures, 22 skipped (opt-in) |
| Hosted CI on `a0d882a` | dotnet, android, sql green |

## Final verified commit

| Item | Value |
| --- | --- |
| Final verified `dev` code commit | **`bc652ce`**: merge of PR #29, after PR #28 (`4e0b97f`), merged in dependency order on 27 November 2026 |
| Equivalence | `git diff e85c0ef bc652ce` is empty: merged `dev` is byte-identical to the tested PR head |
| Hosted CI | dotnet, android and sql green on #28, #29 and the `dev` push of `bc652ce` |
| Later commits | Documentation only (this record). Confirm with the command in the handoff guide §3 |
| Not merged | `main` (owner approval required) |
| Production | Not performed by this team (D10) |
