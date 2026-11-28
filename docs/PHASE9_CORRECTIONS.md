# Phase 9 — targeted corrections (28 November 2026)

Local candidate: `fix/phase9-targeted-corrections`, based on remote `dev`
`10668d07c009545bc90f5c41721cd4bef47975f5`. Not approved, published or deployed.
Earlier handoff results retain their original scope. Detailed progress, commands,
red/green evidence and final verification are saved outside Git by the owner.

## Corrected contracts

- MVC refresh accepts authenticated members and administrators, validates token
  shape/expiry and same-owner replacement, and retains serialized rotation and
  fail-closed handling. Administrator authorization remains at its existing boundaries.
- Ordinary transaction DELETE requires `?version=<64 uppercase hex>` from the row
  the user saw. Missing/malformed: 400; stale: 409; missing/unowned: 404; protected
  bill payment: 409. The financial write lock rechecks the row before deletion.
- MVC posts its confirmation page's version without replacing it at submit.
  Android sends the selected transaction's version and refuses invalid versions locally.
- Deletion atomically inserts an owner/ClientId identity in `DeletedTransactions`,
  removes the row and recalculates budgets. A create with that deleted identity,
  even with altered data, returns 409 without a write. Other owners/new identities
  remain independent. Android retains the rejected queue entry for review, stops
  automatically retrying it and does not announce successful synchronization.

## Migration and coordinated release

The candidate has **six** migrations. New migration:
`20261128102603_RetainDeletedTransactionIdentities`.

It adds an empty table: owner/ClientId primary key, retained transaction ID,
UTC deletion instant, no-cascade owner FK and check constraints. It does not rewrite
existing financial/account/notification history. Down intentionally refuses to drop
permanent retry safeguards. Do not purge deletion identities.

This protects deletions performed by the corrected API after migration. Absent
records deleted before installation cannot yield reconstructible ClientIds; review
legacy pending requests at cutover instead of assuming retrospective protection.
Existing migration 4/5 guards remain intact. Use the verified-target backup, restored
copy and bundle procedures in [deployment](../deployment/README.md). No existing
database migration is authorized by this candidate.

API/MVC/Android must ship together: old DELETE clients omit the version and fail
closed. An older API ignores the retained identities; do not roll back to it with
writes enabled. Preserve the migrated database and post-backup writes; use a
compatible recovery build. Never drop the table or blindly restore a stale backup.
Worker hosting, schedules and separate storage are unchanged. No new secrets,
Cloudflare configuration or Room migration is required by these corrections.

## Android quality and branding

ComponentActivity's supported dispatcher and existing Compose BackHandlers replace
legacy diagnostic-only key/back interception. API 27 theme attributes are qualified.
Test flow transformations are remembered; converter state is collected with lifecycle
awareness. The unused constraints scope is a plain Box. No blanket lint baseline.

The adaptive launcher icon reuses every existing `ic_owli_mark` path and OWLI colors.
No logo redesign, application ID/signing change or device installation is included.
CI now includes both lint variants and instrumentation-source compilation alongside
the existing APK/JVM checks. Compilation does not imply device test execution.

Warning review: retain SDK36 and pinned dependencies per current scope. Dependency
updates, unused resources and KTX/style suggestions do not authorize refactoring.
Resource-reactivity/localization warnings remain review items. Release-only themed
monochrome launcher support and the redundant minimum-SDK folder qualifier are
optional resource improvements, not failed launcher resolution. `allowBackup=false`
is unchanged; actual transfer/signing/upgrade acceptance remains a receiving-team
check. The pre-existing API nullable warning is not part of these fixes.

## Regression and acceptance boundaries

Tests cover member/admin renewal/security failures, concurrent rotation, stale and
missing delete versions, deleted retries/owner isolation, SQL identity uniqueness,
post-SQL rollback injection, populated migration-five upgrade, Android deletion
transport/rejected offline retry, and launcher resource/path preservation.

No production publish, existing-record changes, APK install, push or merge occurs.
APKs without real release endpoint/signing are verification artifacts only. Use an
explicitly approved commit on `dev` for subsequent deployment, not `main`.

Remaining unrelated gates: D6 administrator scope; actual database identity and
backup/restore rehearsal; public origin availability; release signer/endpoint and
same-key upgrade; full physical/client UI acceptance; remote-deletion cache
reconciliation. These require decisions/verification, not silent scope expansion.

## Verified local candidate

Final numbered checks: Finance463, Authentication34, API security36, Category15,
Schema66, Provisioning40, SQL22, worker14: all pass (690 total, no failures).
MVC/Razor harness (including 18 renewal assertions) and ReportsContract also pass.
Android debug687/release693 JVM tests pass with zero failures/errors/skips.
Debug/release APK assembly, both lint variants and instrumentation-source compilation
pass. Lint: zero errors; debug90/release96 warnings. The final incremental solution
build emitted zero warnings/errors; the pre-existing API nullable warning is not fixed.

Only harness-owned disposable SQL catalogs were migrated, including a populated
five-to-six upgrade and post-SQL deletion rollback injection; cleanup left zero test
catalogs. No physical UI/instrumentation run or production verification is claimed.
Detailed external evidence remains separate from this sanitized source handoff.
