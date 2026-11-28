# Next Steps (historical priorities)

> **Historical guide / status snapshot.** Do not treat the old blockers or next steps below as current execution instructions. Use [final handoff](../FINAL_HANDOFF.md) for release identity and reconciled limits, [developer setup](../DEVELOPER_SETUP.md) for local startup, and [production deployment](../../deployment/README.md) for receiving-team operations.

These priorities and test counts describe the earlier integration. Use the
[current handoff issues](../handoff/ISSUES.md) for current status and the
[Git workflow](../DEVELOPMENT_WORKFLOW.md) for all new development planning.

## Before Monday's presentation

1. **Toolchain check (no database).** Clone, `dotnet tool restore`, run VERIFICATION.md. Expect 11/11 builds, 580
   numbered checks and 523 Android unit tests passing. Report any difference before changing code.
2. **Mock Demo safety net.** Build and install the debug APK. Confirm Mock Demo works with no server. This is the
   fallback if anything below is blocked.
3. **Fresh LocalDB with approved migrations 1–2 only** (DATABASE_SETUP.md). Generate and review the script first.
   Ask the owner before migration 3. **Do not apply migration 4.**
4. **API + admin account.** Configure user-secrets, run the API on `https://localhost:7027`, create the administrator
   with the provisioning tool (owner present), and optionally seed demo data with
   `docs/elect3-demo/Seed-OwliPresentationData.ps1`.
5. **MVC.** `BackendApi__BaseUrl=https://localhost:7027`. Verify login, default categories, budgets and the monthly report
   in a browser. Record what you verified.
6. **Android live sync.** Replace the debug certificate (ANDROID_SETUP.md), start the API in phone mode, run
   `adb reverse tcp:7027 tcp:7027`, sign in, record an expense offline, reconnect, and confirm it appears via the API/MVC.
7. **Demo script.** Present savings goals, recurring bills, bill reminders and Hangfire scheduling as
   "implemented and offline-tested, blocked on schema approval" (REQUIREMENTS_STATUS.md). Do not claim them as working.

## After the presentation

8. Owner decision on migration 3, then migration 4. Fix the UTC-date issue with a **new** migration and service change
   before using bill payments. Run the bill-payment SQL harness on a disposable database.
9. Android: wire Savings Goals to `/api/savings-goals` and Bills to `/api/recurring-bills` once the schema allows.
   Consider `/api/reports/monthly` for server-consistent reports. Add a launcher icon.
10. API + MVC: add Administrator endpoints (user list, cross-user/platform summaries) if ELECT3 budget administration
    and reporting must cover all users. Then extend the MVC pages.
11. Hangfire: owner-approved schema installation script, then enable it in Development only and observe the 3 jobs.
    Consider a dev trigger for bill reminders.
12. Keep development documentation current. Integrate completed, verified task
    branches into `dev`; propose a `dev` to `main` release only after final
    verification and explicit owner approval.

Work on short-lived branches from synchronized `dev`. Push with normal pushes only.
