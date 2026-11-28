> **Historical record (Phase 2, 20 November 2026).** Superseded by

> **Current release identity and evidence:** see [final closeout](../docs/FINAL_HANDOFF.md). The generated Phase2 manifests/hashes below remain unchanged historical artifacts and do not certify current production packages.
> [the Phase 3–4 acceptance record](../docs/audits/OWLI_Phase3_Acceptance.md) and the
> [deployment handoff guide](README.md). Kept for traceability only.

# OWLI Phase 2 offline validation — November 20, 2026

## Exact source and integration

- Workspace <deployment-worktree>, branch deployment/owli-server.
- MVC cab4e2b61c3707b8bff2a8f4d018fa69da4ae565; fetched origin/feature/mvc-admin-dashboard still points here.
- Verified backend e454262b2c371dcc98631735ada36cc9df0cf948; fetched origin/feature/backend-hangfire-demo exactly matches user-approved commit.
- Common ancestor e843462186e913e09437ffa04e9483fd31824788. MVC change set adds MVC/UI tests and changes solution membership; backend changes API/Infrastructure/backend tests. No changed paths overlap.
- git merge --no-commit --no-ff e454262b2c371dcc98631735ada36cc9df0cf948 completed without conflicts in deployment only. The reviewed 20-file source integration was finalized as merge ad126680fb9e55a6a1da6f73348b7a3bbc307bf2 with exactly the MVC and backend parents. No source functionality changed during finalization; no reset/stash/discard performed.
- Integrated API/Infrastructure/finance/security tests equal backend commit; MVC/UI tests equal MVC commit (git diff comparisons empty). Only deployment artifacts are independently authored.
- Original six Phase 1 files preserved byte-for-byte in evidence/phase1, hashes in evidence/phase1-hashes.json. Old outputs deployment/out unchanged. Current documentation supersedes historical copies.

## Builds and regression results

SDK 10.0.402; builds Release, NuGetAudit=false (dependency vulnerability audit not performed). API, MVC, seven original test projects and ReportsContract all built with 0 warnings and 0 errors. Source dependency restore includes Hangfire libraries, but no Hangfire worker, storage or schema was installed or enabled.

| Suite | Result |
| --- | --- |
| AdminMvc.UiTests | PASS UI, auth/antiforgery metadata, Reports date/auth/payload/error handling |
| AdminProvisioningTests | 22 passed, 0 failed; test stores, no provisioning execution |
| ApiSecurityTests | 27 passed, 0 failed; in-memory middleware/JWT |
| AuthenticationTests | 27 passed, 0 failed; SQL mode NOT RUN |
| CategoryUniquenessTests | 15 passed, 0 failed; synthetic SQL and in-memory simulation |
| FinanceTests | 397 passed, 0 failed; real routing/JWT + in-memory EF, fake FX |
| SchemaValidation | 65 passed, 0 failed; no connection-opening attempts |
| Deployment ReportsContract | PASS authenticated route metadata; actual backend empty/populated DTO -> MVC client, nested budget and savings values; synthetic in-process HTTP handler |

553 numbered checks passed plus unnumbered MVC UI/Reports and deployment contract checks. evidence/phase2/test-summary.json retains exits and summaries; all eight executions exited 0. Detailed build/test/publish logs are retained locally under evidence/phase2 (ignored .log files). Tests used an isolated empty APPDATA user-secret profile, Testing default environment, empty connection override, cleared development thumbprint and Hangfire__Enabled=false. No startup-smoke or SQL switches, listener or shared port 7027. Development-only job logic tested only against synthetic in-memory fixtures, never a scheduler or database.

Reports source/transport contract blocker is resolved. GET /api/reports/monthly requires authentication, first day of month in supported year range, and returns user-scoped base-currency totals/categories/budgets. Actual in-memory API tests cover totals, cross-user isolation, invalid/missing month, authentication and budget status. Actual backend DTO serialization is accepted by the MVC client. SQL translation/schema/constraints, deployment TLS/proxy and browser-to-server end-to-end execution are not certified by these results.

## Publish and configuration

Both direct Release framework-dependent portable publish commands succeeded with --no-restore --self-contained false -p:UseAppHost=false into fresh deployment/out-phase2/api and mvc. 288 output files hashed in evidence/phase2/publish-hashes.json. No server transfer or startup. Phase 1 output was not overwritten. Publish-OWLI.ps1 still parses successfully; Windows execution policy remains unchanged and script orchestration was not executed. Use documented direct commands in fresh folders or an approved signed script.

Published JSON parsed successfully. Forbidden development/local JSON, secrets.json, .env, launchSettings, PFX/private-key, SQL data and backup file extensions: none. Templates have valid distinct variable keys, explicit placeholders only; API now pins Hangfire__Enabled=false and warns to leave OWLI_DEV_CERT_THUMBPRINT unset. Runtime requirements: SQL connection; JWT issuer/audience/key; MVC HTTPS BackendApi__BaseUrl; Production; AllowedHosts; chosen host TLS configuration. No real secret was accessed or copied. Heuristic private-key/GitHub/AWS/JWT pattern scan of publish outputs, templates and contract check found no matches; this is not exhaustive secret detection. No local development settings are included. Source localhost:7027 development certificate mode is opt-in and Production bypasses it; no production localhost API fallback exists.

Backend Hangfire setup returns before storage/server registration when disabled; PrepareSchemaIfNecessary=false even when enabled. Source has no application Migrate/EnsureCreated calls; deployment has no database initialization command. Production /api/dev is rejected before authentication and at the action. Do not enable Hangfire or use development job triggers. Publish includes Hangfire libraries as verified source dependencies, not an enabled job system.

git diff --cached --check reports inherited CRLF-as-whitespace diagnostics; git -c core.whitespace=cr-at-eol diff --cached --check passes. Incoming source bytes were not reformatted or normalized. No conflict markers or unmerged index entries expected; exact upstream-source comparisons pass.

## Rehearsal gates

Not ready for a live server rehearsal yet. Required: actual host/OS/runtime and HTTPS origins, trusted proxy/TLS strategy, secure configuration injection and protected keys, approved compatible non-LocalDB SQL Server schema, designated Administrator account/test data, dependency advisory review, and live execution authorization. Migration 3 remains unapplied; migration 4 unsafe/unapplied. No migrations, database creation/restores, certificate/DNS/resource operations or shared-service changes occurred. Existing SQL model compatibility is unresolved; reports query savings contributions and cannot be assumed to work with the unapplied schema. Additional recurring-bill/payment routes may depend on migration-4 structures. Keep those features unavailable to demonstration use until schema readiness is separately resolved. No merge to shared branches; other worktree files untouched.

## Finalization and publication review

The integration merge is finalized with the approved two parents; its exact commit/tree and validated input blob IDs are recorded in evidence/phase2/source-validation.json. Only documentation, ignore rules and evidence metadata were sanitized afterward. Existing build/test results apply to unchanged source; checksums describe the existing local publish outputs. Tests/builds were not rerun merely for prose and metadata edits.

All six original Phase 1 files remain byte-exact in the ignored local archive. The Phase 1 hash manifest now uses relative paths. Test evidence was reduced to plain suite names, exit codes and message strings, removing PowerShell provider/drive/user metadata. A sanitized build summary replaces raw build logs for publication. Raw logs and generated outputs remain local and ignored. No private certificate, SQL snapshot, populated runtime configuration or secret is intended for GitHub.

NOT YET APPROVED FOR LIVE DEPLOYMENT. Production must explicitly inject Hangfire__Enabled=false; the template does not activate it. Use secure server-side secret injection and consistent Production environment settings. Branch publication does not authorize running the application, jobs or migrations.
