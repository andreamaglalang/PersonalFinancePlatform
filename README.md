# OWLI — Personal Finance Platform

Android (Kotlin/Compose), ASP.NET Core MVC, Web API, EF Core SQL Server, and optional Hangfire background jobs.

**Current project handoff:** [Setup and running instructions](docs/handoff/README.md), [database initialization](docs/handoff/DATABASE.md), [known issues and remaining work](docs/handoff/ISSUES.md), [source consolidation](docs/handoff/SOURCE_MAP.md), and [verification](docs/handoff/VERIFICATION.md).

Development uses permanent branch `dev`, initialized from the latest approved documentation commit on `handoff/owli-20261125`. Start temporary task branches from synchronized `dev` and integrate verified work back into it. Preserve handoff as the original baseline; reserve `main` for explicitly approved stable releases. See [Git workflow](docs/DEVELOPMENT_WORKFLOW.md), [shared agent instructions](AGENTS.md), and [Claude Code instructions](CLAUDE.md). Older developer-guide and Hermes reports remain historical references; use the handoff guide for setup details.

Use a fresh local database, your own generated secrets and development certificate. Keep Hangfire disabled initially. No personal records, original machine configuration, database backup, deployment credentials or original certificate are needed. Build and offline checks do not contact the existing database or deployment.
