# Sanitized financial notification evidence

> Source: OWLI dev 1163305; branch feature/financial-notifications; docs/NOTIFICATIONS.md
> Collected: 2026-11-28
> Published: 2026-11-28

The existing Notifications table (free Type string, unique UserId+DeduplicationKey) was reused with no migration.

API financial services now write these notifications in the same save as the change:
- savings milestones (highest new milestone only) and goal completion (once per goal);
- bill payment confirmations;
- password-change notices.

The existing daily reminder job adds one overdue notice per oldest unpaid occurrence. Android shows local
"all caught up" feedback after a confirmed offline sync.

Verified:
- the audit API with the disposable runtime catalog (SQL rows, replay, concurrency, isolation);
- the real worker on disposable Hangfire storage;
- an Android → MVC → Android read-state round trip on the emulator and in Chrome.

Production was not verified.
