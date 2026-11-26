# Approved owner requirements — selected original excerpts

> Source: Project-owner Phase 0 Finalization instructions, approved 26 November 2026
> Collected: 2026-11-26
> Published: 2026-11-26

These are selected original excerpts, not a replacement for the complete approved prompt.

## 3. Adopt the Integrated Engineering Methodology

Establish the approved SmartFarm/AniGo-derived engineering workflow, adapted specifically to OWLI:

**Karpathy LLM Wiki → Superpowers → Ponytail → Impeccable when applicable → Independent Review and Verification → Knowledge Update → Reviewed Integration into dev.**

These tools must complement one another rather than operate as independent planning systems.

Testing must be incorporated throughout development, particularly when reproducing defects, defining regression tests, implementing corrections, and verifying behavior.

Apply the following principles:

- Inspect relevant knowledge and current source before making changes.
- Treat approved requirements as authoritative for expected behavior.
- Treat current source as authoritative for implemented behavior.
- Use actual runtime evidence to establish verified behavior.
- Investigate root causes.
- Prefer the smallest complete and maintainable solution.
- Preserve existing working functionality.
- Avoid duplicated analysis and specifications.
- Avoid unnecessary context and token consumption.
- Apply skill workflows proportionately to task complexity and risk.
- Update only relevant knowledge after significant verified work.
- Integrate reviewed and verified changes into dev.

Do not automatically invoke every skill for every task.

Maintain one coherent plan and one acceptance record per substantial development task.

Skills must not override OWLI's financial safeguards, academic requirements, approved architecture, security policies, or Git workflow.

### 7.4 Dependency architecture

The eventual dependency direction should be:

- API → Infrastructure and Domain.
- Worker → Infrastructure and Domain as required.
- Infrastructure → Domain.
- MVC and Android → Web API.

Do not make the Worker reference the API executable project.

Do not make the API reference the Worker executable project.

Reuse existing financial services rather than duplicating business logic.

Do not introduce unnecessary microservices, class libraries, scheduling loops, or message brokers.

### 7.5 Selected job implementation

Accept the assessment's recommendation to retain the existing `FinanceJobs` implementation family initially, subject to subsequent correctness verification.

Preserve the existing recurring job identifiers:

- `owli-bill-reminders`
- `owli-budget-alerts`
- `owli-monthly-summaries`

Preserve the existing schedules:

- Upcoming bill reminders: daily at 7:00 AM.
- Budget threshold alerts: hourly.
- Monthly financial summaries: first day of each month at 12:30 AM.

Use `Asia/Manila` timezone.

Do not activate the competing implementation family.

Preserve compatible job type identities, signatures, serializer conventions, recurring identifiers, and persisted job compatibility where practical.

Record operational cutover and existing persisted job compatibility as requirements for subsequent implementation.

## 10. Preserve OWLI Requirements and Financial Invariants

The MVC Member website must remain a full web counterpart of the Android application, not merely an Administrator dashboard.

Maintain the approved requirements for:

- Income and expense recording.
- Transaction history.
- Categories.
- Budgets and budget validation.
- Savings goals and contributions.
- Recurring bills and payment records.
- Financial reports.
- Currency preferences.
- Frankfurter exchange rates.
- Financial notifications.
- Upcoming bill reminders.
- Budget threshold alerts.
- Monthly financial summaries.
- Room offline-first recording.
- Reliable synchronization.
- Role-based authentication and authorization.
- Separate Administrator requirements.
- Full Android and MVC Member functional parity.

Protect:

- Monetary precision.
- Historical exchange rates.
- Financial transaction atomicity.
- Idempotency.
- Concurrency.
- Account isolation.
- Contribution ledger integrity.
- Bill payment receipt integrity.
- Database constraints.
- Migration guards.
- Financial history.
- Offline recovery and synchronization.
- Financial report consistency.

Do not weaken these requirements to accelerate development.

Do not introduce unapproved functionality.

Use synthetic development data for eventual testing.

Respect the academic project's permitted scope of external assistance and contribution requirements.
