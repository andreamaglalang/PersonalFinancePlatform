# Phase 3 Database Implementation Report

Implemented on October 31, 2026, using the supplied implementation-ready specification and the approved Phase 3 instructions.

The database foundation is implemented. The initial migration and inspection SQL are generated. No migration was applied, no SQL Server connection was opened, and no live database objects or data were changed.

## Packages and architecture

Direct package additions, all stable 10.0.13:

| Project | Package | Version |
| --- | --- | --- |
| Infrastructure | Microsoft.EntityFrameworkCore.SqlServer | 10.0.13 |
| Infrastructure | Microsoft.AspNetCore.Identity.EntityFrameworkCore | 10.0.13 |
| API | Microsoft.EntityFrameworkCore.Design, PrivateAssets=all | 10.0.13 |

The existing Microsoft.AspNetCore.OpenApi 10.0.13 reference is preserved. Resolved EF Core, Abstractions, Analyzers, Relational, and SQL Server packages are aligned at 10.0.13.

Project references remain API → Infrastructure and Domain; Infrastructure → Domain. Domain has no EF Core or Identity dependency. The solution file and Domain project file were not changed. The standalone validation console project references API and is intentionally outside the three-project solution; it adds no test framework packages.

## Entities and relationships

ApplicationDbContext derives from IdentityDbContext<ApplicationUser, IdentityRole<int>, int>. ApplicationUser extends AspNetUsers with required FullName, PHP-initialized BaseCurrency, and CreatedAtUtc. There is no separate Users table or custom password hashing.

Identity services use AddIdentityCore, AddRoles, and EF stores with RequireUniqueEmail=true. Email and NormalizedEmail are nonnullable and nonblank. NormalizedEmail uses explicit Latin1_General_100_CI_AS collation and an unfiltered unique EmailIndex, preventing nullable-email exceptions or case variants from bypassing uniqueness. Standard Identity tables remain; framework-owned Identity relationships retain their defaults. Composite login/token key fields are bounded to 128 characters for SQL Server key widths.

The migration seeds only User and Administrator role definitions with stable integer IDs. No users or administrator credentials are seeded. Registration and authentication endpoints are not implemented.

Business tables: Categories, Transactions, Budgets, RecurringBills, SavingsGoals, GoalContributions, ExchangeRates, Notifications, MonthlySummaries. RefreshTokens supplies the additional Identity persistence foundation.

All 13 application foreign keys use DeleteBehavior.NoAction:

- AspNetUsers → Categories (nullable ownership).
- AspNetUsers → Transactions, Budgets, RecurringBills, SavingsGoals, Notifications, MonthlySummaries, RefreshTokens (required ownership).
- Categories → Transactions, Budgets, RecurringBills.
- RecurringBills → Transactions (optional BillId).
- SavingsGoals → GoalContributions.

GoalContributions has no direct UserId. ExchangeRates has no foreign keys. Notification ReferenceType/ReferenceId remain logical references with paired nullability.

## Indexes and constraints

All eight requested unique indexes are present:

- Transactions(UserId, ClientId).
- Transactions(BillId, BillDueDate), filtered where both values are nonnull.
- Budgets(UserId, CategoryId, PeriodMonth).
- ExchangeRates(BaseCurrency, QuoteCurrency, RateDate).
- MonthlySummaries(UserId, SummaryMonth).
- RefreshTokens(TokenHash).
- Notifications(UserId, DeduplicationKey).
- AspNetUsers(NormalizedEmail).

Additional nonunique indexes cover reporting by user/date and user/category/date, refresh-token user/family lookup, and foreign-key access. Identity retains its standard username and role-name uniqueness.

The model uses decimal(18,2) for money, decimal(18,8) for rates, date for DateOnly fields, and datetime2 for UTC event timestamps. UTC value conversion rejects writes with ambiguous/non-UTC DateTime kinds and restores UTC kind on materialization.

Checks enforce positive source transaction, bill, contribution, goal-target, and budget amounts; positive FX rates; nonnegative rounded AmountBase and summary totals; threshold percentages 1–100; first-of-month budget/summary dates; bill reference pairing; bill payments as expenses; supported enums; intended recurrence day; uppercase three-letter currency codes; notification reference pairing; SHA-256 hash shape; and refresh-token expiry after creation. Required text and the optional color format have checks as well. Summary net cash flow equals income minus expenses and may be negative.

Two migration-owned database triggers cover cross-row/deletion invariants:

- TR_Transactions_ProtectBillPayments rejects deletion or detachment/reassignment of an existing bill payment. Ordinary unlinked transactions remain deletable. A future controlled reversal requires a separately reviewed design.
- TR_AspNetUsers_LockBaseCurrency rejects base-currency changes when transactions, budgets, bills, goals, or monthly summaries already exist.

Trigger creation is wrapped in EXEC so CREATE TRIGGER has its own SQL batch. EF OUTPUT is disabled for the affected tables. These guards are part of the immutable initial migration.

## Approved policies supported

- Transactions preserve original amount/currency, FxRate, FxRateDate, and AmountBase. A database check prohibits future rate dates.
- CurrencyConversion supplies pure snapshot arithmetic with MidpointRounding.AwayFromZero. Same-currency snapshots use rate 1 and the transaction date. Foreign rates must be on/before that date and within the supplied maximum age.
- CurrencyPolicyOptions defaults MaximumRateAgeDays to 7 and binds CurrencyPolicy configuration. Future posting services must pass the configured value to the snapshot policy. No Frankfurter client or posting service is implemented.
- RecurringBills has SQL Server rowversion optimistic concurrency and an IntendedDayOfMonth field. Weekly bills require a null intended day; other supported frequencies require 1–31. BillRecurrence clamps short months and restores the original intended day later.
- Notification DeduplicationKey is required nvarchar(200), with binary collation and per-user uniqueness. BudgetTransition keys include a distinct transition GUID; callers must persist/reuse the same transition ID for retries and allocate another for a later legitimate transition. The future alert workflow must coordinate that durable transition creation atomically.
- Budget current status and goal progress are not stored as independently maintained totals. LastAlertStatus/LastAlertAtUtc provide alert-state storage.
- Monthly summary rows are mutable and unique per user/month so a later service can regenerate them after backdated changes.
- RefreshTokenHash computes uppercase SHA-256 hexadecimal values. RefreshTokens stores only hashes and revocation/replacement metadata, with a GUID family ID and integer PK. There is no plaintext token property, token generator, issuance endpoint, rotation service, or retention cleanup.
- Ownership/category eligibility checks, budget recalculation/reset, atomic payment advancement, summary regeneration, and high-entropy token generation/rotation remain later service responsibilities, as requested. No permanent-user-deletion operation was added.

## SQL Server configuration

API startup adds AddInfrastructure(builder.Configuration). Existing controller routing, HTTPS redirection, OpenAPI setup, launch profiles, and appsettings configuration are preserved.

The registered context reads ConnectionStrings:DefaultConnection. No instance, database name, or credentials were guessed. Without a connection string the provider can construct its model, but database operations cannot connect.

Configure the actual connection later through the ConnectionStrings__DefaultConnection environment variable. CurrencyPolicy__MaximumRateAgeDays can override the default 7-day policy.

ApplicationDbContextFactory in API constructs the SQL Server model without any connection string. There is no automatic database migration, EnsureCreated, database initialization, or connection attempt on startup.

## Migration and SQL

- Migration: PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.cs
- Metadata: PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.Designer.cs
- Snapshot: PersonalFinance.Infrastructure/Migrations/ApplicationDbContextModelSnapshot.cs
- Guard SQL: PersonalFinance.Infrastructure/Migrations/FinanceSchemaGuards.cs
- Inspection script: docs/InitialFinanceSchema.sql

The script was generated from migration 0 through InitialFinanceSchema and inspected. It contains 17 model tables plus __EFMigrationsHistory, 19 foreign keys (13 application and 6 framework-owned Identity), 10 unique indexes including standard Identity indexes, 38 check constraints, and 2 guarded trigger-creation commands. It includes primary keys, required precision, filtered indexes, rowversion, and role definitions. Application foreign keys omit ON DELETE CASCADE, meaning SQL Server NO ACTION. Cascades appear only in standard Identity relationships.

The SQL is an unapplied inspection artifact. No SQL command was submitted to a database.

## Verification performed

Final command:

    dotnet build PersonalFinancePlatform.slnx

Result: succeeded, 0 warnings, 0 errors.

Focused offline checks:

    dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore

Result: 37 passed, 0 failed. The executable returns a nonzero exit code on failure. Checks cover Domain independence, provider creation without a connection, expected tables, integer keys, all application foreign keys, required indexes, nullability/collation, precision, dates/UTC conversion, rowversion, check metadata, Identity registration, role seeds, SHA-256 hashing, recurrence edge cases, conversion rounding/rate-age boundaries, notification transition keys, migration operations, and generated SQL guard batching.

The EF migrations has-pending-model-changes command also reported:

    No changes have been made to the model since the last migration.

The initial migration, SQL script generation, and model inspection used API as startup assembly and Infrastructure as the migration assembly. No validation opened a database connection. Database-engine execution of constraints/triggers and concurrent-write integration testing remain pending an approved development database.

LaunchSettings.json, appsettings.json, and appsettings.Development.json retained their original SHA-256 hashes. The project specification was read through shared access and was not modified.

## Environment errors and resolution

- The user-level NuGet.Config was inaccessible in the managed execution environment. Process-local APPDATA pointed to a temporary directory for tooling; user settings were not modified.
- Network access to api.nuget.org was denied. Restore succeeded using the existing package cache with NUGET_PACKAGES directed to that cache. NuGet vulnerability auditing was disabled only for these offline command processes; a network-backed audit was not performed.
- The first compilation exposed a missing Microsoft.EntityFrameworkCore namespace import in ConfigurationRules. It was corrected; all subsequent final builds pass.
- SQL review caught CREATE TRIGGER batch placement. The generated migration now uses EXEC-wrapped trigger definitions.
- The fallback EF invocation initially chose a nested snapshot directory. The snapshot was moved into the intended Migrations directory and the model/snapshot was revalidated.
- A solution-local .config/dotnet-tools.json pins dotnet-ef 10.0.13. Download/restoration of that local tool was blocked by network restrictions. Migration and SQL generation instead used the existing matching Microsoft.EntityFrameworkCore.Tools 10.0.13 ef.dll via dotnet exec with API deps/runtimeconfig files. No extra Tools package reference was added.

When network access is available, restore the pinned local tool with:

    dotnet tool restore

Conventional regeneration/inspection commands after tool restoration:

    dotnet ef migrations has-pending-model-changes --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api
    dotnet ef migrations script 0 InitialFinanceSchema --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api --output docs/InitialFinanceSchema.sql

## Created files

Paths below are relative to the solution root; build-generated bin/obj artifacts are excluded.

- .config/dotnet-tools.json
- PersonalFinance.Domain/Enums/FinanceEnums.cs
- PersonalFinance.Domain/Entities/Category.cs
- PersonalFinance.Domain/Entities/Transaction.cs
- PersonalFinance.Domain/Entities/Budget.cs
- PersonalFinance.Domain/Entities/RecurringBill.cs
- PersonalFinance.Domain/Entities/SavingsGoal.cs
- PersonalFinance.Domain/Entities/GoalContribution.cs
- PersonalFinance.Domain/Entities/ExchangeRate.cs
- PersonalFinance.Domain/Entities/Notification.cs
- PersonalFinance.Domain/Entities/MonthlySummary.cs
- PersonalFinance.Domain/Policies/BillRecurrence.cs
- PersonalFinance.Domain/Policies/CurrencyConversion.cs
- PersonalFinance.Domain/Policies/NotificationKeys.cs
- PersonalFinance.Infrastructure/Identity/ApplicationUser.cs
- PersonalFinance.Infrastructure/Identity/ApplicationRoles.cs
- PersonalFinance.Infrastructure/Identity/RefreshToken.cs
- PersonalFinance.Infrastructure/Identity/RefreshTokenHash.cs
- PersonalFinance.Infrastructure/CurrencyPolicyOptions.cs
- PersonalFinance.Infrastructure/DependencyInjection.cs
- PersonalFinance.Infrastructure/Persistence/ApplicationDbContext.cs
- PersonalFinance.Infrastructure/Persistence/UtcDateTimeConverter.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/ConfigurationRules.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/IdentityConfigurations.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/CategoryConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/TransactionConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/BudgetConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/RecurringBillConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/SavingsConfigurations.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/ExchangeRateConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/NotificationConfiguration.cs
- PersonalFinance.Infrastructure/Persistence/Configurations/MonthlySummaryConfiguration.cs
- PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.cs
- PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.Designer.cs
- PersonalFinance.Infrastructure/Migrations/ApplicationDbContextModelSnapshot.cs
- PersonalFinance.Infrastructure/Migrations/FinanceSchemaGuards.cs
- PersonalFinance.Api/Persistence/ApplicationDbContextFactory.cs
- tests/PersonalFinance.SchemaValidation/PersonalFinance.SchemaValidation.csproj
- tests/PersonalFinance.SchemaValidation/Program.cs
- docs/InitialFinanceSchema.sql
- docs/Phase3_Implementation_Report.md

## Modified files

- PersonalFinance.Infrastructure/PersonalFinance.Infrastructure.csproj — two package references.
- PersonalFinance.Api/PersonalFinance.Api.csproj — private EF Design reference.
- PersonalFinance.Api/Program.cs — Infrastructure import and service registration.

## Outstanding items and next step

No schema decision blocks the implemented foundation. Remaining environment items are restoration of the pinned local EF tool when network access is available and provision of the actual SQL Server development connection string.

Live SQL constraint/trigger execution and concurrency integration tests have not been run. Future API services must use the approved ownership, conversion, token, alert-transition, and atomic-write policies; those features remain outside this phase.

Review the migration and SQL, identify the development SQL Server/database, and explicitly approve database application before running any database update. No migration has been applied.

