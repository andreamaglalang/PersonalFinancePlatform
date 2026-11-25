# OWLI — Personal Finance Platform

Android (Kotlin/Compose), ASP.NET Core MVC, Web API, EF Core SQL Server, and optional Hangfire background jobs.

**Current project handoff:** [Setup and running instructions](docs/handoff/README.md), [database initialization](docs/handoff/DATABASE.md), [known issues and remaining work](docs/handoff/ISSUES.md), [source consolidation](docs/handoff/SOURCE_MAP.md), and [verification](docs/handoff/VERIFICATION.md).

Clone branch `handoff/owli-20261125`. The handoff includes current uncommitted Android/MVC/backend work reconciled on an isolated branch. Older developer-guide and Hermes reports are retained as historical references; use the handoff guide for current setup and status.

Use a fresh local database, your own generated secrets and development certificate. Keep Hangfire disabled initially. No personal records, original machine configuration, database backup, deployment credentials or original certificate are needed. Build and offline checks do not contact the existing database or deployment.
