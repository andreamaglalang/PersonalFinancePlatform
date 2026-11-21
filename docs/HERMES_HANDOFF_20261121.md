# OWLI — Hermes Handoff (2026-11-21)

This one-page summary has been superseded by the full handoff set in **[docs/hermes-handoff/](hermes-handoff/README.md)**.
Start there. If anything here disagrees with that folder, the folder wins.

Quick start:

```
git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git switch integration/hermes-handoff-20261121
```

Then read `docs/hermes-handoff/README.md` → `WINDOWS_SETUP.md` → `VERIFICATION.md` → `DATABASE_SETUP.md`.

Critical reminders:
- Never apply migration 4 (`20261107054048_AddBillPaymentReceiptsAndGuards`) or run an untargeted
  `dotnet ef database update` without the owner's approval.
- Keep `Hangfire__Enabled=false`.
- The Android debug certificate belongs to the original PC; replace it with your own public dev certificate.
- The repository is public: never commit secrets, private keys, database files or real financial data.
- A preservation branch with internal tooling was briefly public on 2026-11-21 and then deleted. Deletion does not
  guarantee removal from caches or clones (see `docs/hermes-handoff/KNOWN_ISSUES.md`).
