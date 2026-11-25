# Bill-payment SQL tests: offline policy slice

This standalone net10.0 console project is outside the solution. It has no
PackageReference, ProjectReference, EF Core, SQL client, application configuration,
production DI, connection constructor, fixture, provisioner, migration executor,
LocalDB startup or cleanup implementation.

## Current verification status

Previous verified baseline: build succeeded with zero errors and zero warnings;
offline self-tests passed 230/230, with zero failures and zero skipped checks.
The subsequent audit identified coverage and documentation improvements.

Current coverage-improvement verification: build succeeded with zero errors and
zero warnings using --no-restore. Offline self-tests passed 245/245, with zero
failures, zero skipped checks and exit code 0 using --no-build --no-restore.
The previous 230 checks retain their substantive assertions; 15 checks were added.
No SQL Server integration has occurred. The fourth migration remains
UNSAFE TO APPLY.

## Only supported entry points

After separately approved restore/build:

```text
--mode self-test-offline
--mode validate-offline --manifest D:\review\manifest.json
```

Arguments are exact and order-sensitive. There is no default or live mode.
Exit 0 means offline declarations passed (or all self-tests passed); exit 1
means a test or unexpected validation failure; exit 2 means rejected arguments,
manifest or file access. Self-tests use in-memory JSON/streams and never write
temporary files. No sample manifest is created.

## Manifest contract

All fields are required, camelCase and case-sensitive. Only schemaVersion 1 is
allowed. Unknown fields, nested duplicates, comments, trailing commas, null
required values, wrong JSON types and depth above 8 are rejected. Maximum input
is 65,536 bytes. No connection-string, named-pipe endpoint or database-binding
field is accepted.

- runId: nonempty canonical lowercase D-format GUID.
- sqlServerInstance: exactly (localdb)\MSSQLLocalDB.
- owner.computerName: exactly OWLI-FIXTURE.
- owner.accountName: exactly OWLI-FIXTURE\developer.
- owner.sid: exactly S-1-5-21-111111111-222222222-333333333-1001.
- expectedSqlServerVersion: exactly 17.0.4025.3.
- databaseName: PersonalFinanceBillPaymentTests_ followed by this run ID in N format.
- dataPath: D:\PersonalFinanceBillPaymentSqlTests\<runId:D>\<databaseName>.mdf.
- logPath: D:\PersonalFinanceBillPaymentSqlTests\<runId:D>\<databaseName>_log.ldf.
- databaseState: exactly unverified-target.
- allowedPhases: exactly ["validate-offline"].
- migrationIds: exactly the four ordered IDs below.

The instance, owner, SID and version pins are historical declarations inspected
in SavingsSqlTests/SqlTestTargetGuard.cs. They are independently pinned here;
manifest values cannot redefine them. Matching declarations do not establish
current server identity, endpoint, database absence, filesystem safety, database
ownership or human authorization.

No named-pipe endpoint is invented. VerifiedExistingNamedPipe is a transport
requirement, not an observed endpoint. A future independently approved preflight
must verify the already-running instance and owner and freeze its actual pipe.
A later approved creation phase must bind the real database GUID, ID, creation
identity, files and run marker. No matching name permits adoption.

## Connection-policy declarations

connectionPolicy requires:
authentication WindowsIntegrated; transport VerifiedExistingNamedPipe;
encrypt true; trustServerCertificate true; pooling false; enlist false;
multipleActiveResultSets false; allowAttachFile false; allowFallback false;
allowFailover false; allowImplicitLocalDbStartup false;
connectTimeoutSeconds 10; commandTimeoutSeconds 10; connectRetryCount 0.

TrustServerCertificate is the explicitly retained local-development declaration,
not a general remote-server certificate policy. No connection is constructed.

## Exact migration declarations

1. 20261031023901_InitialFinanceSchema
2. 20261031115500_AddCategoryNameUniqueness
3. 20261103141326_AddSavingsGoalContributionLedgerAndRowVersions
4. 20261107054048_AddBillPaymentReceiptsAndGuards

This list is policy data only. No migration discovery, generation or execution
is implemented.

## Filesystem boundary and diagnostics

Target data/log paths are compared literally without probing or creating them.
Manifest files must have an ordinary absolute fixed-local Windows path.
Traversal syntax, redundant separators, UNC/device paths, alternate streams,
wildcards and observed reparse points are rejected. These checks do not
comprehensively reject aliases such as 8.3 short names or establish physical
file identity. Files are opened read-only,
bounded and without sharing writes. Checks cannot establish race-free path
identity against a hostile local process. Fixed-drive and actual reparse checks
are not exercised by the in-memory self-tests.

Diagnostics do not print payloads, paths or raw exception messages. A manifest
SHA-256 identifies only the bytes validated; it is not a signature or approval.

## Coverage interpretation

The over-depth array and object-root cases establish general rejection only.
The strict schema has no otherwise-valid deeply nested field, and the public
parser maps JSON errors to BillSqlPolicyException. These tests do not
independently prove MaximumDepth enforcement or its exact boundary. No parsing
seam or exception-classification change was introduced.

The duplicate-root case starts with an accepted manifest before adding one
duplicate property. The exact 65,536-byte parser acceptance case pads a valid
manifest with legal JSON whitespace. Direct null-manifest rejection and bounded
short-read stream behavior are tested in memory.

Injected reader exceptions test CLI exit classification and safe diagnostics.
They do not test actual operating-system permission failures, fixed-drive
detection, reparse resolution, file sharing, physical identity or race safety.
No temporary files are used. Test labels use fixed names or deterministic
indices, not randomized string hashes.

## Restore and future approvals

The project-specific restore assets were available for the previously verified
warning-free --no-restore build. This project has no package dependencies.
If restore assets are absent in another checkout, a project-specific offline
restore requires separate authorization. No restore is part of this coverage
update. Do not copy another project's restore assets.

Future live verification, target creation, migrations, financial tests,
permissions and cleanup require separately reviewed source and explicit
authorization. No offline result grants any live capability. Existing savings
policies, production code, solution and historical migrations remain untouched.
