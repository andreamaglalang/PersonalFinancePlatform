# OWLI verification limits and architectural decisions

> **Current closeout:** implementation `ec73858ef54a7323523f32989942220ca53461b1`; [final handoff](../../../FINAL_HANDOFF.md) records current status/evidence. Earlier API-hosting, future-worker or unverified-everywhere descriptions below are historical; production operations remain separately owned.

> Sources: Project owner, 2026-11-26; guarded Phase 0 SQL preparation, 2026-11-26
> Raw: [Approved requirements](../../raw/owli/2026-11-26-approved-requirements.md); [Storage preparation evidence](../../raw/owli/2026-11-26-storage-preparation.md); [Agent discovery](../../raw/owli/2026-11-26-agent-discovery.md); [CI portability](../../raw/owli/2026-11-26-ci-portability.md); [Hosted CI acceptance](../../raw/owli/2026-11-26-ci-hosted-acceptance.md); [Phase 1 audit](../../raw/owli/2026-11-26-phase1-audit.md); [Persistent local startup](../../raw/owli/2026-11-27-persistent-local-startup.md)
> Updated: 2026-11-27

## Verified preparation

Dedicated `OWLI_Hangfire` storage was absent and then created on the verified
existing local SQL target. The official Hangfire.SqlServer 1.8.25 installer
prepared schema version 9 and 11 expected tables. Observed target server, job,
recurring-definition and queue counts were zero. Authenticated connections to
both intended catalogs succeeded. Private future-worker settings are ignored,
owner-restricted and disabled; no existing app configuration changed.

Finance schema/constraint, migration-history and row-count fingerprints matched
before/after; existing container identity/start time/volume mapping matched too.
This is bounded preservation evidence, not a full financial-row checksum or a
claim about unrelated activity. No worker/server/job execution was initiated.
The complete worker and application runtime remain NOT TESTED by preparation.

Both coding agents discovered and loaded the LLM Wiki through native catalogs,
used the single configured index, and recognized ownership and activation limits.
The other four engineering/design skills remained visible. This is instruction
verification, not execution of all optional skill engines or application behavior.

## CI portability acceptance

The initial Phase 0 finalization head had two confirmed hosted CI failures:
obsolete Android SDK `tools` selection and an LF-only MVC antiforgery assertion.
The accompanying corrections explicitly select supported SDK packages and check
actual POST Edit metadata. A demonstrated equivalent shell source assertion now
normalizes line endings. No production controller was changed.

Local full solution build, all seven Test-Handoff projects, focused LF/CRLF and
negative-security checks, and all three Android tasks passed. Debug/release unit
runs counted 596/599 tests without failures or skips. See the new raw CI evidence
for scope, temporary fixture details and original failed-run links. Earlier
instruction/storage readiness did not prove passing CI; that PR-ready claim was
premature. Both hosted jobs subsequently passed on fix commit
`39533657a0c930b93bfb2f38bab18dbf59280b4c` in run `45817931183`,
superseding the earlier failed/pending evidence. Final acceptance still requires
both jobs at the latest PR head, including the documentation-only evidence
successor; PR #1 records the final commit/run. No merge is authorized.

## Phase 1 audit verification

At `dev` `e15d859`:

- The solution build and all seven offline .NET harnesses passed.
- Android `testDebugUnitTest` ran 596 tests with 0 failures.
- `HangfireTests` was BLOCKED; it requires its disposable LocalDB target.
- SQL Server, live API, rendered MVC, device, cross-client and worker behavior
  remain NOT TESTED. No requirement is runtime-verified.

The audit estimates source implementation at about 70% and runtime-verified
functionality at about 0–10%. See the
[Phase 1 audit](../../../audits/OWLI_Phase1_Audit.md).

## Phase 3 runtime evidence

Phase 3 changes are on review-pending PRs #4–#17; none is merged. On disposable catalogs
with synthetic accounts, these were verified at runtime:
- Android ↔ MVC create, edit and conflict, and delete.
- Registration in both directions.
- Durable contribution retry after a real outage.
- Notification read state in both directions.
- Same-cent report parity.
- Worker lifecycle, retry and API isolation.
- Admin role checks.
- MVC WCAG AA checks in both themes.

The two defects R12 found (the shared auth rate limit, and contrast/overflow) are fixed in #16 and #17.
Incomplete essential rows are listed in the [Phase 3 acceptance record](../../../audits/OWLI_Phase3_Acceptance.md).

## Decisions and remaining acceptance work

The independent console requirement and initial selection of `FinanceJobs` are
approved. No job activation or application correction is approved in Phase 0.
Keep shared requirements/contracts as authoritative references, not duplicate
wiki specifications. Clarify summary immutability/recomputation, direct Development
job execution, persisted summary consumption, operational cutover and portable
approved disposable tests during the audit. The Phase 1 audit adds four
decisions: overdue-bill reminders, budget deletion after alerts, administrator
cross-user scope, and notification mark-read/summary read contracts. These are
not approved scope. The [Phase 2 roadmap](../../../plans/OWLI_Phase2_Roadmap.md)
resolves these and earlier open decisions: D1–D5, D7–D9, D11 and D12 are approved,
and D6 (administrator cross-user scope) and D10 (live deployment) still need owner
confirmation. Hangfire tests use disposable `OWLI_HangfireTests_*` storage, never
operational `OWLI_Hangfire`. Essential BLOCKED/NOT TESTED rows remain incomplete. Verified there: transactions have no rowversion, and
notifications have a foreign key only to the user. Require fresh SQL/retry/lifecycle,
API, rendered-client and bidirectional consistency evidence for acceptance.

Use Confirmed/Suspected/Proposed/Implemented/Fixed/Verified/Blocked/Not tested
labels accurately. Preserve old evidence as historical when new source supersedes
it. Runtime flags, production readiness and current client parity are not inferred
from source, schema readiness or historical passing tests.

## See Also

- [Architecture and worker decision](architecture.md)
- [Financial invariants](financial-invariants.md)

## Independent persistent local startup

**Verified:** ordinary root and project-folder API/MVC commands load persistent
Development user secrets without per-terminal exports. Existing credentials and
trusted HTTPS certificate are reused; local SQL uses exact public-certificate
validation rather than TrustServerCertificate bypass. Synthetic MVC registration,
login/member access, API login and JWT reuse across API restart passed. The owner
confirmed fresh VS Code terminal startup; debugger UI was not independently tested.

Android's latest debug app reached the same API with normal TLS. The explicit
DevelopmentTest worker profile validates actual disposable storage before activation;
ordinary startup stays disabled. Test worker start/stop/restart passed with one
server, three recurring definitions and zero queued jobs. No operational activation,
migration, container restart or unrelated worktree/process modification occurred.
The complete nine-project offline suite and full solution build passed. See
[current setup](../../../DEVELOPER_SETUP.md) and
[scoped acceptance record](../../../runtime/LOCAL_SETUP_VERIFICATION.md) for commands,
source-backed evidence and the Windows/Linux/device/runtime limits. Historical
manual environment-loading instructions are superseded for ordinary development.
