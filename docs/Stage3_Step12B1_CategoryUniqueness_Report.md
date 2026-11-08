# Stage 3 — Step 12B.1: Category Uniqueness Hardening

## Status and database safeguards

Source implementation and migration generation are complete. The new migration has NOT been applied. PersonalFinanceDb has not been accessed or modified during this implementation. No isolated database was created. No records, existing relationships, Identity settings, or public authentication endpoints were changed. No Git commit or push was performed.

The initial migration, its designer, FinanceSchemaGuards, original docs/InitialFinanceSchema.sql, and Domain source files retain their pre-change SHA-256 hashes. The model snapshot was intentionally updated.

IMPORTANT: updated category create/update operations query NormalizedName. They require the new schema. Do not test those updated operations against the still-unmigrated PersonalFinanceDb. Coordinate activation of this application version with a separately approved migration application, after isolated validation.

## Source inventory

Modified:

- PersonalFinance.Infrastructure/Persistence/Configurations/CategoryConfiguration.cs
- PersonalFinance.Infrastructure/Finance/CategoryService.cs
- PersonalFinance.Infrastructure/Migrations/ApplicationDbContextModelSnapshot.cs
- tests/PersonalFinance.SchemaValidation/Program.cs
- tests/PersonalFinance.FinanceTests/FinanceFixture.cs
- tests/PersonalFinance.FinanceTests/Program.cs
- tests/PersonalFinance.AuthenticationTests/TestDoubles.cs

Created:

- PersonalFinance.Infrastructure/Persistence/CategoryNameNormalization.cs
- PersonalFinance.Infrastructure/Migrations/20261031115500_AddCategoryNameUniqueness.cs
- PersonalFinance.Infrastructure/Migrations/20261031115500_AddCategoryNameUniqueness.Designer.cs
- docs/AddCategoryNameUniqueness.sql
- tests/PersonalFinance.FinanceTests/CategoryComputedValueSimulation.cs
- tests/PersonalFinance.FinanceTests/SyntheticSqlError.cs
- tests/PersonalFinance.CategoryUniquenessTests/PersonalFinance.CategoryUniquenessTests.csproj
- tests/PersonalFinance.CategoryUniquenessTests/Program.cs
- docs/Stage3_Step12B1_CategoryUniqueness_Report.md

No production package versions, solution references, controllers, Domain entities, financial relationship configurations, connection settings, or initial migration files were changed. Generated bin/obj files are excluded from this inventory.

The new focused test project uses the already-used EF Core InMemory 10.0.13 package. Production EF Core remains 10.0.13.

## Computed key and exact normalization

NormalizedName is an Infrastructure shadow property, computed by SQL Server and persisted as nvarchar(50). It is not part of the Domain entity or public request DTOs.

Exact SQL expression:

~~~sql
CONVERT(nvarchar(50),
    TRIM(
        CONVERT(nvarchar(25),
            NCHAR(9) + NCHAR(10) + NCHAR(11) + NCHAR(12) + NCHAR(13) +
            NCHAR(32) + NCHAR(133) + NCHAR(160) + NCHAR(5760) +
            NCHAR(8192) + NCHAR(8193) + NCHAR(8194) + NCHAR(8195) +
            NCHAR(8196) + NCHAR(8197) + NCHAR(8198) + NCHAR(8199) +
            NCHAR(8200) + NCHAR(8201) + NCHAR(8202) +
            NCHAR(8232) + NCHAR(8233) + NCHAR(8239) +
            NCHAR(8287) + NCHAR(12288)
        ) COLLATE Latin1_General_100_BIN2
        FROM [Name] COLLATE Latin1_General_100_BIN2
    )
) COLLATE Latin1_General_100_CI_AS_KS_WS_SC
~~~

The fixed 25-character set comprises U+0009–000D, U+0020, U+0085, U+00A0, U+1680, U+2000–200A, U+2028, U+2029, U+202F, U+205F, and U+3000.

C# uses the identical explicit character list with Trim(char[]). It preserves case, all interior characters, and unlisted characters such as U+200B. No uppercase conversion, whitespace collapsing, NFC/NFKC conversion, or replacement of interior tabs/nonbreaking spaces is performed.

Case/accent/Unicode equality is determined by the SQL computed column's collation, not by a claimed equivalent C# string comparer. This preserves the manually verified behavior, including equality of composed/decomposed accented forms without rewriting their stored sequences.

EF configuration uses HasComputedColumnSql(expression, stored: true). Collation is deliberately specified only inside the expression. During SQL review, combining UseCollation with that expression caused the EF generator to append a duplicate COLLATE clause. The redundant mapping declaration was removed and the new migration/designer/snapshot aligned; regenerated SQL now has a single final comparison COLLATE clause. A schema check guards against reintroducing that defect.

The computed property is nullable in EF/DDL metadata; the explicit check constraint prohibits actual null or empty values. Database inference of computed-column nullability is not relied upon.

## Unique index and checks

Index:

~~~text
UX_Categories_UserId_Type_NormalizedName
UNIQUE NONCLUSTERED (UserId, Type, NormalizedName)
UNFILTERED
~~~

Configuration explicitly uses IsUnique(), IsClustered(false), and HasFilter(null). The generated CreateIndex operation has no filter, and generated SQL has no WHERE clause on this index.

The existing IX_Categories_UserId is explicitly retained. IsActive is excluded from the unique key.

Behavior:

- Equivalent names conflict within the same owner and type.
- Null owner represents the single system-default scope.
- Different users and different types remain separate.
- System-default and private categories remain separate scopes.
- Inactive categories reserve their names.
- No sentinel user ID is introduced.

Added check:

~~~sql
[NormalizedName] IS NOT NULL AND DATALENGTH([NormalizedName]) > 0
~~~

Existing name, type, color checks and all NO_ACTION financial relationships are unchanged.

## Service behavior and concurrency

Create/update trim the approved boundary characters before storing names. Duplicate validation queries EF.Property<string>(category, "NormalizedName") under the requested owner/type scope. The current row is excluded on update; inactive rows are included. Nullable owner predicates use SQL null semantics. No ToUpperInvariant or SQL UPPER substitute remains in category duplicate lookup.

Administrator/system protection and claim-derived ownership are preserved. Type changes and deletion remain blocked when financial references exist. Renaming does not rewrite or remove transaction, budget, or bill records.

Default-category writes now share finance:categories:system in addition to existing category/user locks. This coordinates multiple administrators rather than relying solely on the actor's user lock. Private-owner operations retain their existing owner locks. Existing serializable transactions and category row locking are preserved.

## Unique-key errors and atomicity

Only category create/update operations use the new local duplicate-error handler. It catches DbUpdateException with SqlException errors 2601 or 2627 and returns:

~~~text
HTTP 409
A category with the same name and type already exists.
~~~

The handler executes outside the write transaction callback, after SqlFinancialWriteScope has unwound and disposed its transaction. It clears failed tracked state to prevent accidental later persistence within the request scope. Raw SQL text, duplicate values, owner IDs, index details, connection strings, and provider exception messages are not returned.

The shared FinanceServiceBase and transaction ClientId idempotency handling were not changed. A focused test confirms the handler does not blanket-handle category deletion.

The production write scope rolls back through transaction disposal on failure. Synthetic tests verify exception translation and control flow, but actual SQL rollback and simultaneous-write behavior remain unverified until isolated SQL testing.

## Migration and generated SQL

Migration ID: 20261031115500_AddCategoryNameUniqueness

Up has exactly three category-only additive operations:

1. Add persisted computed NormalizedName.
2. Create the unfiltered unique nonclustered index.
3. Add CK_Categories_NormalizedName.

Down removes only this new index, check, and computed column. No foreign keys, existing indexes, Identity objects, or financial records are changed by migration operations.

docs/AddCategoryNameUniqueness.sql is the generated delta from InitialFinanceSchema to AddCategoryNameUniqueness. It includes the three operations plus standard EF migration-history bookkeeping inside a transaction. It was generated and inspected, NOT executed. It is a forward delta, not an independently idempotent deployment script.

No pending model changes remain after the migration. The generated comparison expression is the approved expression above. The expected worst-case key payload remains about 124 bytes.

## Verification results

| Check | Result |
| --- | --- |
| Complete solution build | PASS: zero warnings, zero errors |
| Focused category uniqueness checks | PASS: 15 passed, zero failed |
| Existing/extended schema checks | PASS: 47 passed, zero failed |
| Authentication service regression | PASS: 27 passed, zero failed |
| Finance HTTP regression suite | BLOCKED before execution by missing NuGet dependencies |
| API security HTTP regression suite | BLOCKED before execution by NuGet restore/network errors |
| EF pending-model-changes check | PASS: no model drift |
| New migration operation inspection | PASS: exactly three intended category operations |
| Unfiltered index / single final COLLATE SQL inspection | PASS |
| Protected initial artifacts and Domain SHA-256 comparison | PASS: unchanged |
| Isolated SQL Server persistence/indexability/constraint/concurrency validation | NOT EXECUTED |
| PersonalFinanceDb migration or record changes | NONE |

A total of 89 checks passed in the three executable suites above. This does not include blocked HTTP suites or claim live SQL validation.

The focused suite exercises exact trimming, generated SQL query translation, normalized shadow-property lookup, inactive duplicates, null/default/private scopes, owner/type separation, self-update, authorization/ownership, referenced-category rename/type/deletion protection, synthetic 2601/2627 conflict translation after write-scope unwinding, and absence of blanket duplicate handling.

EF InMemory does not calculate SQL computed columns or emulate SQL collation. A test-only SaveChanges interceptor supplies boundary-trimmed shadow values. These tests certify service flow and exact-name behavior only; they do not certify case/accent/Unicode collation behavior or actual database enforcement. Synthetic SQL exceptions are created with test-only reflection and contain no real database details.

The finance HTTP suite has ten added checks, including simulated provider errors through actual MVC responses and rename/history preservation. They are implemented but not claimed executed. Existing passing HTTP authorization results from earlier stages are not presented as fresh regression results.

Authentication fixture repair: restoring stale framework assets exposed a missing Data Protection provider in the console-only test fixture after the existing Infrastructure configuration gained default Identity token providers. The fixture now registers an ephemeral provider. This is test-host setup only; production Identity/password/JWT configuration is unchanged. Authentication regression then passed all 27 checks.

The cached Identity.UI 10.0.14 reference already present in the API brings additional dependencies to console test restore. Finance restore reported unavailable Microsoft.Extensions.Identity.Stores and Microsoft.Extensions.FileProviders.Abstractions; API security reported NU1301. Network access to api.nuget.org is socket-blocked. A local restore was requested; production package versions were not modified to bypass the problem. Agent builds use process-local NuGetAudit=false and the existing cache; no repository NuGet configuration was changed.

Reproduce offline-capable checks:

~~~powershell
dotnet build PersonalFinancePlatform.slnx
dotnet run --project tests/PersonalFinance.CategoryUniquenessTests
dotnet run --project tests/PersonalFinance.SchemaValidation
dotnet run --project tests/PersonalFinance.AuthenticationTests
~~~

After completing local restore, run both blocked suites:

~~~powershell
dotnet restore tests/PersonalFinance.FinanceTests/PersonalFinance.FinanceTests.csproj
dotnet restore tests/PersonalFinance.ApiSecurityTests/PersonalFinance.ApiSecurityTests.csproj
dotnet run --project tests/PersonalFinance.FinanceTests
dotnet run --project tests/PersonalFinance.ApiSecurityTests
~~~

These are existing console-style test projects; dotnet test alone does not execute their checks. No SQL integration flag was passed.

## Isolated SQL validation: proposed operation requiring approval

No isolated test connection is configured. No database was created, connected to, migrated, or populated for SQL integration validation.

Proposed exact database name: PersonalFinanceCategoryTests_12B1

After separate explicit approval, the proposed operations are:

1. Create only that separate database on SQL Server 2025.
2. Set its database collation to SQL_Latin1_General_CP1_CI_AS and compatibility to 170.
3. Verify the connection target is exactly the isolated database, never PersonalFinanceDb.
4. Apply InitialFinanceSchema and AddCategoryNameUniqueness only there.
5. Insert dedicated test users/categories/financial references only there.
6. Exercise direct SQL and EF/API category writes, including concurrent submissions.
7. Inspect computed metadata, index definition, DML behavior, rollback, safe 409 responses, and preserved relationships.
8. Retain the test database for inspection. Deleting it would require separately authorized cleanup.

No step above has been performed. Creating this database or applying any migration requires further approval.

Required isolated metadata results:

~~~sql
SELECT
    cc.name,
    cc.is_persisted,
    cc.definition,
    c.max_length,
    c.collation_name,
    COLUMNPROPERTY(cc.object_id, cc.name, 'IsDeterministic') AS IsDeterministic,
    COLUMNPROPERTY(cc.object_id, cc.name, 'IsPrecise') AS IsPrecise,
    COLUMNPROPERTY(cc.object_id, cc.name, 'IsIndexable') AS IsIndexable
FROM sys.computed_columns AS cc
JOIN sys.columns AS c
  ON c.object_id = cc.object_id AND c.column_id = cc.column_id
WHERE cc.object_id = OBJECT_ID(N'dbo.Categories')
  AND cc.name = N'NormalizedName';

SELECT name, is_unique, type_desc, has_filter, filter_definition
FROM sys.indexes
WHERE object_id = OBJECT_ID(N'dbo.Categories')
  AND name = N'UX_Categories_UserId_Type_NormalizedName';
~~~

Expect persistence/determinism/precision/indexability = 1; 100-byte nvarchar maximum; the approved comparison collation; unique nonclustered index; has_filter=0 and filter_definition=NULL.

Also verify required SET options on both migration and actual SqlClient write connections. SSMS session results do not by themselves certify application-connection behavior.

Required isolated cases include case differences, all 25 boundary characters, ordinary/interior whitespace distinctions, accents and canonical-equivalent Unicode forms, null owners, inactive rows, cross-owner/type allowances, self-renames, conflicting renames, direct-SQL bypass attempts, concurrent private/default submissions, rollback, and preserved financial references.

## Remaining blockers and exact next steps

1. Complete local restores and execute the two HTTP suites. Fix only relevant failures.
2. Obtain approval to provision PersonalFinanceCategoryTests_12B1 and perform the listed isolated operations.
3. Validate the exact persisted expression/index and real SQL writes. No live determinism, precision, indexability, rollback, or concurrency claim is made yet.
4. Review the new migration SQL and isolated results.
5. Before any development-database application, pause category writes, back up the database, and repeat the approved duplicate/normalized-empty/metadata checks; prior clean results are time-specific.
6. Obtain explicit approval to apply AddCategoryNameUniqueness to PersonalFinanceDb.
7. Apply only through that separately approved operation, then activate the corresponding source version and repeat manual Categories API tests.

Source implementation is ready for review and isolated validation. Application to PersonalFinanceDb is NOT approved or performed, and manual category writes using the updated source must wait for a migrated target.
