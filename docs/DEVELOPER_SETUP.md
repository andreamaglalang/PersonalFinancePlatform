# OWLI — Development Setup

This page is a short setup entry point. Use the [current handoff](handoff/README.md)
for current setup/status and the [Git workflow](DEVELOPMENT_WORKFLOW.md) for branch policy.
The [developer guide](developer-guide/README.md) remains a technical reference; older
implementation and verification claims are historical.

Quick start:

```
git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git switch dev
```

Then read [README](developer-guide/README.md) → [WINDOWS_SETUP](developer-guide/WINDOWS_SETUP.md) →
[VERIFICATION](developer-guide/VERIFICATION.md) → [DATABASE_SETUP](developer-guide/DATABASE_SETUP.md).

Critical reminders:
- Never apply migration 4 (`20261107054048_AddBillPaymentReceiptsAndGuards`) or run an untargeted
  `dotnet ef database update` without the project owner's approval.
- Keep `Hangfire__Enabled=false`.
- The Android debug certificate belongs to the original development PC; replace it with your own public dev certificate
  ([ANDROID_SETUP](developer-guide/ANDROID_SETUP.md)).
- The repository is public: never commit secrets, private keys, database files or real financial data.
- A preservation branch containing internal tooling was briefly public on 2026-11-21 and then deleted. Deletion does
  not guarantee removal from caches or clones (see [KNOWN_ISSUES](developer-guide/KNOWN_ISSUES.md)).
