# OWLI Android Development Standards

> Part A holds permanent working rules. Part B is a dated status snapshot that must be
> updated (or removed) as the project changes; it is context, not a rule.
> Throughout: **Implemented** = confirmed in code. **Planned** = proposed, not built.
> **Backend-dependent** = requires backend work or coordination before it can be real.

---

# Part A — Permanent instructions

## 1. Project identity and ownership
- **Product:** OWLI — "Your Wise Money Companion" (`app_name`, `app_tagline` in
  `android/app/src/main/res/values/strings.xml`).
- **Platform:** Personal Finance and Budget Monitoring Platform (monorepo):
  - `android/` — Android client (Kotlin, Jetpack Compose). Gradle root project
    `PersonalFinanceAndroid`, single module `:app`.
  - `PersonalFinance.Api`, `PersonalFinance.Domain`, `PersonalFinance.Infrastructure` —
    shared ASP.NET Core backend (EF Core, SQL Server).
  - `tests/` (backend tests), `tools/`, `docs/`.
- **Ownership:**
  - **Andrea** owns Android development (`android/`).
  - **Xerxes** owns MVC development.
  - The backend projects are shared. Any backend change requires coordination with Xerxes
    and explicit approval (see §9).
- **Objective:** a reliable, demonstrable, honest personal-finance Android client with
  authenticated, account-isolated financial workflows and a cohesive, premium Lavender &
  Midnight experience across the entire app.

## 2. Plan-first rule (mandatory)
Before doing any of the following, a contributor must propose a **scoped implementation plan**
(goal, files affected, approach, verification, risks) and receive explicit approval:
- editing, creating, renaming, or deleting files;
- running builds, tests, Gradle tasks, or the app;
- starting the backend API, LocalDB, or any service;
- accessing databases, executing SQL, or creating/applying migrations;
- making HTTP requests or financial transactions;
- any Git mutation (add, commit, branch, switch, stash, merge, rebase, reset, clean,
  restore, push).

Approval applies only to the scope approved. Read-only inspection (reading files,
`git status`, `git log`, `git diff`) is allowed without approval. UI work additionally
requires the design deliverables in §6.5 before implementation. If scope is unclear, ask.

## 3. Git safety and preservation of work
- Preserve all tracked and untracked work. Never overwrite, revert, or reformat existing
  changes.
- Never run destructive commands (`git reset`, `git clean`, `git checkout -- <path>`,
  `git restore`, `git stash`, force push, history rewrites) without specific authorization
  naming that command.
- Do not create or switch branches, commit, stash, or checkpoint unless explicitly asked.
- When committing is authorized, stage files explicitly by path and show the file list
  first. Never use `git add -A` / `git add .` without confirmation.
- Before editing a file that already has uncommitted changes, read it fully and make the
  smallest targeted edit.
- Never modify the original checkout or the MVC work.

## 4. Android technology stack and architecture (Implemented)
- Kotlin, Jetpack Compose (Compose BOM), Material 3, Navigation Compose,
  `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`. Versions live in
  `android/gradle/libs.versions.toml`. compileSdk/targetSdk 36, minSdk 26.
- **Not used:** dependency-injection frameworks (Hilt/Koin), Room, DataStore, Retrofit,
  chart libraries, material-icons-extended. **Do not add dependencies without approval.**
- Package root: `com.elect3.mobdev.personalfinance` under
  `android/app/src/main/java/com/elect3/mobdev/personalfinance/`:
  - `domain/model` — `Transaction`, `TransactionType`, `TransactionPage`, `DashboardSummary`
  - `domain/repository` — `TransactionRepository` (`TransactionPageResult`,
    `TransactionFailure`), `FinanceRepository.kt` (`FinanceFailure`, `FinanceResult`,
    `Category`, `CategoryQuery`, `TransactionQuery`, `CategoryRepository`),
    `TransactionCreationRepository`
  - `domain/dashboard` — `DashboardSummaryCalculator`, `LoadDashboardSummary`
  - `data/mock` — `MockTransactionRepository`, `MockScenario`, `TransactionFixtures`,
    `DemoCategoryRepository`
  - `data/remote` — `ApiConfiguration`, `AuthApiClient`, `FinanceTransport`
    (`FinanceResource`), `FinanceRepositories` (`RemoteCategoryRepository`,
    `RemoteTransactionRepository`), `FinanceJson`, `FinanceCreationTransport`
    (`RemoteTransactionCreationRepository`), `TransactionCreationCoordinator`,
    `LogoutRequestObservation`
  - `data/auth` — `AuthRepository.kt` (`InMemoryAuthRepository`), `AuthDiagnostics`,
    `ActivityLifecycleDiagnostics`
  - `ui/app` — `OwliEntryGate`, `OwliFinanceContent`, `OwliApp`
  - `ui/auth` — `LoginScreen`, `LoginViewModel`
  - `ui/dashboard` — `DashboardScreen`, `DashboardComponents`, `DashboardViewModel`,
    `DashboardUiState`
  - `ui/transactions` — `TransactionListScreen`, `TransactionListViewModel`,
    `TransactionListUiState`, `TransactionRow`, `TransactionFormatting`,
    `AccountTransactionStore`
  - `ui/transactions/add` — `AddTransactionScreen` (`AddTransactionDialog`),
    `AddTransactionViewModel` (`FormWorkflow`), `AddTransactionCompletion`,
    `OfflineSaveWorkflow`
  - `ui/components`, `ui/theme` — see §5
- Build-variant source sets: `src/debug` and `src/release` each provide `ApiEnvironment`
  and `AuthDiagnostics`. Debug targets the approved local development API; release is
  unconfigured.
- **Wiring:** manual, in `MainActivity.kt`, via a `ViewModelProvider.Factory`. Routes and
  screens receive ViewModels and actions from their owner and never construct repositories
  or ViewModels during composition. Preserve this pattern.
- **Composition hierarchy:** `OwliEntryGate` (Login / Mock Demo / Authenticated)
  → `OwliFinanceContent` (per-account `AccountTransactionStore`, Add Transaction dialog)
  → `OwliApp` (`NavHost` with `dashboard` and `transactions`, bottom `NavigationBar`,
  save/restore tab state).
- **Back behaviour:** Transactions → Dashboard. In Mock Demo, Back on Dashboard returns to
  Login. Covered by `OwliAppTest`, `AuthGateTest`,
  `AuthenticatedTransactionsNavigationTest`; update those tests with any change.
- Preserve the existing architecture. Propose structural changes before making them.

## 5. Lavender & Midnight design system (Implemented foundation)
- Source of truth: `ui/theme/Theme.kt`, applied by `PersonalFinanceTheme`.
  - Branded light and dark Material 3 color schemes. Dynamic (wallpaper) color stays off.
  - `LocalOwliColors` (`OwliExtensionColors`): `income`, `expense`, `heroStart`,
    `heroEnd`, card fill/label pairs for income, expense, entries, and top category,
    `navUnselected`, and `brandAccent` (light only, decorative, never used for text).
  - `LocalOwliTypography` (`OwliTypographyTokens`, Plus Jakarta Sans): `heroAmount`,
    `screenTitle`, `greeting`, `sectionTitle`, `amountMedium`, `bodyStrong`, `amountRow`,
    `body`, `caption`, `label`, `micro`, `wordmark`. Amount styles request tabular numerals.
  - `LocalOwliShapes` (`OwliShapeTokens`): `hero`, `bottomBar`, `summaryCard`,
    `listContainer`, `navigationIndicator`, `rowTile`, `brandTile`, `button`, `chip`.
  - `MaterialTheme.typography` remains the default `Typography()`. Adopt Owli tokens screen
    by screen; do not replace Material typography globally without approval.
- Fonts: Plus Jakarta Sans (regular, medium, semibold, bold, extrabold) in
  `android/app/src/main/res/font/`, with its licence at
  `android/app/src/main/assets/licenses/plus_jakarta_sans_ofl.txt`.
- Brand and icon assets in `android/app/src/main/res/drawable/`: `ic_owli_mark`,
  `ic_owli_home`, `ic_owli_transactions_list`, `ic_owli_income_up`,
  `ic_owli_expense_down`, `ic_dashboard`, `ic_transactions`.
- Reusable components (`ui/components`): `OwliBrand`, `OwliTopBar`, `OwliMockDataChip`,
  `OwliSummaryCard` (`OwliSummaryRole`: Income, Expense, Neutral),
  `OwliTransactionDirectionTile`. Reuse or extend these before creating new components.
- Use tokens rather than hard-coded colors, text sizes, or corner radii. New tokens go in
  `Theme.kt` with both light and dark values.
- **Accessibility requirements:**
  - Never ellipsize or height-limit currency amounts; let them wrap.
  - Follow the existing measured side-by-side → stacked fallback pattern
    (`DashboardIncomeExpenses`, `TransactionRow`); support narrow widths and 2× font scale.
  - Touch targets at least 48dp; section titles use `semantics { heading() }`;
    loading, status, and error text uses polite live regions.
  - Verify light and dark themes.

## 6. Application-Wide UI/UX Design Responsibility

### 6.1 Role
UI/UX design and implementation responsibility covers the **entire OWLI Android
application**, not only the Dashboard. Andrea remains the owner and final
decision-maker. All design work stays subject to the plan-first rule (§2) and every other
rule in this file.

### 6.2 Scope (every surface of the app)
| Surface | Current classification |
|---|---|
| Onboarding / welcome | Planned (no onboarding flow exists). |
| Login | Implemented (`LoginScreen`, `LoginViewModel`). |
| Registration | Backend endpoint exists (`api/auth/register`); **no Android client call or UI**. Backend-dependent integration. |
| Mock Demo entry and labelling | Implemented (`OwliEntryGate`, `auth_demo_title`). |
| Dashboard | Implemented with sample data only (see §8 and Part B). |
| Transactions list | Implemented. |
| Add Income / Expense | Implemented UI; authenticated saving not enabled at runtime. |
| Categories | Implemented as a picker inside Add Transaction; no category management screen. Backend has category CRUD (`api/categories`). |
| Budgets | Planned. Backend API exists (`api/budgets`); no Android data layer. |
| Savings Goals | Planned. Backend API exists (`api/savings-goals`, including contributions); no Android data layer. |
| Recurring Bills | Planned and backend-dependent: domain entities exist (`RecurringBill`, `BillRecurrence`), **no API controller**. |
| Reports and analytics | Planned and backend-dependent: no reports endpoint; `MonthlySummary` entity has no API. Mock-only views must be labelled as sample data. |
| Profile / settings | Planned. Backend `api/auth/me` and `api/auth/logout-all` exist; no Android UI. |
| Navigation | Implemented: two-tab bottom bar (Dashboard, Transactions) plus the entry gate's top strip. Redesign planned. |
| Dialogs, sheets, confirmations | Implemented: Add Transaction dialog and discard confirmation. Others planned. |
| System states: loading, empty, error, offline, session-expired, refresh-failed | Partially implemented: Dashboard and Transactions have loading, empty, error, session-expired, and retained-content refresh failure. **No general offline state** (the offline-save workflow is test/preview-only). Planned as a unified pattern. |

Design deliverables must keep these classifications visible. A planned or
backend-dependent screen may be designed, but it must never be shown as working.
Placeholder screens must be clearly labelled (for example "Coming soon" or "Sample data")
and must not fake financial behaviour.

### 6.3 Design direction
- **Quality bar:** cohesive, premium, modern, elegant, and visually impressive. It should
  feel like a polished, trustworthy finance product.
- **Themes:** coordinated Lavender & Midnight light and dark themes from `Theme.kt`, with
  equal care for both; dynamic color stays off.
- **Typography:** Plus Jakarta Sans via the Owli tokens, with a clear hierarchy
  (hero amount → screen title → section title → body → caption) and tabular numerals for
  amounts.
- **Branding:** tasteful owl branding using the existing `ic_owli_mark` / `OwliBrand`.
  Use it for identity moments (entry, empty states, success) without cluttering data
  screens.
- **Layout:** responsive from narrow phones to large font scales; consistent spacing
  rhythm, card hierarchy, and gutters; important information first.
- **Accessibility:** all §5 requirements, sufficient contrast in both themes, meaningful
  content descriptions, and logical focus and traversal order.
- **Motion:** refined and purposeful (state changes, list updates, card reveals,
  navigation). Use Compose's built-in animation APIs only, keep motion subtle and short,
  respect the system "remove animations" setting, and keep tests deterministic. Navigation
  transitions are currently disabled in `OwliApp` (`EnterTransition.None` /
  `ExitTransition.None`); changing them is a scoped, approved task that includes test
  updates.
- **Financial honesty in design:** sample-data labels stay prominent; net flow is never
  shown as a balance; amounts are never truncated (§8).

### 6.4 Creative freedom and constraints
Contributors may propose improvements to layout, information hierarchy, navigation structure
(for example additional destinations, a central add action, or a profile entry point),
interaction patterns, and the reusable component library, provided that:
- OWLI's identity (name, tagline, Lavender & Midnight palette, Plus Jakarta Sans, owl
  mark) is preserved;
- no new dependencies are introduced without approval (Compose, Material 3, and Canvas
  are preferred for charts, icons, and motion);
- the existing architecture, authentication flow, account isolation, and Back behaviour
  are preserved unless a change is separately approved;
- proposals are clearly marked as proposals, with trade-offs stated.

### 6.5 Required design deliverables (before any UI implementation)
Before editing any UI code, the contributor must produce the following for review and receive
approval:
1. **Screen inventory:** every screen and state in §6.2, each marked Implemented /
   Planned / Backend-dependent, with its data source (mock, remote, none).
2. **Navigation map:** entry gate, authenticated and demo flows, tabs and destinations,
   dialogs, Back behaviour, and session-expiry and logout exits.
3. **Design system specification:** color roles (light and dark), typography scale,
   spacing, shapes, elevation, iconography, motion principles, and any proposed token
   additions to `Theme.kt`.
4. **Reusable component library:** existing components (§5) plus proposed new ones, each
   with purpose, API sketch, states, and accessibility behaviour.
5. **Screen-by-screen specifications:** layout and hierarchy, components used, data shown
   and its source, every state (loading, empty, error, offline, session-expired), light
   and dark notes, large-font behaviour, and sample-data labelling.
6. **Phased implementation plan:** small, reversible phases ordered by §11 priorities,
   each listing files, tests to update or add, verification, risks, and dependencies
   (especially backend-dependent items).

Deliverables are presented in chat, or as a document only if Andrea requests one.
Each implementation phase still needs its own explicit approval before files are edited.

## 7. Authentication, authorization, and account isolation (Implemented)
- Sessions are held in memory by `InMemoryAuthRepository`; nothing is persisted.
  Persisting tokens requires approval and a security review.
- `LoginViewModel.state.authenticatedContentKey` identifies the active session.
  Authenticated content is rebuilt per login (`key(contentKey)` in `OwliEntryGate`).
  Account data and navigation state must never survive logout or expiry.
- `AccountTransactionStore` scopes account financial state, invalidates it when the session
  key changes, and clears it on close. New account-scoped features must follow this pattern.
- Handle `FinanceFailure.MissingSession` / `FinanceFailure.SessionExpired` and
  `TransactionFailure.SessionExpired` in every remote path, routing to `onSessionExpired`.
- Mock Demo is never authentication. Demo state must not reach authenticated APIs, and
  account data must never appear in demo mode.
- Never log, display, or commit tokens, credentials, passwords, raw response bodies, or
  exception messages. Expose failures only as the bounded enums above.
- Do not open, print, or modify the debug certificate
  (`android/app/src/debug/res/raw/`), the debug network security configuration
  (`android/app/src/debug/res/xml/`), `appsettings*.json`, or any secrets.

## 8. Financial accuracy, currency, and disclosure
- Represent money with `BigDecimal`; never `Float`/`Double`. Do not alter values or scale
  during validation or formatting.
- `DashboardSummaryCalculator` (Implemented) supports **PHP only**, with FX rate 1 and
  `amountBase` equal to `amount`. It is a mock-data calculator, not a multi-currency engine.
  Account data with another base currency or FX conversion must not be summarized with it.
- `LoadDashboardSummary` (Implemented) reads every page and is only safe for a stable
  dataset such as the mock repository. Its documentation requires a snapshot token or an
  authoritative summary endpoint before use with remote data.
- **Net flow is income minus expenses. Never describe or label it as an account balance**
  ("Balance", "Available", "Total funds", etc.).
- **Sample-data disclosure:**
  - Any screen showing mock or sample figures must stay clearly labelled as sample data
    (existing strings: `dashboard_demo_data`, `auth_demo_title`,
    `auth_authenticated_sample_title`; component: `OwliMockDataChip`).
  - Redesigns must keep the label at least as prominent as before.
  - Use account-data wording only when the data actually comes from the signed-in account.
- Charts and analytics must be computed from verified data with the same `BigDecimal` and
  currency rules. Visual scaling must never change displayed values.

## 9. Shared backend, MVC coordination, and database restrictions
- Backend projects (`PersonalFinance.Api`, `PersonalFinance.Domain`,
  `PersonalFinance.Infrastructure`) are shared with Xerxes's MVC development. Android work
  must not change backend code, routes, DTOs, auth behaviour, or configuration without
  explicit approval and coordination.
- Write up backend needs (for example a summary endpoint, a recurring-bills controller, or
  reports endpoints) as proposals first.
- Database: EF Core with SQL Server; migrations live in
  `PersonalFinance.Infrastructure/Migrations/`.
  - Do not create, edit, remove, or apply migrations, start LocalDB, connect to any
    database, or execute SQL without separate, explicit approval.
  - Schema proposals must state the purpose, affected tables, rollback plan, and MVC impact.
- Do not start the backend API or send HTTP requests unless that session explicitly
  authorizes it.

## 10. Testing and verification
- Test locations (under `android/app/src/`):
  - `test/` — JVM unit tests (ViewModels, calculators, repositories, transports)
  - `androidTest/` — instrumented Compose UI and navigation tests
  - `testRelease/` — release-variant unit tests
- Gradle wrapper: `android/gradlew` (Unix shells) and `android/gradlew.bat` (Windows).
  Run from the `android/` directory, **only when approved**:
  - Unit tests: `.\gradlew.bat :app:testDebugUnitTest`
  - Release unit tests: `.\gradlew.bat :app:testReleaseUnitTest`
  - Instrumented tests (needs a device or emulator):
    `.\gradlew.bat :app:connectedDebugAndroidTest`

  These are standard Android Gradle Plugin task names; this file does not record them as
  having been run.
- Expectations:
  - UI changes update affected tests in the same change (for example `DashboardScreenTest`
    asserts specific text and sections).
  - New calculation logic gets unit tests; new UI gets Compose tests covering narrow width,
    2× font scale, dark theme, and each defined state (loading, empty, error,
    session-expired, and offline where applicable).
  - Motion must not make tests flaky; animations must be controllable in tests.
  - There are no screenshot tests. Describe manual visual checks (light/dark, 1×/2× font)
    in the completion report.

## 11. Development priorities (permanent ordering principles)
1. Reliable, demonstrable financial workflows that actually work end to end.
2. Authentication correctness and account isolation are never traded away for features
   or visuals.
3. Real authenticated transaction saving and Dashboard data accuracy are addressed through
   separately authorized, scoped tasks. They are not postponed by default and are not
   bundled into UI work.
4. Application-wide UI/UX improvements follow the §6.5 deliverables and are delivered
   incrementally, each phase small and reversible, reusing existing tokens and components.
5. Database migrations and shared-backend changes always need separate approval and
   coordination with Xerxes.

## 12. Integrity rules (non-negotiable)
- Do not invent functionality, endpoints, screens, data, file paths, or test results.
  If something does not exist in the code, say so.
- Never report tests, builds, or behaviour as passing unless they were run in the current
  session and the output was observed. Otherwise say "not run" or "not verified".
- In every report and design deliverable, separate confirmed findings (seen in code or
  output) from assumptions and from planned or backend-dependent work.
- Never present sample data as real account data in UI, copy, screenshots, mock-ups, or
  reports. Never design a planned screen so that it appears functional.
- If a request conflicts with these rules or its scope is ambiguous, stop and ask.

## 13. Completion report format
After every authorized task, report concisely:
1. **Summary:** what was done, in one or two sentences.
2. **Changed files:** paths, with a one-line description each (or "none").
3. **Verification performed:** exact commands or checks run and their observed results.
4. **Skipped checks:** what was not run or verified, and why.
5. **Risks / open issues:** known limitations, assumptions, follow-ups.
6. **Next steps:** proposed next scoped task(s), awaiting approval.

---

# Part B — Current project status (snapshot; update when outdated)

_Updated 2026-11-19 after the authorized Android mock-demo stabilization task. Not a rule._

- Branch: feature/android-lavender-midnight-ui; HEAD 177e7a66df9340947afd19768986962d43d1fcd0.
- Preserve all existing uncommitted/untracked work, including concurrent account, appearance and Dashboard changes. No backend changes were made by this task.
- DemoSession provides a shared synthetic in-memory ledger for creation, Activity and Dashboard; demo exit resets it.
- Authenticated transaction creation and refresh are wired; API sessions remain memory-only. Live behavior is not certified here.
- Activity filtering/search/pagination and category management are implemented. Dashboard net flow is not account balance.
- Room, Retrofit and OkHttp remain absent. Budgets, Savings Goals, Insights and registration remain incomplete Android workflows.
- AuthenticatedSaveNavigationTest now checks mock expense saving, Activity visibility, Dashboard totals, repeated tabs, synthetic labels and zero fake HTTP-engine calls.
- Offline debug JVM tests: 341 passed. Offline release JVM tests: 343 passed. Both suites were explicitly rerun; debug assembly and instrumentation Kotlin compilation also passed.
- New workflow instrumentation has not run on-device. A connected physical device is available, but standard APK deployment approval is pending clarification of the no-new-installation restriction. Historical 23-test Activity/list results do not cover the new workflow.
- No production defect was demonstrated or fixed. Complete workflow remains NOT YET DEVICE-VERIFIED. Concurrent source edits make results snapshot-specific.
- android/README.md records architecture, implemented/remaining features, exact commands and verification limits.

Next scoped step: execute only AuthenticatedSaveNavigationTest after APK deployment is accepted, preserving app data and using fake transports. No API, SQL, live credentials, dependency additions or unrelated implementation is authorized.
