# OWLI — Personal Finance Platform

Android (Kotlin/Compose), ASP.NET Core MVC, Web API, EF Core SQL Server, and an independent optional Hangfire console worker.

**Start here:** [Persistent local setup and exact startup commands](docs/DEVELOPER_SETUP.md). After one-time private setup, run `dotnet run --project PersonalFinance.Api` and `dotnet run --project PersonalFinance.AdminMvc` in separate terminals. No per-terminal configuration exports are needed.

**Project handoff:** [Historical handoff and references](docs/handoff/README.md), [database initialization](docs/handoff/DATABASE.md), [known issues and remaining work](docs/handoff/ISSUES.md), [source consolidation](docs/handoff/SOURCE_MAP.md), and [verification](docs/handoff/VERIFICATION.md).

Development uses permanent branch `dev`, initialized from the latest approved documentation commit on `handoff/owli-20261125`. Start temporary task branches from synchronized `dev` and integrate verified work back into it. Preserve handoff as the original baseline; reserve `main` for explicitly approved stable releases. See [Git workflow](docs/DEVELOPMENT_WORKFLOW.md), [shared agent instructions](AGENTS.md), and [Claude Code instructions](CLAUDE.md). Older developer-guide and Hermes reports remain historical references; use the current development setup for startup details.

Use an approved local testing database, persistent private user secrets and your own trusted development certificate. Preserve existing credentials and databases. The API never hosts Hangfire; the worker is disabled by default. Its explicit development test profile validates disposable storage before activation. No personal records, original machine configuration, database backup, deployment credentials or original certificate are needed. Build and offline checks do not contact the existing database or deployment.
