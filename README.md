# Personal Finance and Budget Monitoring Platform

A .NET 10 backend for user-owned financial records, category budgets and savings goals. This is the original development project. The separate academic submission copy is not the collaboration repository.

## Status and safety

The repository contains implemented backend source and offline checks. Source presence is not proof of deployed functionality or of a compatible live database. No MVC views or Android project currently exist. Recurring-bill models and payment-service source exist, but recurring-bill CRUD and payment HTTP endpoints are not exposed; the payment service is not registered in dependency injection.

**Do not apply `20261107054048_AddBillPaymentReceiptsAndGuards`: it remains UNSAFE TO APPLY.** Do not use a blanket database-update command to initialize a clone. Database state has not been inspected during repository preparation. Starting the API or a database-capable tool requires separately approved configuration, schema and environment review.

The two High live-activation blockers remain open: independently verified environment/endpoint/physical-file binding, and runtime/launch integrity with authenticated external approval. `HumanApprovalAuthenticated = false`; `LiveExecutionAuthorized = false`. Offline checks and repository sharing do not grant live authority.

## Functionality

Implemented API source covers:

- Registration, login, signed JWT access tokens, rotating refresh sessions, logout and current-user retrieval.
- Personal categories and administrator-managed default categories.
- Transaction creation, filtering, updates and deletion, with exchange-rate snapshots and affected-budget responses.
- Monthly category budgets and calculated usage/status.
- Savings goals and contributions, retained request receipts, replay handling and version preconditions.

Pending work includes the MVC client, Android client, recurring-bill HTTP management, payment HTTP integration, dashboard/summary endpoints and notification delivery. Authentication recovery/profile-management endpoints are not currently exposed. Historical reports describe their own dates and must not be read as a single current completion claim.

## Stack and architecture

| Project | Responsibility |
| --- | --- |
| `PersonalFinance.Api` | ASP.NET Core controllers, JWT validation, transport and request handling |
| `PersonalFinance.Domain` | Financial entities, enumerations and pure policies |
| `PersonalFinance.Infrastructure` | EF Core SQL Server persistence, Identity, service implementations and contracts |
| `tests` | Standalone console checks, test doubles, offline policies and guarded SQL-test definitions |
| `tools/PersonalFinance.AdminProvisioning` | Explicit, separately approved development administrator provisioning |


The solution references API, Domain, Infrastructure, FinanceTests and AdminProvisioning. Other test/tool projects are standalone. The application uses ASP.NET Core Identity, EF Core SQL Server and the Frankfurter exchange-rate provider. Domain and service validation remain authoritative for all clients.

## Repository navigation

- [API controllers](PersonalFinance.Api/Controllers) — HTTP endpoints and request handling.
- [Domain policies](PersonalFinance.Domain/Policies) — financial rules and calculations.
- [Infrastructure services](PersonalFinance.Infrastructure/Finance) — financial operations and contracts.
- [EF Core migrations](PersonalFinance.Infrastructure/Migrations) — schema history; inclusion does not authorize execution.
- [Development administrator provisioning source](tools/PersonalFinance.AdminProvisioning) — guarded tool; do not run without separate approval.
- [Implementation reports](docs) — historical implementation and verification notes, not proof of current deployment readiness.
- [Finance tests](tests/PersonalFinance.FinanceTests) — test source; inspect test modes before execution.

Some internal preparation documents, operational tools and historical test projects are intentionally excluded from this public checkout. They are not required reading for ordinary source review, and should not be added merely to satisfy historical references.

## Prerequisites

- A .NET 10 SDK and Visual Studio/MSBuild version supporting .NET 10 and `.slnx`, or equivalent CLI tooling.
- Access to the packages declared in the project files. No package restore or dependency vulnerability check was performed during preparation.
- The local tool manifest pins `dotnet-ef` 10.0.13. Application package references are declared in each `.csproj`; they must not be changed merely to bypass restore failures.
- No `global.json` currently pins an SDK patch. Agree on a supported SDK with the team before adding one in a separate change.

Historical offline test modes described below did not require LocalDB, but their project source is excluded from this public checkout. Windows-specific operational tools are not portable database setup helpers. Local package-cache assumptions from internal verification do not constitute setup instructions for collaborators.

## Environment configuration and secrets

`appsettings.json` declares logging and JWT issuer/audience without a signing secret. `PersonalFinance.Api/appsettings.Development.json` is private local configuration and is intentionally absent from the public repository. A clean clone has no configured database connection or JWT signing secret. Missing database configuration must not trigger a fallback target or automatic initialization. Development certificate-trust settings are not production guidance.

For a separately approved local setup, configure `Jwt:SigningKeyBase64` in the API project's user-secret store, or supply `Jwt__SigningKeyBase64` externally. It must decode to at least 32 cryptographically random bytes. Obtain or generate the development value through an approved secret-management procedure; never place it in source, screenshots, issue text or browser/mobile code. The project's `UserSecretsId` is an identifier, not the signing secret.

For separately authorized setup, supply `ConnectionStrings:DefaultConnection` through the API user-secret store or external `ConnectionStrings__DefaultConnection` configuration. Administrator provisioning loads base JSON, optional development JSON, API user secrets and environment variables in that order; it rejects missing or whitespace connection configuration and remains restricted to its existing approved development target. Supplying configuration does not grant authority to connect or provision. Do not share personal secret files or replace machine-bound security guards with permissive fallbacks.

## HTTPS and safe local work

The source launch profile declares HTTPS on `https://localhost:7027` and HTTP on `http://localhost:5015`. Authentication and finance middleware reject HTTP. For a later authorized API startup, establish a trusted development HTTPS certificate and approved database/schema configuration first. Never bypass certificate validation or send credentials to HTTP as a workaround.

Safe initial work consists of reading contracts, implementing client code against synthetic responses and reviewing source. No API startup, provisioning, migration, build, restore or test execution was performed during this preparation. Those activities need the applicable separate approval before execution in this project workflow.

## Testing and historical verification

The public checkout includes test source under [`tests`](tests), including finance, authentication, API security, category uniqueness, schema validation and administrator provisioning checks. Review each entry point and its dependencies before executing it. In particular, `PersonalFinance.AuthenticationTests --sql` is database-capable. Do not run SQL integration modes, provisioning tools, migrations or operational wrappers as ordinary onboarding steps.

Schema/configuration checks use synthetic, credential-free inputs and do not require the private development configuration file. Configuration inspection does not open a database connection.

Historical, user-reported offline checks from **internal projects excluded from this public checkout** were:

| Historical test project (not included) | Reported result |
| --- | --- |
| `PersonalFinance.BillPaymentSqlTests` | 245 passing checks |
| `PersonalFinance.BillPaymentSqlPreflight` | 84 passing checks |
| `PersonalFinance.BillPaymentSqlLivePreflight` | 719 passed, 0 failed, 0 skipped |

The separately reported total is **1,048** checks. Earlier 715 and 458 preflight counts are included within 719, not additional checks. These results were not independently reproduced during repository preparation and do not establish current build, test or live database readiness. The internal preflight report and database-verification tooling are not distributed in this public checkout; their source-integrity issues must not be bypassed by refreshing pinned hashes.

No build, restore, test execution or live database inspection was performed as part of public repository preparation. Obtain separate authorization and review the specific test mode before running commands that may execute code or affect persistent state.

## MVC and Android integration

The MVC contributor should use the authorized HTTP API and DTO contracts, not direct database writes or bypasses around ownership checks. Registration does not log in automatically. Access tokens expire after 15 minutes; refresh tokens rotate and must not be reused concurrently. Savings mutations require correct resource ETags. Inspect the staged API controllers and request/response contracts for routes and error behavior. For an MVC client, keep refresh tokens server-side and use approved secure storage; do not expose them to browser scripts.

Android architecture has been inspected and proposed, not implemented. Future Kotlin work should begin with fake data and separately reviewed MVVM, Compose, Coroutines, Retrofit and Room boundaries. Do not assume offline financial-write synchronization or recurring-bill endpoints already exist.

## Contribution workflow and academic integrity

Do not stage the whole directory blindly. Stage only individually reviewed files, check the staged diff for credentials and private operational material, and preserve byte-sensitive migration and integrity-related files. Some internal tools, historical tests and preparation records are intentionally excluded; their absence does not authorize copying them into the public repository. Excluded local files must not be deleted as an automatic cleanup step.

The repository has been initialized on `main` and an initial public candidate set has been staged for review. At the time of this README revision, no initial commit or GitHub push has been performed. Source-control publication does not authorize deployment or change either live-activation blocker.
