# Bill-payment SQL preflight — Stage 1

SOURCE ONLY: not restored, compiled, executed or tested in this stage.
The fourth migration remains UNSAFE TO APPLY.

## Boundary

This standalone net10.0 executable is outside the solution. It has no package
or project references, EF Core, SQL client, production configuration or DI.
There are no real Windows identity, LocalDB, filesystem or SQL adapters.
No SQL text, connection construction, provisioning, migrations or cleanup exists.

The default invocation and --mode info only print information.
--mode self-test-offline is reserved for separately approved fake test execution.
All other argument vectors are rejected. No manifest, approval file, connection
string, endpoint or SQL command is accepted by the console.

## Architecture

PreflightContracts.cs contains immutable records and ImmutableArray migration
declarations. Expected targets, scope declarations, approval observations,
Windows/LocalDB/pipe/filesystem/SQL observations, absence evidence and results
have distinct types. Mutable calls and fault controls exist only in OfflineFakes.

PreflightPolicy.cs checks the exact run, target paths, phase, identities,
endpoint relationship, four migration declarations, artifacts and validity
window. Scope validation precedes every observer. Approval scope must match all
expected fields. Hashes are uppercase SHA-256-shaped strings; they are not read
from executable or manifest files in Stage 1.

PreflightOrchestrator.cs receives four observer interfaces and a clock. Only
OfflineFakes.cs implements them. Each successful fake run observes Windows,
LocalDB, filesystem, metadata, then LocalDB again. Evidence must match and the
target must be authoritatively absent in the simulated metadata. Results contain
only a bounded status, failure enum and sanitized text, never connections or
write-capable tokens.

## Approval is simulated, never live authority

ApprovedPreflightScope is a declaration of what an independently approved scope
would contain, not proof that someone approved it. OfflineApprovalObservation
accepts only provenance offline-test-simulation. A record claiming human approval
is rejected. The internal request mode is simulate-read-only-preflight; the
declared future phase is live-read-only-preflight, but that name enables no live
behavior. Every result is explicitly marked OfflineSimulationOnly.

No factory issues a live capability. Public CLI arguments cannot construct a
scope. A successful simulation cannot authorize a database write, or even a real
preflight connection. Real approval provenance, artifact verification and
phase-specific invocation binding require a separate reviewed implementation.

## Target and historical identity declarations

Database: PersonalFinanceBillPaymentTests_<canonical run GUID in N format>.
MDF/LDF paths: D:\PersonalFinanceBillPaymentSqlTests\<run GUID in D format>\
with exact <database>.mdf and <database>_log.ldf names.
Only absent-target state is supported. PersonalFinanceDb and every unrelated
name are rejected. An existing matching name cannot be adopted.

Historical expected declarations are MSSQLLocalDB on OWLI-FIXTURE,
account OWLI-FIXTURE\developer, SID
S-1-5-21-111111111-222222222-333333333-1001 and version 17.0.4025.3.
They were inspected in the earlier offline bill/savings policy sources.
They are not current observations.

No real endpoint is invented. TESTONLY01 is synthetic fixture data, never a
discovered or authorized pipe. Policy checks local pipe syntax and matching
internal identity; fake discovery must match the simulated approved expectation.
Future discovery must independently establish and freeze the current endpoint.

## Evidence limitations

For nonexistent MDF/LDF paths, physical identity can only concern existing
ancestors, not nonexistent files. The corresponding observation explicitly
refers to ancestor physical identity. Fakes assert completeness, fixed volume,
no aliases/reparse redirection and file absence. No actual filesystem or
race-resistance claim follows.

SQL observations are typed fake data. No master connection, catalog query,
permission inspection, migration-history query or actual authenticated-principal
observation is performed. Empty target history means not applicable for an
absent target, not verified migrations. Unknown visibility rejects absence.

Timeout uses a bounded asynchronous wait and cancellation; tests include a
noncompleting fake. Cancellation cannot forcibly terminate an uncooperative
future adapter. Stage boundaries recheck cancellation before proceeding.
Only fake interfaces exist here; actual adapter cancellation and resource
disposal would require separate design and verification.

## Offline test definitions

Cases cover successful absent-target sequencing, approval absence/mismatch,
expiry/windows, artifact identities, canonical run IDs, database/path rejection,
historical expectations versus fake observations, instance running/private state,
endpoint drift, filesystem completeness, visibility and absence, contradictory
history/target/principal observations, fake exceptions, cancellation and timeout.
Each rejection asserts its exact failure class and exact allowed call trace.
Successful flow asserts no further dispatch. CLI tests reject unsupported/live
operations and check sanitized diagnostics.

These tests are definitions only until separately authorized execution.
They do not establish real SQL syntax, transaction behavior, permissions,
server identity, LocalDB encryption, filesystem identity or database safety.

## Future work requiring independent authorization

1. Offline project restore, build and fake self-tests; no external packages are
   needed, but a new project needs its own restore assets.
2. Detailed approval provenance and read-only live-adapter design.
3. Separately reviewed SQL-client dependency and adapters, without application
   configuration fallback or implicit instance startup.
4. Explicitly approved live preflight invocation and read-only evidence scope.
5. Separate provisioning/migration/test/cleanup designs and approvals.

No phase automatically authorizes another. No permission changes, existing
database operations or PersonalFinanceDb access are authorized by this project.
