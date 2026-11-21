# Database Setup (SQL Server LocalDB + EF Core 10)

> **Read all of this before running any `dotnet ef` command against a server.**
> Nothing in this handoff applied a migration or touched the original database.

## What is in the repository

| Item | Location |
| --- | --- |
| DbContext | `PersonalFinance.Infrastructure/Persistence/ApplicationDbContext.cs` (Identity + finance sets, `int` keys) |
| Entity configurations (Fluent API: keys, relationships, indexes, check constraints, triggers) | `PersonalFinance.Infrastructure/Persistence/` configuration classes |
| Entities | `PersonalFinance.Domain/Entities/`, `PersonalFinance.Infrastructure/Identity/` |
| Migrations + model snapshot | `PersonalFinance.Infrastructure/Migrations/` (`ApplicationDbContextModelSnapshot.cs`, `FinanceSchemaGuards.cs`) |
| Design-time factory | `PersonalFinance.Api/Persistence/ApplicationDbContextFactory.cs` (uses API configuration; opens no connection by itself) |
| Seed data | Migration 1 seeds the `User` and `Administrator` roles. Administrators come from the provisioning tool (BACKEND_SETUP.md) |
| Demo data | `docs/elect3-demo/Seed-OwliPresentationData.ps1`: creates demo records **through the API** (prompts for credentials) |
| Migration reviews | `docs/elect3-demo/MIGRATION3_PLAN.md`, `docs/elect3-demo/MIGRATION4_REVIEW.md`, `docs/Stage3_Step12B1_CategoryUniqueness_Report.md`, `docs/MonthlyBudgets_Implementation_Report.md` |

The original machine-pinned SQL guard scripts referenced by MIGRATION3_PLAN.md are intentionally **not** in this
branch. They are internal tooling locked to the original laptop's database and would refuse to run elsewhere.

## Migrations (order, content, safety)

| # | Migration id | Adds | Status on the original database | Safety status |
| --- | --- | --- | --- | --- |
| 1 | `20261031023901_InitialFinanceSchema` | All Identity tables, Categories, Transactions, Budgets, SavingsGoals, GoalContributions, RecurringBills, ExchangeRates, Notifications, MonthlySummaries, RefreshTokens; seeds roles | Applied (documented) | Approved |
| 2 | `20261031115500_AddCategoryNameUniqueness` | `NormalizedName` + uniqueness for categories | Applied (documented) | Approved |
| 3 | `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions` | `RowVersion` on SavingsGoals and GoalContributions; `GoalContributionRequests` ledger | **Not applied** | Additive; reviewed but **not approved**; never executed against SQL Server |
| 4 | `20261107054048_AddBillPaymentReceiptsAndGuards` | `RecurringBills.AnchorDate` (NOT NULL, no default), `BillPaymentReceipts`, 3 new triggers, extends `TR_Transactions_ProtectBillPayments` | **Not applied** | **UNSAFE TO APPLY** without owner approval. Never executed; requires an empty RecurringBills table (guard 51034) |

"Documented" means the state comes from the project's verification records (deployment validation and the demo
prerequisites). **The applied-migration history of the original database was not re-queried during this handoff,
because LocalDB was stopped and starting it was not authorized. Treat it as unverified.**

### Why this matters at runtime

The current EF model maps the columns from migrations 3 and 4. On a database with only migrations 1–2:

| Works | Fails (HTTP 500) |
| --- | --- |
| Auth, categories, transactions, budgets, reports, exchange-rate quotes, notifications, budget-alert and monthly-summary jobs | Every `/api/savings-goals` endpoint (needs migration 3); every `/api/recurring-bills` endpoint and bill payment (needs migration 4) |

## Procedure: fresh, isolated development database on the new PC

The new PC's LocalDB is a separate instance, so creating `PersonalFinanceDb` there cannot affect the original
database. The name must be exactly `PersonalFinanceDb`, because the provisioning tool refuses any other name.

1. **Configure the connection** (BACKEND_SETUP.md, user-secrets). Confirm the target:
   `sqllocaldb info MSSQLLocalDB`
2. **Generate and review the SQL first.** This opens no connection:
   ```powershell
   dotnet tool restore
   dotnet ef migrations script 0 20261031115500_AddCategoryNameUniqueness --idempotent `
     --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api -o $env:TEMP\owli_mig1-2.sql
   ```
   Read the script. Confirm it only creates OWLI objects and seeds roles.
3. **Apply only migrations 1–2 (approved).** Always name the target migration:
   ```powershell
   dotnet ef database update 20261031115500_AddCategoryNameUniqueness `
     --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api
   ```
   Never run `dotnet ef database update` without a target: that would also apply migrations 3 and 4.
4. **Optional, owner approval required: migration 3** (savings goals). On a fresh, empty database the main risks in
   MIGRATION3_PLAN.md (locks, existing rows) do not apply, but it has still never been executed on SQL Server.
   If approved: script `20261031115500_AddCategoryNameUniqueness 20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`,
   review it, then `database update 20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`.
5. **Migration 4: do not apply** until the owner approves. Before that, the review recommends running the bill-payment
   SQL harness against a disposable database and fixing the UTC "today" issue (KNOWN_ISSUES.md).
6. **Read-only check:**
   `sqlcmd -S "(localdb)\MSSQLLocalDB" -d PersonalFinanceDb -E -Q "SELECT MigrationId FROM __EFMigrationsHistory ORDER BY MigrationId"`
7. Create an administrator (BACKEND_SETUP.md) and, if wanted, demo data with `Seed-OwliPresentationData.ps1`.

Do not use `dotnet ef migrations list` against a database you must not touch: it connects to query the history.

## Hangfire schema

Hangfire never creates its tables (`PrepareSchemaIfNecessary = false`). There is no approved Hangfire schema script
in the repository. Keep `Hangfire:Enabled=false`.

## Existing data on the original PC (private, owner decision)

The only OWLI database is the original laptop's LocalDB `PersonalFinanceDb`. It holds development accounts
(including administrators), demo and presentation records, and transactions synced from the Android app. That data
exists only there and in the owner's private `.bak` backups. **It is not in GitHub and was not exported.**
Nothing in it is required to build or run OWLI. A fresh database plus the seed script is enough for development.

If the owner wants to carry the data over, it is a separate, private procedure that needs the owner's approval:

1. On the original PC, with the API and MVC stopped: `BACKUP DATABASE PersonalFinanceDb TO DISK = N'<private path>\PersonalFinanceDb_transfer.bak' WITH COPY_ONLY, CHECKSUM` then `RESTORE VERIFYONLY ... WITH CHECKSUM`; record the SHA-256.
2. Move the `.bak` by private means (USB or a private drive). **Never via GitHub, chat or email.**
3. On the new PC, verify the SHA-256, then restore under the name `PersonalFinanceDb` with `RESTORE DATABASE ... WITH MOVE ...` to the new LocalDB's data folder.
4. Accounts and passwords come along, so treat the file as personal data.

Never commit `.mdf`, `.ldf`, `.bak` or exported financial data.
