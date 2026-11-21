# OWLI Developer Guide

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
git switch integration/owli-platform
```

Work on the Integration Branch `integration/owli-platform`, or on a branch created from it. An earlier integration
branch with the same history up to this branch's creation is kept on GitHub for reference only; do not develop on it.
**`main` is still the initial
commit. Never push to it or force-push anywhere.** Merging into `main` needs the owner's review via a pull request.

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

## 4. Branch map (verified on GitHub)

| Branch | Purpose | Status |
| --- | --- | --- |
| `integration/owli-platform` | **Integration Branch: use this.** All Android, MVC, API, reports, FX, Hangfire and provisioning work merged | current |
| `deployment/owli-server` | Backend + MVC integration used for the earlier deployment candidate | merged into integration |
| `feature/backend-hangfire-demo`, `feature/backend-fx-quote`, `feature/reports-monthly-handoff` | Backend feature lines | merged |
| `feature/mvc-admin-dashboard`, `review/mvc-admin-dashboard-c77cd54` | MVC feature lines | merged |
| `feature/android-lavender-midnight-ui` (+ `-budgets-ui`, `-currency-ui`, `-financial-reports`, `-fx-quote`, `-transaction-list-mock`) | Android feature lines | merged |
| `feature/admin-provisioning-username` | Separate admin username in the provisioning tool | merged |
| `wip/android-worktree-snapshot-20261121` | Preserved unreviewed edits and a stale backend copy | **do not merge** (see KNOWN_ISSUES.md) |

`docs/DEVELOPER_SETUP.md` is the short setup entry point and links here. Android coding and design standards are in
[ANDROID_DEVELOPMENT_STANDARDS.md](ANDROID_DEVELOPMENT_STANDARDS.md).
