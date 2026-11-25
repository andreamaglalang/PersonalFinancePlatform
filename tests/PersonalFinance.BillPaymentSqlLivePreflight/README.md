# Bill-payment live-preflight review packets — Stage 2B

Stage 2C closed as offline-verified: 315/315 review-packet checks passed at the
reported checkpoint. Stage 2D-1 architecture design is complete.
At the original Stage 2D-2 source milestone, its observation definitions had not
been restored, compiled or executed. See the dated verification amendment below.
Stage 2D-4 hardening is SOURCE ONLY and has not been built or executed.
Fourth migration remains UNSAFE TO APPLY.

## Capabilities and commands

This standalone net10.0 console project is outside the solution. It has no
PackageReference or ProjectReference, SQL client, EF Core, process launch,
filesystem/identity/discovery adapter, database connection, SQL text, provisioning,
migration runner or cleanup. Existing Stage 1 and offline validators are untouched.

No arguments or --mode info prints information. --mode self-test-offline is
reserved for separately authorized offline tests. Every other argument vector,
including both proposed review operations, is rejected by the CLI.
No review packet file, connection string, endpoint or SQL is accepted by it.
Parsing accepts in-memory bytes only; no files are opened.

## Procedural human approval — Approach B

The human independently reviews exact packet bytes, command and artifact set,
explicitly approves one invocation, and manually invokes the exact reviewed
command in normal non-elevated PowerShell during a FUTURE separately approved
stage. Any changed artifact, endpoint, command or repeat requires new approval.

This executable does not authenticate that human interaction. ProposedReviewPacket,
ReviewedConsistencyBaseline and ObservedConsistency are distinct immutable data
contracts, not capabilities or provenance proofs. Both result flags
HumanApprovalAuthenticated and LiveExecutionAuthorized are always false.
A successful result cannot authorize discovery, a SQL connection or database
writes. No signature verifier, signer, approval issuer or automatic dispatcher
exists. Matching a self-supplied baseline establishes consistency, not authority.

## Packet and consistency policy

Strict camelCase JSON parsing rejects missing, unknown, duplicate, null-required
or incorrectly typed fields, comments, trailing commas, non-object roots and
oversized/deep payloads. Limits: 65,536 bytes and depth 12. Nullable endpoint must
still be explicitly present. Packet hash covers exact input bytes, not a
re-serialized approximation.
The parsed packet/hash pair has private construction through parsing only;
callers cannot replace the packet while retaining its previous byte hash.
After rejecting empty or oversized input, parsing takes one private byte-array
snapshot. Document parsing, deserialization and SHA-256 hashing all use that same
snapshot. Later mutation of caller-owned memory cannot change the parsed pair.
The snapshot copy itself is not an atomic read of concurrently modified memory;
the caller must not mutate input while handing it over. This change gives every
parsing/hash stage the same privately owned bytes.

The typed policy checks:
- Schema 1; canonical nonempty lowercase D-format request/run GUIDs.
- Exactly discovery-only-review or endpoint-bound-metadata-review.
- Exact PersonalFinanceBillPaymentTests_<run:N> name and run-derived MDF/LDF paths
  under D:\PersonalFinanceBillPaymentSqlTests\<run:D>\.
- Explicit absent target; no adoption and no PersonalFinanceDb.
- Historical expected Windows and LocalDB owner declarations:
  OWLI-FIXTURE, OWLI-FIXTURE\developer,
  S-1-5-21-111111111-222222222-333333333-1001.
- MSSQLLocalDB and 17.0.4025.3 as historical expectations, not observations.
- Discovery has no endpoint; metadata requires a local, nonwildcard named pipe
  and corresponding exact internal server identity.
- Exact executable/output directory, host path and ordered argument vector.
- Exact three-artifact inventory: DLL, deps.json and runtimeconfig.json; positive
  bounded byte lengths and uppercase SHA-256 identities. Host length/hash are
  independently declared. Extra dependencies are currently unsupported.
- Query-set declaration appropriate to the operation and matching baseline.
- UTC chronology, maximum one-hour review window, valid current time, fixed
  10-second connect/command declarations and overall timeout of 10–60 seconds.
- Explicit writes/fallback/retries/automatic-progression prohibitions.
- Matching external baseline and observed manifest/artifact/host/endpoint/query
  declarations.

Artifacts are relative leaf paths only. The fixed proposed output directory is
the Debug net10.0 directory for this project. Another configuration requires
separate source review, not an arbitrary executable override.

## Separate approval checkpoints

1. Discovery-only human review: expected owner/instance/version; no SQL.
2. Review actual discovery evidence, then independently approve an exact frozen
   named pipe/internal identity and absent-target metadata query set.

No discovery result automatically approves a connection. Endpoint replacement
requires fresh review. TESTONLY02 in tests is explicitly synthetic, never an
observed or authorized endpoint.

The exact command vectors inside packets are PROPOSED future review data.
The Stage 2B CLI does not execute or support them. Future packet loading and
manual invocation binding require another design; the current validator cannot
launch its own proposed command.

## Offline tests and limitations

Definitions cover both valid scopes, required JSON fields, duplicates, wrong
types, runs/targets/paths/identities, artifact changes, command mismatch, windows,
endpoint omission/substitution, write prohibitions, CLI rejection and sanitized
results. Unrelated exceptions do not count as an expected parser rejection.
No test reads a real artifact, process, identity, endpoint, filesystem or server.

ObservedConsistency is currently supplied by test fixtures, not real adapters.
Artifact lengths/hashes and query identities in fixtures are synthetic.
There is no actual reviewed SQL query set in this stage. A matching declaration
does not verify query content.

The actual .NET runtime, native dependencies, executable path identity and
replacement races remain outside this consistency model. Host hashing alone
does not attest the runtime dependency closure. Artifact hashing, filesystem
identity and live transport enforcement remain future work.

Expiry uses an injected observation time. It does not prevent clock rollback
or replay within a valid window. No durable one-use ledger exists.
The review baseline must be independently fixed by the external human workflow;
this program cannot establish its provenance or defend against a malicious
operator able to replace the program.

## Stage 2C-1 coverage maintenance

Existing tests and rejection categories are retained. New definitions cover
otherwise-valid JSON padded to exactly 65,536/65,537 UTF-8 bytes; a literal packet
with a fixed independently calculated SHA-256; caller mutation after parsing;
nested fields; artifact entries and identities; host observations; command
replacement/reordering; request canonicalization; UTC/timeout boundaries;
endpoint identity mismatch; full endpoint failure results; explicit info mode.

Boundary-success fixtures deliberately refresh their synthetic baseline packet
hash after changing the packet. This is test setup, not an approval issuer or
production baseline mechanism.

No independent depth-limit claim is made. Excessively nested input would also
violate the strict schema, and JSON failures share one rejection category.
Depth limits remain source-inspected, not independently isolated by these tests.
No parser seam or exception-classification change was introduced.

## Next approvals

The verified 406-check Stage 2D-2 checkpoint predates Stage 2D-4. Any existing
restore/build outputs do not verify the changed sources. Restore, build and
offline self-tests require explicit approval; no restore is part of this stage.
The project declares no external package dependencies.

Real artifact observers, approval workflow wiring, discovery, SQL dependencies,
fixed queries and any live invocation each require separate review/authorization.
Provisioning, migrations, payment tests and cleanup remain separate future
write-capable phases. No phase automatically authorizes another.

## Stage 2D-2 offline discovery evidence model

ObservationContracts.cs separates expected declarations from simulated Windows,
instance/process-generation, file and launch observations. All values are immutable
records/ImmutableArray collections. DiscoveryResult approval and live-authorization
flags are computed constants: both are false on success and failure. Validated
evidence is point-in-time synthetic consistency, never authenticated human approval.
These contracts do not extend schema 1 or turn review packets into capabilities.

ObservationPolicy.cs is pure and fail-closed. It checks identity/SID, impersonation,
elevation, exact owner/instance/version, running/private state, local named-pipe
syntax/internal identity, process generation, closure hashes/lengths/physical file
identities, ancestor/volume/alias declarations, ordered launch arguments, host,
runtime, environment/closure completeness, UTC windows and elapsed budgets.
Expected evidence is itself checked for missing/duplicate closure identities,
unsupported operation, malformed fields and invalid review/budget bounds.

DiscoveryCoordinator.cs requires all four observers and the budget through explicit
constructor injection. There is no production factory or metadata dependency.
Its exact successful read sequence is:

windows, instance, artifacts, launch, windows, instance, artifacts, launch.

The two instance snapshots must match, including PID and creation marker. Artifacts
and launch are checked against the expected closure twice. Every boundary checks
cancellation, expiry, monotonic elapsed budget and backward clock movement.
The coordinator is single-use and owns the supplied observers and budget; callers
must supply distinct, exclusively owned resources and must not reuse them.
Cleanup attempts launch, artifacts, instance, windows, then budget in that order,
even when a prior disposal fails. Cleanup failure rejects success. Review/deadline
validity is checked again after all cleanup using a coherent post-disposal timing
snapshot, followed by a final cancellation sample, as described below.

The injected budget provides a fake deadline task; no real timer, identity lookup,
file reading, runtime/module inspection, LocalDB operation or SQL operation exists.
An unfinished observation races cancellation/deadline signals. Pending work gets
cancellation and observer disposal; late evidence is ignored and late task faults
are observed without publishing their messages. This does not forcibly terminate
an uncooperative observer, prove real handle retention or bound native cleanup.
Future adapters require separate cancellation, resource-ownership and isolation
review. Constructor dependency rejection transfers no resource ownership.

ObservationFakes.cs uses deliberately synthetic FAKE-HOST identity, SID, instance,
pipe, X:\FAKE paths, hashes, file IDs, process markers and runtime identity. It never
reads those paths or discovers those objects. ObservationTests.cs registers through
the existing offline test harness without removing existing checks. Definitions
assert exact read/cleanup traces, rechecks, failure categories, sanitized results,
authority flags, boundary windows/budgets, exceptions, cancellation, uncooperative
pending work, late success/fault, single-use behavior and disposal failure.
The later 406/406 checkpoint below covers Stage 2D-2. No Stage 2D-4 passing-count
claim is made until separately authorized offline execution.

Two High-severity live-activation blockers remain unresolved:

1. Independent real observation and endpoint/physical-identity binding, including
   substitution races and correlation of the actual transport with the server.
2. Execution integrity across the host, runtime/native dependency closure and
   launch environment; supplied consistency does not authenticate human approval.

These summarize the outstanding activation areas; fake assertions resolve neither.
Actual adapter/API behavior, runtime loading, filesystem locking, endpoint identity,
clock trust and replay prevention remain unverified. A matching pipe string is not
server authentication. No durable one-use ledger or approval authenticator exists.

No live discovery, SQL connection, migration, provisioning or automatic progression
is authorized. PersonalFinanceDb is never accessed. The fourth migration remains
UNSAFE TO APPLY.

After separate approval, verification commands from the project root would be:

```powershell
dotnet build tests/PersonalFinance.BillPaymentSqlLivePreflight/PersonalFinance.BillPaymentSqlLivePreflight.csproj --no-restore
dotnet run --project tests/PersonalFinance.BillPaymentSqlLivePreflight/PersonalFinance.BillPaymentSqlLivePreflight.csproj --no-build --no-restore -- --mode self-test-offline
```

These commands were not executed during the original Stage 2D-2 source-only
implementation or during Stage 2D-4. Missing/stale restore assets
must be reported; package restore needs separate authorization. Passing offline
checks would verify the model only, not authorize live operations.

## Verification amendment — 2026-11-07

The supplied checkpoint reports separately authorized Stage 2D-2 offline restore
and build success, followed by 406 passed, 0 failed, 0 skipped. This incorporates
the prior 315 review-packet checks and the added observation checks. The other
separate suites report 245 and 84 passing checks, for 735 total reported checks;
do not add 315 again. These results were not rerun during this source-only stage.
The original source-only milestone above is retained as historical context.

## Stage 2D-4 source-only hardening — 2026-11-07

M1: ISimulatedBudget.Capture returns one coherent timing sample and remains readable
after disposal. Observers, budget, cancellation registration and linked token source
are cleaned up before terminal timing validation. Success requires disposed budget
state, no deadline signal, elapsed time below budget, a valid UTC review window and
no backward movement relative to the last observation. The post-cleanup terminal
capture is the precise point-in-time completion decision point. Cancellation is
sampled immediately before capture and conservatively again after validation.
Later deadline events do not retroactively revoke that snapshot. No further budget
getters or observer calls occur. Cleanup failures always reject success.
The terminal sample must include changes made during budget disposal; disposal must
not freeze a pre-cleanup sample. Tests include a separate budget implementation,
not derived from the normal fake, with disposal-time timing changes.

M2: Invalid host-path tests now update the matching closure reference. Duplicate
physical-identity tests preserve distinct valid paths and the complete inventory.
A separate path-uniqueness case preserves distinct physical identities. Each case
asserts InvalidExpectation and an exact zero-observation/cleanup trace.

M3: All observation-coordinator test waits use an independent five-second harness
watchdog (Task.WaitAsync), unrelated to the injected discovery deadline. On expiry,
the harness releases its owned fake promises, observes later task faults and throws
TimeoutException for the existing Check wrapper to count as a failure. A focused
watchdog self-test uses an otherwise uncompleted promise and a short independent
timeout. No Task.Run, worker process, process termination or native timer adapter
is introduced. The harness timer only protects asynchronous waits; it cannot
interrupt synchronous blocking before a Task is returned, a blocking callback or
blocking disposal. Such behavior remains prohibited for these controlled fakes
and requires separate design before any real adapters exist.

L1: Expected account identities reject empty/whitespace, nested and control-character
suffixes. Account SIDs must be canonical S-1-5-21 followed by exactly four decimal
UInt32 components: ASCII digits, no signs/leading zeros, bounded lengths and no
numeric overflow. Zero and UInt32 maximum boundaries are tested. This validates
syntax/range only; it does not resolve or authenticate a real Windows account.

L2: DiscoveryResult is sealed with private construction and get-only properties.
Success requires nonnull instance evidence with a UTC observation time; rejection
requires a defined non-None failure and always drops evidence. Approval and live
authorization remain computed false on every result.

L3: The dated amendment records the reported 406/406 checkpoint without claiming
verification for these new changes. Additional definitions cover multi-resource
disposal failure, cancellation during cleanup, deadline/cancellation ties, null
nested evidence, later-stage pending/late observers, exact success evidence and
aliased dependency rejection. Existing review tests and CLI restrictions are intact.

Deferred: exhaustive scheduler interleavings, synchronous blocking containment,
all recheck-stage pending permutations, null-task observers and broad fuzzing remain
outside this focused slice. No real hashing, file handles, runtime inspection,
identity discovery or transport identity has been implemented or verified. The two
High-severity live-activation blockers remain open. No live operation or migration
is authorized; the fourth migration remains UNSAFE TO APPLY.

## Stage 2D-4 verification and Stage 2D-5 closure amendment — 2026-11-07

The supplied verified checkpoint records a successful .NET 10 build and Stage
2D-4 offline results of 458 passed, 0 failed, 0 skipped. The earlier independent
suites report 245 and 84 passing checks, giving 787 reported offline checks in
total. The 458 count supersedes the earlier combined 406 count; do not add them.
No build or tests were rerun while appending this amendment in Stage 2D-6.

Stage 2D-5 completed its read-only post-fix security audit and recommended closing
Stage 2D-4 as offline-verified. All six earlier findings were resolved within the
modeled offline lifecycle, with no new Critical, High or Medium finding. Its one
nonblocking documentation follow-up is addressed by this verification amendment.
Earlier source-only statements are retained as historical implementation status
and are superseded by this amendment for the current verification checkpoint.

Both High-severity live-activation blockers remain open: independent real evidence
and endpoint/physical-identity binding, and host/runtime/native dependency and
launch integrity with the external human-approval boundary. Offline verification
does not authenticate human approval, implement real observers, or authorize live
discovery, SQL connections, provisioning, migrations or automatic progression.
No live execution is authorized. The fourth migration remains UNSAFE TO APPLY.

## Stage 2D-7 offline binding source implementation — 2026-11-07

Status: source implementation only. Build/test verification: NOT EXECUTED.
The reported 458/458 Stage 2D-4 baseline and 787 checks across the three separate
suites remain historical checkpoints; they do not verify this new source.

New files: BindingContractsV2.cs, BindingParserV2.cs, BindingPolicyV2.cs,
InvocationBindingPolicy.cs, BindingFakes.cs and BindingTests.cs. The existing
ReviewPacketTests.cs registers BindingTests after the unchanged observation tests.
Program.cs, project files, schema 1 and existing observation/coordinator behavior
are unchanged. All proposed operation vectors remain rejected by the CLI.

ReviewScopeV2 (schema 2), InvocationDescriptorV1 (schema 1) and
ObservationEnvelopeV1 (schema 1) use the explicit policy profile
offline-synthetic-binding-v1. They bind canonical request/run/invocation/nonce
identities, the exact absent test target and paths, historical Windows/LocalDB
expectations, artifact inventory, runtime/dependency profile, command vector,
review window, budgets, external review requirements and operation-specific lineage.
Expected and observed Windows/file/runtime/launch contracts are distinct types.
Historical account/path values are expectations; all matching fixture observations,
lengths, hashes, physical identities, process markers and endpoints are synthetic.

The identity chain uses exact scope bytes and their length/SHA-256 in the
descriptor; exact descriptor bytes and their length/SHA-256 are bound by the
envelope. The separately supplied BindingReviewAnchor fixes both byte identities.
Scope arguments are the exact reviewed prefix (including invocation ID/nonce).
The descriptor appends only --scope-sha256 and the scope digest to produce the
exact final vector. It has no self-hash field or self-referential hash argument.
Whitespace changes reviewed identities; no canonical reserialization is hashed.
Manifest and runtime-profile digests are reviewed declarations, not hashes of
independently acquired manifest/profile files. Exact scope hashing commits their
fields, and observed inventories are compared item by item. No artifact is read.

Parsing accepts in-memory bytes only and retains private immutable snapshots.
Bounds: 65,536 input bytes, JSON depth 12, 2,048 UTF-16 code units per string,
64 code units per property name, 64 elements per JSON array, 32 arguments/files/
dependency paths and 16 missing/unsupported-field labels. Unknown/duplicate/missing
properties, wrong types, numeric/unknown/noncanonical enum spellings, malformed
IDs/hashes, unsupported versions and locally contradictory data reject. Nullable
scope endpoint/lineage fields must still be explicitly present. Callers must not
mutate input during the snapshot copy; the copy itself is not an atomic concurrent
read. Parsed value/hash pairs cannot be replaced through construction or with.

Discovery scopes prohibit frozen endpoints and metadata lineage, and declare zero
connect/command budgets. Metadata-review scopes require exact endpoint/generation
expectations, a prior discovery-report identity and distinct review references.
These lineage/query identities are declarations fixed by review, not validation of
prior report contents, a query implementation, or proof of either human review.
Envelope instance endpoint/generation must match its recheck and any frozen scope.
There is no connection, query set, metadata operation, real observer or dispatcher.

The pure policy checks the byte chain, anchor, exact vectors, identity/owner/version,
running/private state, manifest/inventory/physical identities, retained-handle and
runtime assertions, completeness, UTC freshness, elapsed budgets and terminal
cleanup/cancellation/deadline/late-completion outcomes. Elapsed ticks are relative
to acquisition start (zero) and this deliberately strict synthetic profile requires
UTC durations to equal modeled elapsed durations exactly. That is not a real clock
correlation guarantee or native cancellation/disposal implementation.

BindingResult is privately constructed, has get-only status and permanently false
HumanApprovalAuthenticated and LiveExecutionAuthorized flags. Evidence-source
classification has only SyntheticOnly; strings claiming independent or live origin
are rejected, not promoted. An invocation ID, nonce, matching anchor, review
reference or consistent replacement bundle cannot authenticate approval. If an
operator replaces the external anchor as well as all matching data, consistency
alone cannot detect that replacement; no durable one-use/replay ledger exists.

Offline definitions cover both operations; exact bytes and post-copy mutation;
schema/required/duplicate/unknown/type and size bounds; hash-chain and otherwise
consistent replacements; isolated invocation/vector/quoting mismatches; artifact,
runtime and dependency changes; endpoint/generation drift; stale/contradictory time;
missing/unsupported evidence; synthetic-promotion and escalation attempts; terminal
failures, result invariants and unchanged CLI rejection. These are definitions,
not passing results. Existing fake lifecycle/watchdog registrations are preserved.

Deferred: exhaustive parser fuzzing and scheduler interleavings, real OS/file/pipe
identity, retained-handle enforcement, actual runtime closure/hash acquisition,
native cleanup/timeout containment, authenticated approval and durable replay
prevention. The two High-severity activation blockers remain open: independent
real evidence/endpoint/physical-identity binding, and host/runtime/native dependency
and launch integrity with the external human-approval boundary.

No human approval is authenticated. No live execution is authorized. No SQL client,
SQL text, process launcher, application startup, package dependency, file loader or
automatic progression is introduced. The fourth migration remains UNSAFE TO APPLY.
Separate authorization is required before any offline build or self-test execution;
passing those checks would still provide no live authorization.

## Stage 2D-9 targeted binding-test correction — 2026-11-07

Historical verification amendment: the supplied Stage 2D-7 checkpoint records a
successful .NET 10 offline project build and 715 passed, 0 failed, 0 skipped.
The prior 458 checks are included within 715. The separately reported suites have
245 and 84 passing checks: 715 + 245 + 84 = 1,044. Do not add 458 again.
These results predate the Stage 2D-9 source changes and were not rerun here.
Earlier source-only descriptions are preserved as historical milestone records.

Stage 2D-8 completed its read-only security audit. It found no new Critical or
High issue, one Medium inventory-bound test-isolation gap, and one Low stale
verification-documentation issue. The 32-file limit was present in policy; the
old 33-file fixture also violated uniqueness and mandatory-role requirements,
so it could not independently detect removal of the inventory bound.

BindingTests.cs replaces that fixture with direct scope-parser boundary cases:
exactly 32 files must be accepted; exactly 33 files must reject with InvalidInventory.
Both retain host/entry/deps/config/runtime roles, distinct canonical file paths,
distinct valid modeled physical identities, valid lengths and SHA-256 values,
and matching runtime/dependency references. The 33-file case has only 29 dependency
references, below their 32-entry bound; both inventories are below the generic
64-element JSON-array bound and the serialized input byte limit. Each case also
checks an accepted positive control formed by removing its last dependency and
the corresponding reference. No descriptor, envelope or anchor comparison can
mask these direct-parser inventory assertions.

Additional focused definitions independently reject duplicate paths while keeping
physical identities distinct, and duplicate physical identities while keeping
paths distinct. Their mandatory roles remain intact and dependency references
are rebuilt consistently. A metadata case first validates its unchanged fixture,
then replaces all three observed endpoint copies with one matching synthetic
endpoint while leaving process generations and the frozen reviewed endpoint
unchanged. It must reject with InvalidEndpoint solely for the frozen-endpoint
mismatch, rather than disagreement among observed endpoint copies.

An isolated depth-12 rejection with otherwise schema-valid input is deferred.
These fixed contracts have shallow, nonrecursive nesting: exceeding depth 12
requires an unknown property or an incorrectly typed nested value. The existing
deep unknown-property fixture is retained and explicitly labeled generic malformed
schema coverage, not an independent depth-limit proof. No parser seam or production
policy change was introduced to manufacture such a test. The depth bound remains
source-inspected; a separate parser-level test seam would need independent review.

Stage 2D-9 verification status: NOT EXECUTED. No restore, build or tests were run.
The Medium finding is addressed in source pending separately authorized offline
verification and focused re-review. This amendment addresses the stale historical
verification record without claiming passing results for the new definitions.
Only BindingTests.cs and this README were changed. Production contracts, parser,
policies, coordinator, CLI, project files and authorization invariants are unchanged.

Both existing High-severity live-activation blockers remain open: independent real
evidence and endpoint/physical-identity binding, and host/runtime/native dependency
and launch integrity with the external human-approval boundary. No human approval
is authenticated. No live execution is authorized. No real observer, SQL client,
process launcher, connection or live dispatcher is introduced. The fourth migration
remains UNSAFE TO APPLY.

## Stage 2D-11 final offline security-closure amendment — 2026-11-07

This amendment records the subsequent verification and review milestones while
preserving all earlier source-only implementation records as historical context.
Build and test results below are user-supplied verification results, not results
independently reproduced during the read-only audits or this documentation stage.

- Stage 2D-7: 715/715 offline checks passed.
- Stage 2D-8: independent security audit completed, identifying one Medium
  regression-coverage finding and one Low documentation finding.
- Stage 2D-9: targeted correction completed, followed by a user-reported successful
  .NET 10 build and 719 passed, 0 failed, 0 skipped.
- Stage 2D-10: independent read-only security audit completed. The previous Medium
  finding was resolved through isolated 32/33 inventory-boundary tests with valid
  mandatory roles, unique identities and consistent dependency references. The
  previous Low documentation finding was also resolved.

Stage 2D-9 is closed as an offline-only, statically security-reviewed correction
with user-reported offline verification. No new Critical, High, Medium or Low
implementation defects were identified in the post-correction audit. The depth-12
test limitation remains informational and deferred: the fixed, shallow schema
cannot exceed that depth without another schema violation. Depth enforcement is
source-inspected rather than independently isolated by a schema-valid deep input.

The two existing High-severity live-activation blockers remain OPEN:

1. Real observation and physical identity binding: independent live Windows/LocalDB
   observation, endpoint-to-process and physical-file identity binding, and adequate
   replacement-race protection remain unimplemented or independently unverified.
2. Runtime integrity and authenticated external approval: live host/runtime/native
   dependency integrity, launch integrity and an authenticated external human-approval
   boundary sufficient for activation remain independently unverified.

Combined separately reported baseline: 719 + 245 + 84 = 1,048 passing checks.
The earlier 715 and 458 preflight counts are included within 719 and must not be
added again. This total combines three separately reported suites; it is not a
newly executed combined test run.

HumanApprovalAuthenticated = false.
LiveExecutionAuthorized = false.
Offline consistency validation and security closure do not authorize live discovery,
SQL/LocalDB access, provisioning, migrations or automatic progression. No approval
mechanism, CLI dispatch or authorization boundary is changed by this amendment.

Stage 2D-11 build/test status: NOT EXECUTED. No build, restore, test, application,
live observer, SQL connection, SQL command or migration was executed in this stage.
Live execution status: NOT AUTHORIZED.
The fourth migration, 20261107054048_AddBillPaymentReceiptsAndGuards, remains
UNSAFE TO APPLY.
