# OWLI approved product requirements

> **Phase closeout, 28 November 2026:** approved development is complete at `ec73858ef54a7323523f32989942220ca53461b1`. [Final handoff](docs/FINAL_HANDOFF.md) records implemented coverage, evidence and remaining limits. The original Phase 0 questions/boundaries below are dated requirements history, not instructions to begin another audit or implementation phase.

Authority: project-owner-approved methodology/architecture assessment and Phase 0
finalization instructions, 26 November 2026. This defines expected behavior,
not certification that the current implementation satisfies it. Current source,
historical reports and implementation proposals do not silently expand scope.
See [integration contracts](docs/backend-integration.md),
[knowledge index](docs/karpathy-wiki/wiki/index.md), and
[engineering standards](docs/AI_DEVELOPMENT.md).

## Objective and scope

Stabilize and complete the existing Personal Finance and Budget Monitoring
Platform. Reuse its working implementations rather than rebuild. Retain the
shared .NET 10 Web API, EF Core/SQL Server, MVC Member/Administrator site,
Android Kotlin/Compose/Room application, Frankfurter FX and Hangfire automation.
No new microservices, duplicate backend/financial database, broker, external
notification service or full redesign without an approved need.

## Approved capabilities and acceptance

| Area | Required outcome and acceptance evidence |
| --- | --- |
| Personal finance | Income/expense recording, history, categories, budgets/validation, savings goals/contributions, recurring bills/payments, reports, currency preferences and financial notifications; authorized CRUD/results persist correctly |
| Android | Secure API/session handling, Room offline recording/history, explicit pending/rejected/server-confirmed states, retry/recovery and duplicate/conflict handling verified on a device/emulator |
| MVC Member | Complete web counterpart of Android, shared accounts/data/business rules, responsive accessible interfaces and consistent branding; not just an administrator dashboard |
| MVC Administrator | Preserve budget/category administration, reports and role-based access; additional administrative features need requirement confirmation |
| Shared API/database | Enforce validation, ownership, roles, persistence, transactions, concurrency and compatible contracts; Android changes appear in MVC and vice versa through approved refresh/sync mechanisms |
| Currency | Frankfurter integration and preferences; preserve each transaction's historical conversion/rate snapshot and consistent base-currency totals; test precision/rounding and unavailable-rate behavior |
| Background processing | Separate independently executable .NET 10 `PersonalFinance.Worker` console/Generic Host in the existing solution; API must not host a Hangfire server or recurring registration; reuse shared job/services without executable-project cycles |
| Jobs/storage | Initially retain `FinanceJobs`; bill reminders daily 07:00, budget alerts hourly, previous-month summaries on day 1 at 00:30, all `Asia/Manila`; separate `OWLI_Hangfire` storage and finance results in `PersonalFinanceDb`; prove restart/retry/concurrency safety |
| UI/UX | Preserve OWLI identity/tokens and reusable Razor/Compose components; validate rendered responsive layouts, native interactions, keyboard/accessibility/text scaling and all loading/empty/error/offline/sync/success states |

## Financial invariants

Never trade away authentication, authorization, account isolation, monetary
precision/rounding, historical FX accuracy, atomicity, idempotency, concurrency,
contribution ledgers, bill-payment receipts, budget transitions, report accuracy
or offline recovery. Preserve migrations, guards, constraints and financial
history. Use synthetic data and approved disposable targets for mutation tests.
No existing database resets or guard removal to make a test pass.

A build or unit test alone is insufficient. Acceptance requires relevant unit,
SQL, API, rendered-client, cross-platform, worker lifecycle and regression
evidence. Record commands, commits, actual results and skipped checks using
PASS/FAIL/BLOCKED/NOT TESTED/NOT APPLICABLE. Distinguish source implementation from
runtime verification. Respect the academic project's permitted assistance and
contribution rules; do not invent an institution policy.

## Unresolved decisions and boundaries

Confirm intended existing-monthly-summary immutability versus recomputation;
retirement/replacement of the Development manual-job endpoint; access to stored
summary snapshots from both clients; portable disposable SQL targets; persisted
job cutover compatibility; and deployment/runtime credential requirements.
These are audit questions, not approved new features. Real-time push, email,
new dashboards/public worker endpoints, and additional administrator features
are not assumed requirements. In-app notifications do not establish external
delivery.

Phase 0 may configure agents/docs/wiki, provision only guarded dedicated local
Hangfire storage, and prepare disabled private worker settings. It must not
create the actual worker, change application code/contracts/project references,
run finance migrations, activate jobs/processors, alter existing app settings,
reset containers/data/certificates, deploy, or create the final roadmap.

Prioritization target: Friday night, 27 November 2026; Saturday night,
28 November 2026 is final-testing allowance, Asia/Manila. The deadline never
waives correctness. Follow [the Git workflow](docs/DEVELOPMENT_WORKFLOW.md):
reviewed task branches from synchronized `dev`; preserved handoff; stable
`main` releases require explicit approval.
