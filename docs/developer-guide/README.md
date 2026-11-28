# OWLI Developer Guide

> **Historical guide / status snapshot.** Do not treat the old blockers or next steps below as current execution instructions. Use [final handoff](../FINAL_HANDOFF.md) for release identity and reconciled limits, [developer setup](../DEVELOPER_SETUP.md) for local startup, and [production deployment](../../deployment/README.md) for receiving-team operations.

OWLI is a Personal Finance and Budget Monitoring Platform made of three applications that share one backend:

| App | Project | Who uses it |
| --- | --- | --- |
| Web API (ASP.NET Core, .NET 10) | `PersonalFinance.Api` + `PersonalFinance.Domain` + `PersonalFinance.Infrastructure` | Android app and MVC site |
| Administrator website (ASP.NET Core MVC) | `PersonalFinance.AdminMvc` | Administrators |
| Android app (Kotlin, Jetpack Compose) | `android/` | Members |

Keep **OWLI** as the product name.

## 1. Clone

```powershell
git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git switch dev
```

Follow the [Git workflow](../DEVELOPMENT_WORKFLOW.md): `dev` is the permanent
development/integration branch, initialized from `handoff/owli-20261125`. Start
temporary task branches from synchronized `dev` and integrate verified changes
back into `dev` through review. Preserve handoff as the original baseline.
`main` is reserved for stable releases with explicit owner approval. Never force-push.
Use the [current handoff](../handoff/README.md) for current setup and verification status.

## 2. Read in this order

1. [WINDOWS_SETUP.md](WINDOWS_SETUP.md): install the tools.
2. [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit together.
3. [VERIFICATION.md](VERIFICATION.md) (verification results): run the offline build and tests first. **No database is needed.**
4. [DATABASE_SETUP.md](DATABASE_SETUP.md): **read fully before touching SQL Server.** Contains migration safety rules.
5. [BACKEND_SETUP.md](BACKEND_SETUP.md), then [MVC_SETUP.md](MVC_SETUP.md), then [ANDROID_SETUP.md](ANDROID_SETUP.md).
6. [REQUIREMENTS_STATUS.md](REQUIREMENTS_STATUS.md) (implementation status), [KNOWN_ISSUES.md](KNOWN_ISSUES.md), [NEXT_STEPS.md](NEXT_STEPS.md).
7. [DEPLOYMENT.md](DEPLOYMENT.md): optional; Cloudflare is **not** needed to run OWLI locally.

Sanitized configuration examples are in [config-examples/](config-examples/).

## 3. Golden rules

- **Never commit secrets:** `appsettings.Development.json`, user-secrets, `.env` files with values,
  `android/local.properties`, `.pfx`/`.key` files, Cloudflare tokens, `.mdf`/`.ldf`/`.bak` files or real financial data.
  This repository is **public**.
- **Never run `dotnet ef database update` without a target migration**, and never apply migration 4
  (`20261107054048_AddBillPaymentReceiptsAndGuards`) without the owner's approval. See DATABASE_SETUP.md.
- **Keep Hangfire disabled** (`Hangfire__Enabled=false`) unless the owner approves installing the Hangfire schema.
- **Do not weaken TLS.** No certificate-validation bypasses and no cleartext HTTP in the Android app.
- Offline tests passing does **not** mean a feature works end to end. Record what you actually verified.

## 4. Historical integration branch map (21 November 2026)

This records earlier consolidation, not current development instructions.
The [Git workflow](../DEVELOPMENT_WORKFLOW.md) defines current branch responsibilities.

| Branch | Purpose | Status |
| --- | --- | --- |
| `integration/owli-platform` | Earlier integration of Android, MVC, API, reports, FX, Hangfire and provisioning work | historical; use `dev` for new development |
| `deployment/owli-server` | Backend + MVC integration used for the earlier deployment candidate | merged into integration |
| `feature/backend-hangfire-demo`, `feature/backend-fx-quote`, `feature/reports-monthly-handoff` | Backend feature lines | merged |
| `feature/mvc-admin-dashboard`, `review/mvc-admin-dashboard-c77cd54` | MVC feature lines | merged |
| `feature/android-lavender-midnight-ui` (+ `-budgets-ui`, `-currency-ui`, `-financial-reports`, `-fx-quote`, `-transaction-list-mock`) | Android feature lines | merged |
| `feature/admin-provisioning-username` | Separate admin username in the provisioning tool | merged |
| `wip/android-worktree-snapshot-20261121` | Preserved unreviewed edits and a stale backend copy | **do not merge** (see KNOWN_ISSUES.md) |

`docs/DEVELOPER_SETUP.md` is the short setup entry point and links here. Android coding and design standards are in
[ANDROID_DEVELOPMENT_STANDARDS.md](ANDROID_DEVELOPMENT_STANDARDS.md).
