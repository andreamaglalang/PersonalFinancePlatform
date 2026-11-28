# OWLI financial invariants and storage boundaries

> Sources: Project owner, 2026-11-26; OWLI application source, 2026-11-26
> Raw: [Approved requirements](../../raw/owli/2026-11-26-approved-requirements.md); [Source snapshot](../../raw/owli/2026-11-26-application-source.md); [Phase 1 audit](../../raw/owli/2026-11-26-phase1-audit.md); [Input validation audit](../../raw/owli/2026-11-28-input-validation-audit.md)
> Raw (notifications): [Financial notifications](../../raw/owli/2026-11-28-financial-notifications.md)
> Updated: 2026-11-28

## Required invariants

Preserve monetary precision, historical FX, account isolation, authentication and
authorization, atomic writes, concurrency, idempotency, contribution ledgers,
bill-payment receipts, budget transitions, financial history and offline recovery.
Never remove constraints or migration guards to make tests pass. Both clients
must agree through the shared backend; expected scope is in
[PRODUCT.md](../../../../PRODUCT.md).

## Observed safeguards and their limits

Source maps money to decimal precision and supplies transaction-owned user locks
inside a serializable write scope. Notifications have a unique user/deduplication
key, and monthly summaries have a unique user/month key. Selected budget/summary
jobs use the shared financial write scope. These mechanisms are implemented;
actual SQL race, rollback and cross-client behavior still needs runtime evidence.
Retry/concurrency attributes alone do not prove exactly-once financial effects.

The Phase 1 audit (source-confirmed, not reproduced) found these integrity gaps:

- Transaction PUT carries no client version token, so concurrent cross-client
  edits are last-writer-wins.
- A replayed transaction ClientId with a different payload returns the original
  without a mismatch check.
- Android keeps uncertain savings/bill write identities in memory only. After
  process death, a retried contribution can receive a new ClientId. Bill
  payments are also guarded by unique `(BillId, DueDate)`; contributions are not.
- A budget referenced by an alert notification cannot be deleted.

`PersonalFinanceDb` is the finance catalog; `OWLI_Hangfire` is separate operational
storage on the same SQL instance. Preserve that guard and disabled automatic
schema preparation. No separate worker financial database is required.
Use approved disposable targets for mutating tests; preserve the existing
finance database and migration history. Private diagnostics and real financial
records do not belong in this wiki's raw sources.

## Input boundary (2026-11-28)

The API is the authoritative input boundary; clients only repeat its rules for
feedback. Typed money is read exactly: digits with one decimal separator. Grouping,
signs, symbols and exponents are refused and never reinterpreted. Android also
accepts a decimal comma; MVC asks for a decimal point. Names are single-line (no
control characters). Malformed JSON gets plain field messages without type names.
Bill currencies must be offered by the rate provider. See
`docs/audits/OWLI_Input_Validation_Audit.md` for the policy, matrix and open decisions
(the MVC 100,000,000 transaction cap and the MVC decimal comma).

## Notifications (2026-11-28)

Event notifications (goal milestones and completion, payment confirmations, password changes) commit in the same
transaction as their financial or authentication change; scheduled ones stay in the independent worker. Deduplication
relies on the unique (UserId, DeduplicationKey) index. Goal notifications require an upward crossing
against authoritative before/after totals and target, then highest-ever-notified suppression;
legacy metadata/noncrossing edits are silent. Overdue insertion rechecks the unpaid active occurrence
under the same user/bill locks as payments and deactivation. Notification-save failures roll back
financial/authentication changes (19 real SQL checks in the continuation). Android restarts unread
pagination after read mutations and clears its account-owned count on session changes. Policies:
docs/NOTIFICATIONS.md; fresh source: raw/owli/2026-11-28-notification-continuation.md.

## See Also

The local Phase 9 candidate supersedes the historical transaction protection claims:
DELETE requires the last-seen version and retains owner/ClientId identities after
deletion. A deleted identity returns409 on create; retention/removal are atomic.
Do not purge identities or run an unaware old API with writes enabled. Prior absent
identities cannot be reconstructed. SQL22 (populated upgrade and post-SQL rollback),
Finance463 and Schema66 pass; no existing database was migrated. Evidence:
[Phase 9 snapshot](../../raw/owli/2026-11-28-phase9-corrections.md),
[candidate contracts](../../../PHASE9_CORRECTIONS.md).

- [Architecture and worker decision](architecture.md)
- [Verification and decisions](verification-and-decisions.md)
