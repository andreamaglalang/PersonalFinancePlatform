# Implementation Status (MOBDEV / ELECT3 Requirements)

> **Historical guide / status snapshot.** Do not treat the old blockers or next steps below as current execution instructions. Use [final handoff](../FINAL_HANDOFF.md) for release identity and reconciled limits, [developer setup](../DEVELOPER_SETUP.md) for local startup, and [production deployment](../../deployment/README.md) for receiving-team operations.

Status as of the integration branch on 2026-11-21. Categories:

- **Verified working**: demonstrated end to end at runtime during the 2026-11-21 verification. (Nothing qualifies: no runtime
  testing was authorized in the 2026-11-21 verification.)
- **Implemented, not runtime-verified**: code exists and offline tests pass, but there was no live API/DB/device run in the 2026-11-21 verification.
- **Partially implemented**: some of the requirement is missing.
- **Missing**: not implemented.
- **Blocked**: implemented but cannot work until an external decision (e.g. migration approval).

Earlier project records describe successful live demonstrations on the original PC (e.g. login and transaction sync).
Those were **not re-verified** here and are not counted as "verified working".

## Overall application

| Requirement | Status | Evidence / notes |
| --- | --- | --- |
| Expenses (record, list, edit, delete) | Implemented, not runtime-verified | API `transactions` + FinanceTests; Android entry/history; MVC has no member expense UI (not required) |
| Recurring bills | **Blocked** (API) / **Missing** (Android UI) | API CRUD + payments exist and are tested offline, but need migration 4 (unapproved). Android shows a placeholder |
| Budgets | Implemented, not runtime-verified | API, Android budgets screen, MVC budgets (own account) |
| Savings goals | **Blocked** (API) / **Missing** (Android UI) | API complete and tested offline; needs migration 3 (unapproved). Android placeholder |
| Financial reports | Implemented, not runtime-verified | API `reports/monthly` + MVC page + Android on-device reports |

## ELECT3 MVC

| Requirement | Status | Notes |
| --- | --- | --- |
| Budget administration | **Partially implemented** | CRUD works on the signed-in administrator's **own** budgets. No cross-user administration (no API) |
| Category management | Implemented, not runtime-verified | Shared default categories: create, edit, deactivate/reactivate |
| Financial reports | **Partially implemented** | Monthly report for the signed-in administrator's own account. No platform-wide or other-user reports, no export |

## Web API

| Requirement | Status | Notes |
| --- | --- | --- |
| Expense recording | Implemented, not runtime-verified | Idempotent `ClientId`, FX snapshot, user isolation; 411 FinanceTests checks |
| Budget validation | Implemented, not runtime-verified | Monthly category budgets; transaction responses report affected budgets/usage; validation rules tested |
| Recurring-bill endpoints | **Blocked** | `api/recurring-bills` CRUD + `{id}/payments` exist; runtime requires migration 4 |

## Hangfire

| Requirement | Status | Notes |
| --- | --- | --- |
| Upcoming bill reminders | **Blocked** | Job code + offline tests exist. Needs Hangfire enabled (schema not installed) and recurring bills (migration 4). No dev trigger |
| Monthly summaries | Implemented, not runtime-verified | Job code + tests; runnable via the Development trigger. Scheduling needs Hangfire schema |
| Budget threshold alerts | Implemented, not runtime-verified | Job code + tests; runnable via the Development trigger. Scheduling needs Hangfire schema |
| Job registration and scheduling | **Blocked** | Registration code exists (3 cron jobs, retry 3). Hangfire disabled; schema never installed; not runtime-verified |

## MOBDEV Android

| Requirement | Status | Notes |
| --- | --- | --- |
| Expense entry | Implemented, not runtime-verified | 523 unit tests pass; Mock Demo works offline |
| Local transaction history | Implemented, not runtime-verified | Room cache |
| Room offline-first recording | Implemented, not runtime-verified | Pending → Synced/Rejected, explicit migrations |
| Backend synchronization | Implemented, not runtime-verified | Needs the new PC's dev certificate + `adb reverse` (ANDROID_SETUP.md) |

## External service

| Requirement | Status | Notes |
| --- | --- | --- |
| Frankfurter reference exchange rates | Implemented, not runtime-verified | `FrankfurterClient` (v2 API, retries capped, caching) + `exchange-rates/quote`; tested with fakes. Needs internet at runtime |
