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
| Interactive elements inventoried | **926**. MVC: 780 on 37 pages (member: 29 pages, 544 elements; administrator: 8 pages, 236). Android: 146 clickable nodes on 8 screens |
| MVC inventory checks on all 780 | Every page returns 200; 0 broken internal links; 0 unnamed controls; 0 POST forms without antiforgery; 1 console error per page (missing icon, fixed) |
| Functional scenarios executed through the real UI | **151**: MVC member 41, MVC administrator + reliability + worker 16, MVC authentication 3, MVC validation 24, MVC session/outage 3, Android device steps 13, cross-client gate 14, rendered layout/axe 36 page-width-theme cases (counted as 36) |
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

## Final verified commit

The verified head and its `dev` merge commit are recorded here after PRs #28 and #29 are reviewed and merged. Until
then, the last verified `dev` code commit remains `600244d` (Phase 5).
