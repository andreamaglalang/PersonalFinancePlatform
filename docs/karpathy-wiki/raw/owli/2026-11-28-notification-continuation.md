# Financial notification continuation evidence — 28 November 2026

Source kind: sanitized implementation and test observation. Task branch:
`feature/financial-notifications`, continuing Claude checkpoint `f7640d4` from `dev` `16990d0`.
This supplements the earlier immutable notification source. It does not repeat its unsupported
method-level Android claims: that checkpoint had an instrumentation brace error. The corrected
methods were rebuilt and actually executed during this continuation.

Confirmed and corrected: legacy goal metadata/downward changes could announce an old threshold;
overdue snapshots could become paid/inactive before insertion; client shells lacked unread totals;
Android unread paging skipped a row after a read mutation; MVC phone padding let icons obscure text.
The implementation now requires an upward authoritative crossing, rechecks overdue eligibility under
existing user/bill locks, refreshes account-owned counts, resets read-mutated pages and preserves
mobile icon space. No new schema, migration, dependency or push channel.

Verified: 460 finance, 34 authentication, 36 security, 15 category, 65 schema and 40 provisioning
checks; MVC regression harness; 19 real SQL checks including notification-save rollback failures;
14 real independent-worker lifecycle checks; 683 Android debug and 689 release unit tests.
After identifying and gracefully stopping the existing test worker, zero registered servers was
verified and all 11 event HTTPS/API/SQL scenarios were rerun. The actual Android milestone method
passed with the worker stopped. Actual Android↔MVC creation/read/count round trips and offline
caught-up/empty-retry feedback passed without clearing Room or bypassing TLS.

The restarted notification-branch worker produced upcoming, overdue, threshold, exceeded and
monthly-summary notices for a fresh synthetic account. Repeat three-job triggers left its inbox
at 16 records. All eight kinds were displayed on actual Android in light/dark and MVC at
360/768/1280px; Android mark-all synchronized zero unread to MVC/API, and the second
member had an empty inbox in both interfaces. Synthetic messages/IDs, private credentials/configuration, machine paths, raw logs
and screenshots remain private. For final presentation/mark-all/CI results and independent steps,
consult the maintained acceptance record and manual guide below.

Sources: `docs/NOTIFICATIONS.md`, `docs/NOTIFICATION_TESTING.md`,
`docs/audits/OWLI_E2E_Acceptance.md` (dated continuation),
`docs/audits/OWLI_Academic_Traceability.md` (N1–N8).
Limitations: pull on open/refresh/navigation; no referenced-record links; physical hardware,
spoken screen-reader output and production runtime are not certified by these checks.
