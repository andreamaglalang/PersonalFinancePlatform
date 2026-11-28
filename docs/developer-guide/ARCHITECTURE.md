# Project Architecture

> **Historical guide / status snapshot.** Do not treat the old blockers or next steps below as current execution instructions. Use [final handoff](../FINAL_HANDOFF.md) for release identity and reconciled limits, [developer setup](../DEVELOPER_SETUP.md) for local startup, and [production deployment](../../deployment/README.md) for receiving-team operations.

```
 Android app (Kotlin/Compose)            Administrator website (ASP.NET Core MVC)
   Room "owli-local.db" (offline-first)     Cookie session; API tokens kept server-side
            |  HTTPS + JWT bearer                      |  HTTPS + JWT bearer (BackendApi:BaseUrl)
            v                                          v
        ┌───────────────────────── PersonalFinance.Api (.NET 10) ─────────────────────────┐
        │ Controllers -> Infrastructure services -> EF Core ApplicationDbContext            │
        │ AuthTransportMiddleware / FinanceTransportMiddleware: HTTPS-only, no-store caching │
        │ JWT (15 min) + rotating refresh tokens (7 days)       Hangfire jobs (disabled)     │
        └──────────────┬───────────────────────────────────────────────┬──────────────────┘
                       v                                               v
         SQL Server (dev: LocalDB (localdb)\MSSQLLocalDB,         Frankfurter API
         database PersonalFinanceDb) + ASP.NET Identity tables    https://api.frankfurter.dev/v2/
```

## Solution layout

| Path | Contents |
| --- | --- |
| `PersonalFinancePlatform.slnx` | Solution: Api, Domain, Infrastructure, AdminMvc, FinanceTests, AdminProvisioning (other test projects are standalone) |
| `PersonalFinance.Api/` | `Program.cs`, `Authentication/` (JWT, middleware, rate limiting, `CurrentUser`), `Controllers/`, `Persistence/ApplicationDbContextFactory.cs` (EF design-time factory) |
| `PersonalFinance.Domain/` | Entities (`Transaction`, `Category`, `Budget`, `SavingsGoal`, `GoalContribution`, `RecurringBill`, `Notification`, `MonthlySummary`, …), enums, pure policies |
| `PersonalFinance.Infrastructure/` | `Persistence/ApplicationDbContext` + entity configurations, `Identity/`, `Authentication/` (sessions, refresh tokens), `Finance/` (services + contracts/DTOs), `Jobs/` (Hangfire), `Migrations/`, `DependencyInjection.cs` |
| `PersonalFinance.AdminMvc/` | Controllers (Account, Dashboard, Categories, Budgets, Reports), Razor views, `Services/` (typed API clients), `Authentication/`, `wwwroot/` |
| `android/` | Single `:app` module. `data/` (auth, remote, local/Room, mock), `domain/`, `ui/` (dashboard, transactions, categories, plan, reports, currency, account, auth, theme) |
| `tests/` | Standalone console test programs (see VERIFICATION.md) |
| `tools/PersonalFinance.AdminProvisioning/` | Guarded tool that creates a development administrator in LocalDB |
| `deployment/` | Publish script, placeholder-only env templates, offline validation evidence |
| `docs/` | Implementation/verification history, `elect3-demo/` runbooks, this developer guide |

## API endpoints (all under `/api`, HTTPS only)

| Area | Routes | Auth |
| --- | --- | --- |
| Auth | `POST auth/register`, `auth/login`, `auth/refresh`, `auth/logout`, `auth/logout-all`; `GET auth/me` | login/register/refresh rate-limited (20/min per IP) |
| Categories | CRUD `categories`; admin defaults `POST/PUT/DELETE categories/defaults[/{id}]` | user; defaults need Administrator |
| Transactions | CRUD `transactions` (idempotent by `ClientId`, FX snapshot, returns affected budgets) | user |
| Budgets | CRUD `budgets` (monthly per category, usage/status) | user |
| Savings goals | CRUD `savings-goals`, `{id}/cancel`, contributions CRUD | user, **needs migration 3** |
| Recurring bills | CRUD `recurring-bills`, `POST {id}/payments` | user, **needs migration 4** |
| Reports | `GET reports/monthly?month=YYYY-MM-01` | user |
| Notifications | `GET notifications` | user |
| Exchange rates | `GET exchange-rates/quote` | user |
| Dev jobs | `POST dev/jobs/{budget-alerts|monthly-summaries}/run` (no bill-reminder trigger exists) | Administrator, **Development only** (404 elsewhere) |

Every finance query is scoped to the authenticated user ID. The tests include cross-user isolation checks.
Administrators also see only their own finance data, because no endpoint returns platform-wide finance data.

## Data flow highlights

- **Android offline-first:** new expenses are written to Room first (`Pending`). A sync step posts them with a client
  `ClientId`; the server deduplicates by `(UserId, ClientId)`, so retries are safe. States: `Pending`, `Synced`, `Rejected`.
- **Currency:** transactions in a non-base currency get a Frankfurter rate snapshot, cached in the `ExchangeRates` table
  for `CurrencyPolicy:MaximumRateAgeDays` (default 7).
- **Jobs:** `FinanceJobs` creates `Notifications` rows (bill reminders, budget alerts) and `MonthlySummaries`. With
  Hangfire disabled, budget alerts and monthly summaries can run only via the Development-only
  `/api/dev/jobs/{job}/run` trigger. Bill reminders have no trigger, so they run only under Hangfire.
- **MVC:** server-rendered pages call the API with the administrator's bearer token, which never reaches the browser.
