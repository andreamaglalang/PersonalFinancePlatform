# Initialize a new development database

> **Historical LocalDB procedure.** Current fresh-SQL initialization and existing-data upgrade instructions are in [production guide §5](../../deployment/README.md#5-database-backup-and-migration); local prerequisites are in [developer setup](../DEVELOPER_SETUP.md). Later disposable SQL evidence supersedes the blanket not-verified statements below; production upgrades still require approval/rehearsal.

SQL Server is required for runtime persistence. The offline checks use synthetic models/test doubles and do not prove SQL locking or trigger behavior. This handoff did not connect to, migrate, back up, or seed the existing database.

Use a NEW LocalDB installation on your own computer and an empty `PersonalFinanceDb` database. The name is required only by the administrator provisioning guard. Do not run these instructions on the owner's computer or against a shared server. Starting the API does not automatically migrate or seed the database.

## Migration inventory

| Target | Purpose |
| --- | --- |
| `20261031023901_InitialFinanceSchema` | Identity and finance tables, User and Administrator role definitions |
| `20261031115500_AddCategoryNameUniqueness` | Normalized category names and uniqueness |
| `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions` | Savings receipt ledger and row versions |
| `20261107054048_AddBillPaymentReceiptsAndGuards` | Bill payment receipts, anchor dates and financial triggers |
| `20261123100000_UsePhilippineLocalBillPaymentDates` | Philippine calendar date checks and bill-payment trigger correction |

The runtime model requires all five. Historical instructions stopping after migration 2 leave savings and recurring bills incompatible. Migration 4 deliberately refuses existing recurring bills or linked transactions; migration 5 refuses inconsistent historical payment dates. Never remove these checks or rewrite old financial history to make an existing database migrate.

## Fresh-empty procedure (new machine only)

1. Configure `ConnectionStrings__DefaultConnection` using the handoff environment template; inspect it yourself and confirm LocalDB and the empty database on YOUR machine. Leave Hangfire disabled.
2. Restore the local EF tool and generate SQL without connecting:

   ```powershell
   dotnet tool restore
   dotnet ef migrations script 0 20261123100000_UsePhilippineLocalBillPaymentDates --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api --output fresh-development.sql
   ```

3. Review the SQL, including triggers and guards. Keep the generated file local. On the confirmed empty target only, apply the explicit full target:

   ```powershell
   dotnet ef database update 20261123100000_UsePhilippineLocalBillPaymentDates --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api
   ```

4. Verify `__EFMigrationsHistory` contains all five IDs, then start the API and register a synthetic member. Create your own categories, transactions, budgets, savings goals and bills through the API/MVC; there is no mandatory personal-data fixture. Administrator creation is separate and interactive.

Execution of this fresh SQL path has NOT been verified in this handoff. If a guard or SQL command fails, stop and preserve the error; do not suppress it. A database maintainer must resolve the cause on the disposable target. Existing-database upgrades require backup, reconciliation, explicit target verification, and a separate migration review. The old migration-4 warnings still apply to nonempty databases.

Hangfire owns a different catalog (`OWLI_Hangfire`); its tables are not created by these EF migrations. Keep it disabled until its own schema is installed and validated. SQL-capable tests under `tests/*Sql*` and `PersonalFinance.HangfireTests` are excluded from the default runner; some require dated, machine-bound evidence or explicitly named disposable targets. Do not run them against your application database.
