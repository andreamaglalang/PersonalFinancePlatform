# OWLI verification limits and architectural decisions

> Sources: Project owner, 2026-11-26; guarded Phase 0 SQL preparation, 2026-11-26
> Raw: [Approved requirements](../../raw/owli/2026-11-26-approved-requirements.md); [Storage preparation evidence](../../raw/owli/2026-11-26-storage-preparation.md); [Agent discovery](../../raw/owli/2026-11-26-agent-discovery.md); [CI portability](../../raw/owli/2026-11-26-ci-portability.md); [Hosted CI acceptance](../../raw/owli/2026-11-26-ci-hosted-acceptance.md); [Phase 1 audit](../../raw/owli/2026-11-26-phase1-audit.md)
> Updated: 2026-11-26

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

## Decisions and remaining acceptance work

The independent console requirement and initial selection of `FinanceJobs` are
approved. No job activation or application correction is approved in Phase 0.
Keep shared requirements/contracts as authoritative references, not duplicate
wiki specifications. Clarify summary immutability/recomputation, direct Development
job execution, persisted summary consumption, operational cutover and portable
approved disposable tests during the audit. The Phase 1 audit adds four
decisions: overdue-bill reminders, budget deletion after alerts, administrator
cross-user scope, and notification mark-read/summary read contracts. These are
not approved scope. Proposed defaults for these and earlier open decisions
(D1–D12) are in the [Phase 2 roadmap](../../../plans/OWLI_Phase2_Roadmap.md),
pending owner approval. Verified there: transactions have no rowversion, and
notifications have a foreign key only to the user. Require fresh SQL/retry/lifecycle,
API, rendered-client and bidirectional consistency evidence for acceptance.

Use Confirmed/Suspected/Proposed/Implemented/Fixed/Verified/Blocked/Not tested
labels accurately. Preserve old evidence as historical when new source supersedes
it. Runtime flags, production readiness and current client parity are not inferred
from source, schema readiness or historical passing tests.

## See Also

- [Architecture and worker decision](architecture.md)
- [Financial invariants](financial-invariants.md)
