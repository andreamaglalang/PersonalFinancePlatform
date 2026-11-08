# Phase 3 Final Database Verification

Review date: October 31, 2026  
Solution: PersonalFinancePlatform  
Overall result: **PASS with 5 WARNING findings; no schema-blocking FAIL findings.**

The schema is structurally ready for its first application to an explicitly approved development SQL Server database. This is an offline readiness assessment, not proof of successful execution on SQL Server. Actual application remains blocked until the target database/connection and execution method are established and the user explicitly approves application.

No packages, application code, entities, policies, configurations, migrations, or existing SQL artifacts were modified during this review. Only this verification report was created. No migration was applied, database connection opened, or SQL Server object/data changed.

## Authority and scope

Reviewed:

- docs/Phase3_Implementation_Report.md.
- docs/Initial_Project_Report_Personal_Finance_Platform_v3.1_Implementation_Ready.docx, especially Sections 2.4, 2.5, 3.1–3.3, 4, and 5. DOCX extraction succeeded using shared read access.
- The approved Phase 3 implementation decisions, which supplement the conceptual ERD and override its older statements.
- All nine Domain entity classes, approved enums, and the three Domain policy helpers.
- Infrastructure ApplicationUser, ApplicationRoles, RefreshToken, RefreshTokenHash, ApplicationDbContext, UTC conversion, service registration, currency options, and all entity configurations.
- InitialFinanceSchema Up/Down operations, migration designer, model snapshot, FinanceSchemaGuards, and docs/InitialFinanceSchema.sql.
- API design-time context factory and the existing standalone schema-validation executable.
- Current package references and restored package assets following the approved Pkcs security fix.

Older statements about MVC token-cookie storage and undecided token lifetimes were not treated as overriding the finalized policies. JWT endpoints, ownership services, background jobs, Android behavior, token rotation services, and business controllers were deliberately excluded from Phase 3 implementation and are not counted as missing schema features.

## Required verification matrix

A PASS means the required Phase 3 foundation exists in the inspected code/model/artifacts. It does not mean the corresponding future API workflow has been implemented or that SQL Server has executed the constraint.

| # | Requirement | Result | Evidence and limits |
| --- | --- | --- | --- |
| 1 | Required financial entities | PASS | Category, Transaction, Budget, RecurringBill, SavingsGoal, GoalContribution, ExchangeRate, Notification, MonthlySummary exist in Domain. RefreshToken is in Infrastructure. No separate Users table exists. |
| 2 | Integer Identity user IDs | PASS | Identity/ApplicationUser.cs:5 derives from IdentityUser<int>; Persistence/ApplicationDbContext.cs:10–11 derives from IdentityDbContext<ApplicationUser, IdentityRole<int>, int>. User/role PKs and ownership FKs are integer. |
| 3 | Approved properties | PASS | Entity members, configurations, migration columns, and SQL match the approved field list, including nullable goal target dates, nullable category ownership, UTC suffixes, FxRateDate, IntendedDayOfMonth, RowVersion, and bounded deduplication keys. See the property checklist and W-01. |
| 4 | Required FKs and deletion restrictions | PASS | All 13 application foreign keys use DeleteBehavior.NoAction. Categories, bills, and goals cannot be deleted while dependent financial rows exist. |
| 5 | Unique indexes | PASS | All eight required unique indexes exist; standard Identity role-name and username indexes bring the SQL total to ten. |
| 6 | Decimal precision | PASS | All monetary fields are decimal(18,2); Transaction.FxRate and ExchangeRate.Rate are decimal(18,8). No SQL float/double money columns. W-01 concerns helper input validation, not column precision. |
| 7 | Recurring bill rowversion | PASS | RecurringBillConfiguration.cs:18 uses IsRowVersion(); SQL line 210 is rowversion NOT NULL. Metadata confirms store-generated concurrency control. |
| 8 | Financial checks | PASS | 38 CHECK constraints cover amounts, FX rates, types, thresholds, date normalization, bill links, recurrence day, currency shape, statuses, notification reference pairing, token-hash shape, and expiry. |
| 9 | First-day monthly dates | PASS | BudgetConfiguration.cs:22 and MonthlySummaryConfiguration.cs:21 use DAY(...)=1. SQL lines 192 and 127 contain matching constraints. |
| 10 | ClientId for Android synchronization | PASS | Transaction.cs:14 is a required Guid; SQL uses uniqueidentifier NOT NULL and unique (UserId,ClientId). Returning the existing record on retry remains a later API responsibility. |
| 11 | Bill-payment uniqueness/nullability | PASS | Filtered unique (BillId,BillDueDate), paired-null CHECK, real BillId FK, expense-only bill CHECK, and payment-protection trigger are present. |
| 12 | FX snapshot/freshness representation | PASS | Original amount/currency, FxRate, FxRateDate, AmountBase persist. SQL prohibits future rate dates. CurrencyConversion enforces date age and AwayFromZero rounding; options default to seven calendar days. See W-01 and service integration notes. |
| 13 | Secure refresh-token persistence | PASS | Unique SHA-256 hash, user FK, GUID family, creation/expiry/revocation/replacement fields, and family lookup index exist. No plaintext token field. Rotation/reuse/revocation logic and random token issuance are correctly deferred. |
| 14 | Repeated legitimate notification transitions | PASS | Required per-user unique DeduplicationKey plus NotificationKeys.BudgetTransition includes a transition GUID and status. Same transition can reuse its key; a later transition can use a different GUID. Durable coordination is a later service responsibility. |
| 15 | No unexpected cascades | PASS | No application/financial FK cascades. Six standard Identity FK cascades remain within framework-owned tables; they do not cascade into financial tables. |
| 16 | Model/migration/SQL consistency | PASS | Existing tests and EF tooling report no pending model changes. A newly generated SQL script in the temporary directory exactly matches the saved script text, including triggers and seeds. W-03 records the database-execution limit. |

## Property checklist

All table PKs are integer. Identity role and user keys are integer throughout.

| Entity | Verified fields and mappings |
| --- | --- |
| ApplicationUser | Standard AspNetUsers fields plus FullName required nvarchar(100), BaseCurrency required char(3), PHP CLR/database default, CreatedAtUtc datetime2. Required nonblank Email and NormalizedEmail, explicit case-insensitive normalized-email collation and unique index. |
| Category | CategoryId, nullable UserId, required Name nvarchar(50), string-converted Income/Expense Type nvarchar(10), nullable ColorHEX char(7), IsActive default true. |
| Transaction | TransactionId, UserId, CategoryId, nullable BillId/BillDueDate, required GUID ClientId, Income/Expense Type nvarchar(10), Amount, CurrencyCode char(3), FxRate, required FxRateDate, AmountBase, nullable Description nvarchar(200), TransactionDate date, CreatedAtUtc. |
| Budget | BudgetId, UserId, CategoryId, PeriodMonth date, positive LimitAmount, AlertThresholdPct tinyint 1–100, nullable LastAlertStatus nvarchar(10), nullable LastAlertAtUtc. No independently maintained current budget-status property. |
| RecurringBill | BillId, UserId, CategoryId, required Name nvarchar(100), positive Amount, CurrencyCode char(3), Frequency nvarchar(20), NextDueDate date, nullable IntendedDayOfMonth tinyint, ReminderDaysBefore tinyint, IsActive, RowVersion. |
| SavingsGoal | GoalId, UserId, required Name nvarchar(100), positive TargetAmount, nullable TargetDate date, supported Status nvarchar(20). |
| GoalContribution | ContributionId, GoalId, positive Amount, ContributedOn date, nullable Note nvarchar(200). No direct UserId and no treatment as an expense entity. |
| ExchangeRate | RateId, BaseCurrency/QuoteCurrency char(3), positive Rate, RateDate date, FetchedAtUtc. No FK to transactions or users. |
| Notification | NotificationId, UserId, Type nvarchar(30), nullable ReferenceType nvarchar(30)/ReferenceId pair, required Message nvarchar(400), IsRead, CreatedAtUtc, nullable EmailSentAtUtc, required DeduplicationKey nvarchar(200). |
| MonthlySummary | SummaryId, UserId, SummaryMonth date, TotalIncome/TotalExpense/NetCashFlow/SavingsAllocated, GeneratedAtUtc, nullable EmailSentAtUtc. Net cash flow can be negative and is constrained to income minus expenses. Rows remain updateable for regeneration. |
| RefreshToken | Integer Id/UserId, unique TokenHash char(64), TokenFamilyId uniqueidentifier, CreatedAtUtc, ExpiresAtUtc, nullable RevokedAtUtc/ReplacedByTokenHash char(64). Hash columns use binary collation and uppercase hexadecimal checks. |

Enums match the approved sets: Income/Expense; OK/Warning/Exceeded; Weekly/Monthly/Quarterly/Yearly; Active/Completed/Cancelled.

All date-only properties map to SQL date. UTC event properties map to datetime2 with an EF value converter that rejects non-UTC writes and restores UTC kind when materialized. SQL datetime2 itself does not encode a timezone, so direct SQL writers must also respect the UTC contract.

## Relationship and index checklist

All application relationships below use NO ACTION in SQL:

| Principal | Dependent | FK | Optional |
| --- | --- | --- | --- |
| AspNetUsers | Categories | UserId | Yes; NULL denotes a system category |
| AspNetUsers | Transactions | UserId | No |
| AspNetUsers | Budgets | UserId | No |
| AspNetUsers | RecurringBills | UserId | No |
| AspNetUsers | SavingsGoals | UserId | No |
| AspNetUsers | Notifications | UserId | No |
| AspNetUsers | MonthlySummaries | UserId | No |
| AspNetUsers | RefreshTokens | UserId | No |
| Categories | Transactions | CategoryId | No |
| Categories | Budgets | CategoryId | No |
| Categories | RecurringBills | CategoryId | No |
| RecurringBills | Transactions | BillId | Yes, paired with BillDueDate |
| SavingsGoals | GoalContributions | GoalId | No |

Notification references remain logical references, as specified. Goal contribution ownership is obtained through the goal. User/category/bill ownership compatibility remains an explicitly approved future service-layer check; the independent FKs do not themselves enforce equal owners.

Required unique indexes in docs/InitialFinanceSchema.sql:

| Index | SQL line | Verified behavior |
| --- | --- | --- |
| EmailIndex | 280 | NormalizedEmail, unique, NOT NULL, Latin1_General_100_CI_AS, no filter |
| IX_Budgets_UserId_CategoryId_PeriodMonth | 286 | One budget per user/category/month |
| IX_ExchangeRates_BaseCurrency_QuoteCurrency_RateDate | 290 | One cached rate per pair/date |
| IX_MonthlySummaries_UserId_SummaryMonth | 294 | One regenerable summary per user/month |
| IX_Notifications_UserId_DeduplicationKey | 296 | Per-user event deduplication |
| IX_RefreshTokens_TokenHash | 302 | Unique token hashes |
| IX_Transactions_BillId_BillDueDate | 308 | Unique period only when both values are nonnull |
| IX_Transactions_UserId_ClientId | 314 | Per-user sync idempotency |

Nonunique reporting indexes cover user/date and user/category/date; refresh tokens have a user/family index. Standard Identity username and role-name uniqueness remain.

## Migration and SQL inspection

Reviewed migration: PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.cs.

- Up creates 17 model tables: seven Identity tables plus nine financial/support tables and RefreshTokens.
- The inspection SQL also creates __EFMigrationsHistory, for 18 CREATE TABLE statements overall.
- There are 19 FKs: 13 application NO ACTION relationships and six standard Identity CASCADE relationships.
- There are ten unique indexes and 38 CHECK constraints.
- Role seeds contain only User and Administrator; no default users, passwords, or credentials.
- Migration lines 605 and 611 invoke the guard helper in Up and Down respectively.
- FinanceSchemaGuards.cs:10–25 / SQL:318–331 reject deleting or detaching/reassigning existing bill-payment links.
- FinanceSchemaGuards.cs:26–44 / SQL:333–351 prevent changing base currency when financial records exist.
- Trigger creation uses EXEC-wrapped SQL, giving CREATE TRIGGER its own batch. EF OUTPUT is disabled for both affected tables.
- Down drops the guards before dropping the tables in dependency-compatible order. Down was inspected, never executed.
- No application startup code calls Migrate, EnsureCreated, or performs database initialization.
- No Hangfire tables are in the application migration; Hangfire owns its separate schema in a later phase.
- The script is a first-application script, not an idempotent deployment script for arbitrary existing schemas.

The six Identity cascades are visible at SQL lines 64, 73, 82, 89–90, and 99. They are expected framework defaults, not financial-deletion paths. No permanent-user-deletion endpoint or workflow was added.

## WARNING findings

### W-01 Conversion helper requires stricter input validation before transaction posting

Classification: WARNING. Not a schema-application blocker; correction is required before exposing transaction posting.

Exact locations:

- PersonalFinance.Domain/Policies/CurrencyConversion.cs:6–20, especially the equality shortcut at 12–13 and multiplication at 20.
- PersonalFinance.Infrastructure/Persistence/Configurations/TransactionConfiguration.cs:14–17.
- docs/InitialFinanceSchema.sql:240–244, Amount/FxRate/AmountBase column definitions.
- tests/PersonalFinance.SchemaValidation/Program.cs:207–220, existing conversion scenarios.

Relevant code:

    if (string.Equals(currency, baseCurrency, StringComparison.Ordinal))
        return (1m, transactionDate, decimal.Round(amount, 2, MidpointRounding.AwayFromZero));
    ...
    return (rate, rateDate, decimal.Round(amount * rate, 2, MidpointRounding.AwayFromZero));

The helper accepts an amount with more than two fractional digits and computes using it before SQL stores the original amount at scale 2. A temporary executable referencing the unchanged Domain DLL reproduced:

    amount=1.005, rate=2
    helper AmountBase=2.01
    scale-2 stored source=1.01; conversion from that source=2.02

The helper also accepted empty matching currency strings and returned rate 1. The database currency checks prevent those empty codes from being stored, but the helper itself is not an input-validation boundary.

Recommended correction: before posting is implemented, validate supported, nonblank three-letter currency codes and ensure source amounts and FX rates are representable at the approved precisions. Reject unsupported precision or define an explicitly approved normalization step before calculating the snapshot. Use the same canonical amount/rate for both calculation and persistence; retain AwayFromZero rounding. Pass the configured maximum rate age into the policy and add regression tests for these edge cases. No existing code was changed.

### W-02 Design-time factory ignores the configured migration-application connection

Classification: WARNING. An execution-workflow prerequisite, not a DDL/schema defect.

Exact locations:

- PersonalFinance.Api/Persistence/ApplicationDbContextFactory.cs:10–11.
- PersonalFinance.Infrastructure/DependencyInjection.cs:16–20.
- docs/Phase3_Implementation_Report.md:83–87.

Relevant code:

    public ApplicationDbContext CreateDbContext(string[] args) =>
        new(new DbContextOptionsBuilder<ApplicationDbContext>().UseSqlServer().Options);

This is safe for offline model/script creation, but the factory does not read args, application configuration, user secrets, or ConnectionStrings__DefaultConnection. EF tooling selects the factory, so setting DefaultConnection alone does not make ordinary factory-based database application connect.

Recommended correction: before any approved application, establish the actual development target and choose a connection-aware design-time factory using approved configuration sources, or a deliberate tooling connection override supported by EF. Do not assume API runtime DI will supply the factory's connection. Never insert credentials into source. No connection was attempted or factory modified during this review.

### W-03 SQL Server constraint and concurrency execution remain unverified

Classification: WARNING. Offline verification limit.

Exact locations:

- tests/PersonalFinance.SchemaValidation/Program.cs:51–54 explicitly constructs the provider without a connection.
- tests/PersonalFinance.SchemaValidation/Program.cs:229–256 checks model/migration metadata and generated SQL, not database execution.
- PersonalFinance.Infrastructure/Migrations/FinanceSchemaGuards.cs:10–44.
- docs/InitialFinanceSchema.sql:308 and 318–351, payment uniqueness and database triggers.

The existing 37 checks are an offline console validation suite. They do not prove SQL Server executed every CHECK, trigger, or unique index correctly, nor do they simulate concurrent payments, token rotation, or base-currency updates.

Recommended correction: only after explicit approval to apply to the designated development database, run database-backed tests for invalid inserts, duplicate client/period keys, referenced deletions, payment deletion/detachment, base-currency changes, rowversion conflicts, rollback, and concurrent coupled writes. Use a disposable or otherwise specifically approved development target. Do not describe current checks as SQL Server integration tests.

### W-04 Full online dependency audit remains incomplete

Classification: WARNING. Security-audit completeness limitation; the approved Pkcs version override is present.

Exact locations:

- PersonalFinance.Infrastructure/PersonalFinance.Infrastructure.csproj:7 pins System.Security.Cryptography.Pkcs 10.0.13.
- PersonalFinance.Infrastructure/obj/project.assets.json and PersonalFinance.Api/obj/project.assets.json resolve that version.
- docs/Phase3_Implementation_Report.md:126 records the earlier offline audit limitation.

The audit was attempted again in this review:

    dotnet list PersonalFinancePlatform.slnx package --vulnerable --include-transitive --no-restore

It exited 1 because api.nuget.org:443 access was forbidden. No assertion is made that all dependencies are vulnerability-free.

Recommended correction: rerun the online audit when access is available and assess any findings before deployment. The build used process-local NuGetAudit=false for offline repeatability; this did not change project/package configuration. No packages were changed in this review.

### W-05 Original implementation report predates the completed security fix

Classification: WARNING. Documentation maintenance only.

Exact locations:

- docs/Phase3_Implementation_Report.md:9–17 lists the original direct package additions.
- docs/Phase3_Implementation_Report.md:189 describes only two Infrastructure package references.
- PersonalFinance.Infrastructure/PersonalFinance.Infrastructure.csproj:5–7 currently has three direct package references.

The original report is a historical record of initial implementation, but its package inventory does not include the subsequently approved Pkcs override.

Recommended correction: append a dated security-fix amendment to the original report, noting the direct System.Security.Cryptography.Pkcs 10.0.13 reference and successful build, while preserving its online-audit limitation. This review records the current state and does not edit the older report.

## FAIL findings

None identified for the approved Phase 3 schema foundation.

The conversion edge cases in W-01 are a warning about preconditions for future posting, not a claim that existing persisted rows are corrupt. No data exists or was inspected in a database during this review. Deferred authentication/finance/background workflows are not counted as failures.

## Policy and service integration obligations

The following are supported by the schema but must be implemented in later authorized phases:

- Scope all financial queries to the authenticated user and validate ownership of selected categories, bills, and goals. A system-default category is allowed via nullable category ownership.
- Preserve ClientId across Android retries; return the existing row on duplicate creation.
- Calculate budgets from expense AmountBase only, excluding income and contributions; recalculate old and new scopes on edits; clear active warning state on OK.
- Allocate a durable alert-transition ID once, persist the notification atomically with any relevant alert-state update, and reuse its key on retries. A new legitimate status transition gets a new ID.
- Regenerate monthly summaries for backdated edits/deletions without creating a second user/month row.
- Use the configured seven-day initial maximum age, validate actual publication/currency pair, and never post with an unacceptable/future rate. The database CHECK prohibits future rates; it does not encode the configurable age or fetch publication data.
- Supply the original intended day when creating a nonweekly bill; use BillRecurrence on advancement, honor rowversion, and commit payment plus advancement atomically.
- Generate refresh tokens using cryptographically secure high-entropy randomness, persist SHA-256 hashes only, rotate/revoke atomically, retain revoked predecessors for replay detection, and revoke a family on reuse. The schema supports this but does not automatically implement it.
- Restrict bill-payment reversal until a controlled reversal design is approved. Current payment protection rejects ordinary deletion and unlinking.

## Executed verification

| Action | Result |
| --- | --- |
| dotnet build PersonalFinancePlatform.slnx | PASS; exit 0, 0 warnings, 0 errors |
| dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore | PASS; exit 0, 37 passed, 0 failed |
| EF migrations has-pending-model-changes | PASS; no changes since the last migration |
| Fresh migration SQL generation to temporary storage | PASS; exact text match with docs/InitialFinanceSchema.sql |
| Temporary policy probes, unchanged Domain DLL | Completed; reproduced the two input-precondition issues described in W-01 |
| Online vulnerability audit including transitives | WARNING; exit 1, blocked network |
| SQL Server integration/application | Not performed; not authorized |

Matching EF 10.0.13 cached tooling was invoked through dotnet exec with API deps/runtimeconfig and Infrastructure as the migration assembly. This did not restore/change packages or contact SQL Server. The pinned local dotnet-ef tool remains a separate environment-restoration item described in the original report.

The suite is a console executable, not an xUnit/MSTest project; dotnet run is its actual test command. No claim is made that dotnet test or live database tests were executed.

SHA-256 verification compared 50 existing non-generated source/config/report/SQL files before and after review and found no changes. The original DOCX was opened only for reading. Build-generated bin/obj outputs and temporary review artifacts are excluded. The only new repository deliverable is docs/Phase3_Final_Verification.md.

## Final readiness decision and blockers

**Schema readiness: PASS with warnings. Structurally ready for first application to an explicitly approved development database; no schema-blocking failures found.**

Remaining prerequisites before execution:

1. Identify and verify the actual SQL Server instance/database and provide the approved connection configuration.
2. Resolve the application method described in W-02 so tooling uses the intended connection.
3. Obtain explicit approval to apply the migration to that target. This review request is not approval to apply it.

SQL Server execution/integration validation remains pending under W-03. A successful online vulnerability audit remains pending under W-04 before deployment. W-01 should be corrected before transaction posting is exposed; W-05 is documentation follow-up. These warnings do not require automatically changing the initial schema during this review.

No migration has been applied. SQL Server has not been modified.

