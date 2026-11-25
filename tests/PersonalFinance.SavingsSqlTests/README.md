# Savings SQL tests: offline foundation only

Stage 8B-5G-1 implements source only. Compilation and execution have not been authorized.
This standalone .NET 10 console project is not in the solution. It references the existing
Infrastructure project and adds no PackageReference or startup host.

## Only implemented command

After separate compilation/execution approval, from the project root:

```powershell
dotnet run --project tests/PersonalFinance.SavingsSqlTests/PersonalFinance.SavingsSqlTests.csproj --no-build --no-restore -- --mode validate-offline --manifest <local-manifest.json>
```

Argument order is exact. Missing, repeated, extra or unsupported arguments/modes fail with exit
code 2. Exit code 0 means offline syntax/policy validation succeeded, not permission to execute
SQL or evidence of any live target. This stage does not create an example manifest file.

The runner reads only the specified local manifest, at most 65,536 bytes. It rejects UNC/device
paths, mapped network drives, alternate streams and observed reparse points, does not echo
payloads or connection strings, and prints a
SHA256 of the bytes validated. No files are written. No application settings, environment
connection variables, development secrets or design-time DbContext factories are consulted.

## Strict manifest schema (version 1)

JSON names are case-sensitive camelCase. Every property below is required. Unknown properties,
duplicate properties (including nested ones), comments, trailing commas, null required objects,
invalid types and unsupported schema versions are rejected. No credentials or connection-string
field exists. A numeric or boolean value must have its JSON type, not a string representation.

| Property | Required value/policy |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `runId` | Nonempty canonical lowercase GUID in D format |
| `sqlServerInstance` | Exactly `(localdb)\MSSQLLocalDB` |
| `owner.computerName` | Exactly `OWLI-FIXTURE` |
| `owner.accountName` | Exactly `OWLI-FIXTURE\developer` |
| `owner.sid` | Exactly `S-1-5-21-111111111-222222222-333333333-1001` |
| `expectedSqlServerVersion` | Exactly `17.0.4025.3` |
| `databaseName` | Exactly `PersonalFinanceSavingsTests_` plus this run GUID in lowercase N format |
| `dataPath` | Exactly `D:\PersonalFinanceSavingsSqlTests\<runId>\<databaseName>.mdf` |
| `logPath` | Exactly `D:\PersonalFinanceSavingsSqlTests\<runId>\<databaseName>_log.ldf` |
| `allowedPhases` | Exactly `["validate-offline"]` |
| `migrationIds` | Exactly the three IDs below, in order |

`owner` is an object containing only the three listed identity fields. The instance, owner and
version pins come from the existing DatabaseVerification README; validating these declarations
does not inspect the current account or the running server. No other instance is approved by
this code. The new disposable name/path convention is a conservative offline policy proposal,
not approval to create that database or directory. Any policy change needs separate review.
Literal exact path equality rejects traversal, aliases, alternate streams and wildcard targets.
A name containing `Test` alone never passes. `PersonalFinanceDb` is explicitly prohibited.

`connectionPolicy` is a required object containing exactly these required properties:

| Property | Required value |
| --- | --- |
| `authentication` | `"WindowsIntegrated"` |
| `transport` | `"VerifiedExistingNamedPipe"` |
| `encrypt` | `true` |
| `trustServerCertificate` | `true` (pinned local development transport policy only) |
| `pooling` | `false` |
| `enlist` | `false` |
| `multipleActiveResultSets` | `false` |
| `allowAttachFile` | `false` |
| `allowFailover` | `false` |
| `allowFallback` | `false` |
| `allowImplicitLocalDbStartup` | `false` |
| `connectTimeoutSeconds` | Integer `10` |
| `commandTimeoutSeconds` | Integer `10` |
| `connectRetryCount` | Integer `0` |

Migration sequence:

1. `20261031023901_InitialFinanceSchema`
2. `20261031115500_AddCategoryNameUniqueness`
3. `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`

The migration list is declarative validation only. No migration discovery, SQL generation,
SQL execution or unrestricted update-to-latest operation is implemented.

## Future live phases are deliberately unavailable

There are no provision, inspect-live, migrate, test, cleanup or startup-smoke modes. Extra
allowed phases in the manifest are rejected. The validated manifest is plain data and conveys
no live execution capability. There is no SQL connection, process launch or mutation dispatcher.

Before future live code can be introduced, separately approve its exact source and commands.
It must independently verify server owner/SID, version, existing instance state and frozen
named-pipe endpoint without starting LocalDB. Provisioning requires an approved absent target.
After creation, bind and approve the actual database GUID, creation identity, exact data/log
files and run marker. Revalidate that identity before mutations on each operational connection.
Use bounded timeouts and cancellation, no silent retries/fallback, and fail closed on drift.
Cleanup requires separate approval and exact identity revalidation; never adopt an existing
database by name prefix or automatically drop a failed run's database.

The offline manifest hash is an evidence identifier, not a signature or human approval proof.
Approval provenance and live database binding remain future prerequisites. Offline validation
cannot establish database absence, NTFS permissions, path collisions, server identity, schema
state, SQL connectivity or disposal safety. Local manifest path checks are best-effort reads,
not a defense against a hostile process racing filesystem changes. None of those offline
checks may be reused as authorization for a future live operation.
