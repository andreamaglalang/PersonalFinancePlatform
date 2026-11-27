# Wiki Log

## [2026-11-26] ingest | OWLI initial approved knowledge
- Disposition: New
- Raw: raw/owli/2026-11-26-approved-requirements.md
- Raw: raw/owli/2026-11-26-application-source.md
- Raw: raw/owli/2026-11-26-storage-preparation.md
- Updated: OWLI architecture and worker decision
- Updated: OWLI financial invariants and storage boundaries
- Updated: OWLI verification limits and architectural decisions

## [2026-11-26] ingest | Agent discovery verification
- Disposition: Update
- Raw: raw/owli/2026-11-26-agent-discovery.md
- Updated: OWLI verification limits and architectural decisions

## [2026-11-26] lint | 0 issues found, 0 auto-fixed
- Evidence checker: 0 fidelity suspects, 0 evidence errors, 0 unreferenced raw files.
- Scope: shared wiki only; no application or database runtime inference.

## [2026-11-26] ingest | PR #1 CI portability corrections
- Disposition: Update
- Raw: raw/owli/2026-11-26-ci-portability.md
- Updated: OWLI verification limits and architectural decisions
- Local build, seven-project handoff suite, focused LF/CRLF and negative-security checks, Android debug/release tasks passed.
- Hosted acceptance remains gated on both jobs at the latest PR head; no merge or application runtime claim.

## [2026-11-26] ingest | Hosted CI acceptance for the fix commit
- Disposition: Update
- Raw: raw/owli/2026-11-26-ci-hosted-acceptance.md
- Updated: OWLI verification limits and architectural decisions
- Run 45817931183: Windows .NET and Ubuntu Android both PASS at fix commit 39533657a0c930b93bfb2f38bab18dbf59280b4c.
- Final documentation successor remains subject to the same latest-head CI gate and owner approval.

## [2026-11-26] ingest | Phase 1 system audit
- Disposition: Update
- Raw: raw/owli/2026-11-26-phase1-audit.md
- Updated: OWLI architecture and worker decision
- Updated: OWLI financial invariants and storage boundaries
- Updated: OWLI verification limits and architectural decisions
- Baseline dev e15d859; offline .NET and Android debug unit suites PASS; SQL/API/device/cross-client/worker NOT TESTED.
- Full record: docs/audits/OWLI_Phase1_Audit.md. No application change or roadmap.

## [2026-11-26] ingest | Phase 1 review amendment
- Disposition: Update
- Raw: raw/owli/2026-11-26-phase1-audit.md
- Updated: OWLI architecture and worker decision
- Added H6: MVC registration requires 200 but the API returns 201 (source-confirmed; runtime pending).
- Carried into Phase 2: Android release endpoint, portable admin provisioning, transaction payload mismatch, contribution retry integrity.

## [2026-11-26] update | Phase 2 roadmap pointer
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- Linked proposed docs/plans/OWLI_Phase2_Roadmap.md (decisions D1–D12, pending approval); roadmap content not duplicated.

## [2026-11-26] update | Phase 2 roadmap approval amendments
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- Approved D1–D5, D7–D9, D11, D12; D6 and D10 remain owner-confirmation items.
- Disposable Hangfire test storage isolated; R4+R5 integrate on one branch; essential BLOCKED/NOT TESTED rows remain incomplete.

## [2026-11-27] update | Phase 3 acceptance record
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- Linked docs/audits/OWLI_Phase3_Acceptance.md; PRs #4-#17 pending review; incomplete essential rows recorded, D6/D10 pending.
## [2026-11-26] update | R2 independent worker
- Updated: OWLI architecture and worker decision
- API Hangfire hosting and DevJobsController removed; PersonalFinance.Worker implemented; HangfireTests 13/13 (7 offline + 6 live) on disposable catalogs. Operational cutover not approved.

## [2026-11-27] update | Phase 4 integration and runtime acceptance
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- Candidate #19 (4c74878) verified; Phase 4 defects fixed in #20-#23; scheduled hourly job fired at 08:00 Manila; ordered PR merge simulation reproduces the candidate. Merges await independent review; D6/D10 pending.

## [2026-11-27] update | Phase 5 deployment handoff
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- D10 resolved: external deployment team deploys via existing Cloudflare; this team delivers verified dev + deployment/README.md handoff (#25). Local readiness and production deployment are separate statuses.

## [2026-11-27] update | Final dev integration
- Disposition: Update
- Updated: OWLI verification limits and architectural decisions
- PRs #12-#25 merged; dev 600244d verified (full .NET/SQL/worker, Android unit+instrumented, device UI, cross-client, MVC rendered). Production deployment remains with the deployment team.

## [2026-11-27] update | Final closeout
- Disposition: Update
- Updated: OWLI verification limits
- Device-verified the Android stored monthly summary and a real FX-provider outage (API, MVC, Android); TalkBack focus order checked on the emulator, speech not captured. The handoff guide now requires a coordinated API+MVC+Android release and a rollback that keeps financial data recorded after cutover. D6 stays open.

## [2026-11-27] update | System-wide validation feedback
- Disposition: Update
- Updated: OWLI verification limits
- One password policy (API/MVC/Kotlin) with live checklists; clients show only allowlisted API reasons (ApiMessages) for 400/404/409; specific amount and field messages; MVC session-expiry notice. Record: docs/audits/OWLI_Validation_Feedback.md.
