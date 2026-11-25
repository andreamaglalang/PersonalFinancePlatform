# Savings SQL companion: offline skeleton only

Stage 8B-5G-5B creates source only. It has not been compiled, restored, tested or executed.
The standalone .NET 10 project is outside the solution. It has no PackageReference,
ProjectReference, linked test files, SQL client reference or application startup dependency.
Its name describes a future purpose, not implemented live functionality.

## Only entry point

After separate build/execution authorization, the sole supported argument sequence is:

```text
--mode validate-policy-offline --policy <local-policy.json>
```

Exit 0 means only that an offline-validation declaration passed syntax, policy consistency,
and its proposed expiration window. Exit 2 means rejection. The runner refuses every live mode,
extra argument, reordered option and even a structurally valid live-phase record passed through
the offline-validation entry point. No declared command is dispatched or executed.

The runner reads one fixed-local-drive file, bounded to 65,536 bytes. It rejects observed
reparse points, UNC/device paths, alternate streams and network drives. It writes only console
diagnostics and the input hash, never a file. No application settings, user secrets, connection
environment variables, LocalDB process discovery, SQL connection or background task is used.
Filesystem link-race resistance is best effort and is not a live execution authorization boundary.

## Strict JSON schema, version 1

Names are case-sensitive camelCase; every positional-record constructor field must be present,
including nullable `frozenServer` and `database` fields, which must explicitly be null if unknown.
Missing/unknown/duplicate properties, wrong types, null nonnullable values, numeric enums,
noncanonical enum spellings, comments, trailing commas, depth over 12 and oversized input fail.
All SHA-256 declarations require exactly 64 uppercase hexadecimal characters.

Root fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Exactly 1 |
| `phase` | One exact phase below |
| `runId` | Nonempty GUID, matching target declaration |
| `manifestSha256` | Declared original manifest byte hash; manifest is not opened here |
| `approvedTargetIdentity` | Immutable proposed/frozen/bound target declarations |
| `reviewedArtifacts` | Exactly one companion executable, Infrastructure and Domain fingerprint each |
| `expectedStartingState` | Phase-specific state and exact approved migration prefix |
| `expiration` | UTC issued/not-before/expires timestamps |
| `approvedCommand` | Exact executable filename, run-specific working directory and argument vector |

The words `approved` and `reviewed` are field labels, not independently verified facts.
There is no approval boolean, signature-shaped field, credential, connection string or arbitrary
command field. Such additional fields fail strict parsing. A supplied hash/GUID/run marker
establishes neither provenance nor permission. The parser returns a declaration, never a
verified-target instance or execution capability. Capability interfaces have no implementations.

### Identity declarations

`approvedTargetIdentity` contains `runId`, `sqlServerInstance`, `windowsOwner`, `localDbOwner`,
`expectedSqlServerVersion`, `databaseName`, `approvedDataPath`, `approvedLogPath`, `frozenServer`
and `database`. Both owner objects contain `computerName`, `accountName` and `sid`.
Pins match the existing offline target policy:

- Instance `(localdb)\MSSQLLocalDB`, version `17.0.4025.3`.
- Computer `OWLI-FIXTURE`, account `OWLI-FIXTURE\developer`, SID
  `S-1-5-21-111111111-222222222-333333333-1001`.
- Database exactly `PersonalFinanceSavingsTests_<run GUID in lowercase N format>`.
- Data/log files exactly under `D:\PersonalFinanceSavingsSqlTests\<run GUID in D format>`,
  named `<database>.mdf` and `<database>_log.ldf`.

Wildcards, aliases, unexpected owners/instances/paths, `PersonalFinanceDb`, attachment,
failover and connection overrides cannot be expressed as valid target values. There is no
connection builder. No owner check is actually performed against the current process or server.

Optional `frozenServer` has `namedPipe` and `internalSqlIdentity`. The proposed syntax is
`np:\\.\pipe\LOCALDB#<alphanumeric token>\tsql\query` paired exactly with
`OWLI-FIXTURE\LOCALDB#<same token>`; this is declaration consistency, not discovery.

Optional `database` has `databaseGuid`, `databaseId`, `createdAtUtc`, `actualFiles`, `runMarker`,
`migrationIds`, `manifestSha256` and `executableSha256`. GUID must be nonempty, ID greater than 4,
creation time UTC and no later than issuance, run marker equal the run GUID, and hashes/migration
state equal the enclosing declarations. The proposed provisioning layout has exactly data file
ID 1 and log file ID 2, with logical names `<database>`/`<database>_log` and exact declared paths.
Each file has `fileId`, `logicalName`, `kind` (`data`/`log`) and `physicalPath`.
These layout assumptions require review before any provisioning implementation; they are not
observations of an existing database. System catalogs are never queried by this skeleton.

### Artifact, time and command policy

Each artifact has `role`, `fileName`, `sha256`. Exact roles/files:

- `companion-executable`: `PersonalFinance.SavingsSqlLiveTests.dll`.
- `infrastructure`: `PersonalFinance.Infrastructure.dll`.
- `domain`: `PersonalFinance.Domain.dll`.

Files are not opened or hashed here. The skeleton does not yet reference the production DLLs.
`expiration` requires `issuedAtUtc <= notBeforeUtc < expiresAtUtc`, all zero-offset UTC, a
maximum proposed one-hour issuance window, and a supplied validation time within the window.
Pure parsing accepts validation time as an argument; the entry point uses actual UTC, not the
financial tests' fixed clock. Time checks cannot prove approval freshness or prevent replay.

`approvedCommand` contains `executableFileName`, `workingDirectory`, `arguments`. Filename is
exactly the companion DLL; working directory is exactly
`C:\OWLI-Fixtures\PersonalFinancePlatform\artifacts\SavingsSqlLive\<run GUID in D format>`.
No directory is accessed or created. Offline declared arguments are exactly:

```text
--mode validate-policy-offline --policy authorization.json
```

Live declared arguments are exactly the following token vector, with `<phase>` from the table:

```text
--mode <phase> --manifest manifest.json --authorization authorization.json
```

Those live commands are reserved declarations, NOT supported runner commands. Unknown filenames,
extra tokens, alternate ordering, shell fragments and executable/directory overrides fail.
The current inspection invocation's policy path is not treated as an approved command invocation.

## Separate phase declarations and prerequisites

`expectedStartingState` contains `state` and `migrationIds`.

| Phase | Starting state; required declarations |
| --- | --- |
| `offline-validation` | `unverified-target`, no database binding/history |
| `live-read-only-preflight` | `unverified-target`, no database binding/history |
| `provision-database` | `absent-database`, frozen server, no database binding/history |
| `apply-bounded-migrations` | Bound database and frozen server; `bound-empty-database` with empty history, or `migration-prefix` with first 1/2 IDs |
| `run-deterministic-tests` | `ready-for-deterministic-tests`, full binding and all 3 IDs |
| `run-coordinated-concurrency-tests` | `ready-for-concurrency-tests`, full binding and all 3 IDs |
| `cleanup-database` | `retained-database`, full binding and an exact history prefix |

Only these ordered migration prefixes are valid:

1. `20261031023901_InitialFinanceSchema`
2. `20261031115500_AddCategoryNameUniqueness`
3. `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`

Declaring one phase does not declare the next. Even a consistent live record is rejected by
this entry point. No provisioning, migration, SQL test registration, verified-target methods,
connection construction, cleanup, approval issuance, run journal or phase resumption exists.

## Future approval boundary

Before live code, separately approve a concrete identity-verification implementation, approval
provenance mechanism, artifact/manifest verification, one-use run journal, exact commands, live
database binding and phase-specific operation. Recheck real owner/SID, already-running instance,
pipe, database GUID/files/run marker and transaction state before operations. Use bounded
timeouts and cancellation, no account switching/elevation, implicit startup, fallback or retries.
Cleanup needs separate approval and identity verification. A failed or interrupted phase must
never automatically authorize resumption, another phase or deletion.

The existing SavingsSqlTests runner/guard and 35 dormant cases remain untouched. This project
does not compile-link them. No live policy declaration overrides that runner's offline-only rule.
