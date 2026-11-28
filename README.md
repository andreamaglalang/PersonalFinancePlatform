# OWLI — Personal Finance Platform

Android (Kotlin/Compose), ASP.NET Core MVC, Web API, EF Core SQL Server, and an independent optional Hangfire console worker.

**Start here:** [Persistent local setup and exact startup commands](docs/DEVELOPER_SETUP.md). After one-time private setup, run `dotnet run --project PersonalFinance.Api` and `dotnet run --project PersonalFinance.AdminMvc` in separate terminals. No per-terminal configuration exports are needed.

**Development closeout:** [Final handoff and receiving-team checklist](docs/FINAL_HANDOFF.md).
Final implementation source: `ec73858ef54a7323523f32989942220ca53461b1` on `dev`; PRs #33–#38 merged;
[hosted CI passed](https://github.com/andreamaglalang/PersonalFinancePlatform/actions/runs/46097900708).
The owner reports personally testing the app and website. Production deployment remains the receiving team's responsibility.

- [Current developer setup and API/MVC startup](docs/DEVELOPER_SETUP.md)
- [Production deployment, SQL migration/backup/rollback, worker, Cloudflare and APK signing](deployment/README.md)
- [Notification testing guide](docs/NOTIFICATION_TESTING.md)
- [Verification evidence and final status](docs/FINAL_HANDOFF.md#verification-and-owner-acceptance)
- [E2E acceptance](docs/audits/OWLI_E2E_Acceptance.md) and [academic traceability](docs/audits/OWLI_Academic_Traceability.md)

The [original handoff](docs/handoff/README.md), [issue history](docs/handoff/ISSUES.md) and
[source consolidation](docs/handoff/SOURCE_MAP.md) are retained as dated references.

Development uses permanent branch `dev`, initialized from the latest approved documentation commit on `handoff/owli-20261125`. Start temporary task branches from synchronized `dev` and integrate verified work back into it. Preserve handoff as the original baseline; reserve `main` for explicitly approved stable releases. See [Git workflow](docs/DEVELOPMENT_WORKFLOW.md), [shared agent instructions](AGENTS.md), and [Claude Code instructions](CLAUDE.md). Older developer-guide and Hermes reports remain historical references; use the current development setup for startup details.

Use an approved local testing database, persistent private user secrets and your own trusted development certificate. Preserve existing credentials and databases. The API never hosts Hangfire; the worker is disabled by default. Its explicit development test profile validates disposable storage before activation. No personal records, original machine configuration, database backup, deployment credentials or original certificate are needed. Build and offline checks do not contact the existing database or deployment.
