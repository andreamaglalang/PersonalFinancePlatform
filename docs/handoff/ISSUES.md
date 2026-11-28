# Known issues and remaining work

> **Historical issue inventory — superseded as a current missing-work list.** See [final closeout](../FINAL_HANDOFF.md#historical-issues-versus-remaining-limits) for current limits at `ec73858ef54a7323523f32989942220ca53461b1`. Approved development is complete; this closeout does not initiate the old acceptance/development list below.

## Current reconciliation — 28 November 2026

| Earlier issue class | Current disposition |
|---|---|
| Missing MVC Member/Android goals, bills, reports or parity | Superseded by implemented paths and A1–A16 runtime evidence |
| SQL/concurrency/migrations wholly unverified; fixed-LocalDB-only provisioning | Superseded for approved disposable targets by SQL/auth/provisioning records. Existing production data still needs independent migration-history verification and upgrade rehearsal |
| Automation family undecided / worker unavailable | Superseded: independent FinanceJobs host, dedicated storage and live lifecycle evidence. Production activation remains a receiving-team operation |
| UTC financial dates and local phone/startup trust | Superseded by Philippine-date fixes and PRs #33/#38, including limited physical-phone installation/HTTPS/sign-in evidence |
| Sessions/offline/admin scope | Retain actual limits: in-process MVC sessions, bounded Android offline creation/retry, D6 own-account administrator scope and documented input-policy differences |
| Accessibility, real-data upgrade, production signing/runtime | Retain unverified scope and receiving-team gates. A blanket “no physical phone tested” statement is superseded only by the narrow PR38 evidence |

The original findings below remain as history. They do not override the final status map,
owner acceptance or current runbooks. Repository-exposure history is retained; this closeout
does not rewrite Git history or certify all past commits.

## Original handoff snapshot

This was the original 25 November source handoff status. Its offline-only qualifications describe that earlier snapshot; later scoped SQL/device evidence and the current reconciliation above supersede the mapped gaps.

## Integration and setup

- **Fresh SQL initialization unverified:** all five migrations are present, but this handoff does not execute SQL. Existing databases with migrations 1–2 cannot support the current savings/bill model. Migration 4 requires empty bill-related tables; migration 5 rejects payment history whose dates differ from Philippine calendar dates. Review and validate on a disposable database first. Do not treat an old report of deployed schema as current evidence.
- **Two account APIs:** MVC uses `/api/auth/me` and `/api/auth/change-password`; Android uses `/api/account/profile` and `/api/account/change-password`. The latter requires a version token for profile updates and confirmation for password changes. Consolidate contracts and clients in a future version; do not silently change either route. Password changes invalidate old security-stamped access tokens and refresh sessions; older clients/tokens must sign in again.
- **Two Hangfire implementations:** active `FinanceJobs/HangfireSetup` and preserved alternative `UpcomingBillReminderJob/MonthlyFinancialSummaryJob/BudgetThresholdAlertJob`. They have different IDs, schedules and behavior. Never schedule both. The newer standalone SQL integration test still requires a dated disposable validation database and is not a portable smoke test. Port it to a generic disposable-target harness before adopting that family.
- **Hangfire activation:** separate catalog and explicit schema installation are required; automatic table installation is disabled. No Hangfire dashboard or fully validated operational provisioning workflow is supplied. Notification inbox records are implemented; external push/email delivery remains separate work.
- **Android divergent work:** older Android-only notifications/cache/bills/savings work is preserved as a patch, not mixed into the selected OWLI source. Review and selectively port useful differences. This is not evidence that all alternative features are delivered in the active app.
- **Android release configuration:** release has no endpoint and no debug user-certificate trust. Configure your own public HTTPS origin before release. Debug localhost requires `adb reverse` and a user-installed certificate generated on the new PC. No developer certificate is bundled.
- **Android instrumentation:** tests requiring an emulator/device, live endpoint, installed accounts or persistent sessions are not executed as part of offline verification. Use dedicated synthetic accounts and a fresh test device; some instrumentation tests can write application data. Offline Room storage is not proof of complete conflict-free financial write synchronization; test logout, account switching, retry/idempotency and ambiguous-delivery recovery end to end.
- **MVC sessions:** in-process token/session storage is limited and lost on restart. Add distributed protected storage before multiple processes or scaling. Review cookie/session expiry, concurrent refresh and account changes across MVC and Android together.
- **Provisioning portability:** administrator CLI intentionally supports only Windows LocalDB with the fixed development database name and interactive input. For another SQL platform, design an explicit safe provisioning workflow; do not weaken current checks.
- **Historical docs:** deployment validation, Hermes reports, requirement matrices and old developer-guide migration/status statements describe older commits. The current entry point is this handoff folder. Do not use old machine-bound hashes, runbooks or SQL evidence as authorization for a different target.
- **Alternative platform portal:** GitHub branch `handoff/owli-visual-parity-20261122` (`faa7b17`) contains another `/Personal` MVC architecture and platform admin/content/support APIs plus two extra migrations. Its full source/history is retained, but these competing features are not enabled in this selected build. Selectively port them with model, migration, authentication and UI tests; a wholesale merge would remove integrated Android, FX, bill and Hangfire functionality. See SOURCE_MAP.md for recovery commands.
- **Android compiler warnings:** deprecated system-bar setters and Gson leniency, an always-true UI condition, and future Kotlin annotation-target semantics remain. They are warnings; modernization is deferred.

## Historical remaining work at the original handoff — not current instructions

1. Prove empty SQL database initialization through migration 5 and all CRUD flows against SQL Server; verify rollback, uniqueness/collation, receipt guards, ETags and concurrent writes. Run SQL tests only on dedicated disposable targets after adapting their manifests/guards.
2. Validate registration/login/refresh/logout/password-change and profile-version behavior with both clients and simultaneous sessions. Decide a single future account contract and migration strategy.
3. Choose one automation family, install dedicated Hangfire storage locally, and verify timezone boundaries, retry/deduplication, budget transitions and monthly summaries. Define operational monitoring and notification delivery requirements.
4. Run Android instrumentation and manual cross-client scenarios for transaction creation/editing, budgets, categories, savings/contributions, bills/payment replay, reports/FX, preferences and offline recovery. Resolve retained alternative-source differences deliberately.
5. Confirm the academic requirement matrix with the developer: administrator cross-user/user-management aggregates, reset/recovery/email verification, push/email delivery and complete offline write conflict handling must not be assumed complete from visible UI or source presence.
6. Production hardening: select real HTTPS origins, release signing outside Git, durable MVC session/key storage, independent secrets, least-privilege SQL users, backups/restore validation, logging/monitoring and deployment review. None requires the original Cloudflare account or computer.

Consult `docs/developer-guide/REQUIREMENTS_STATUS.md`, `KNOWN_ISSUES.md` and `NEXT_STEPS.md` for detailed historical context; items superseded by the current source (such as absent member pages or the old UTC bill-date implementation) are not current missing-feature claims.
