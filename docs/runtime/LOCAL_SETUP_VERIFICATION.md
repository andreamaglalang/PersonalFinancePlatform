# Independent local startup verification - 27 November 2026

Application baseline: dev `9d5038256aebf517ac1d5c6bef6d9b528c03359b`.
Task branch: `fix/local-development-startup`. Scope: persistent local development
configuration, safe startup profiles/native IDE tasks, focused host configuration
guards and portable onboarding. No UI, finance, auth-policy or API-contract changes.

## Root cause and correction

The private runtime.env file held valid working settings, but only terminals that
sourced it inherited them. API had a UserSecretsId but no populated store; MVC and
Worker had no registrations. The API defaulted to its first, HTTP launch profile.
Existing local VS Code tasks depended on an ignored machine-specific launcher; the handoff
also referenced absent templates and obsolete API-hosted Hangfire instructions.

Registered MVC/Worker stores and reused existing local JWT/SQL credentials.
Configured per-project owner-only secret stores with processing disabled. Selected
HTTPS for ordinary API startup. Existing HTTPS trust was valid and was reused.
SQL's existing private connection bypassed certificate checking; the corrected
local settings validate the exact public certificate obtained from the verified
local SQL instance, with TrustServerCertificate=false. No SQL/container/certificate
private-key or credential regeneration was required.

The explicit DevelopmentTest worker profile checks disposable catalog pairing,
actual connected identity, certificate validation, compatible preprovisioned schema
and absence of a registered worker before activation. The ordinary worker remains
disabled. Native VS Code tasks/debug configurations replace the ignored launcher.

## Acceptance record

| Check | Result |
| --- | --- |
| Latest origin/dev, clean baseline and preserved other worktrees | PASS |
| Existing local SQL instance and intended disposable catalog connectivity | PASS, authenticated and certificate validated |
| Root-level API/MVC dotnet run commands in fresh environments without config exports | PASS, HTTPS 200 and normal certificate verification |
| Plain dotnet run from each application project folder after graceful restart | PASS, HTTPS 200 |
| Synthetic member registration and login through MVC, authenticated member page, API login | PASS |
| JWT issued before API restart | PASS, still accepted after restart with persistent key |
| Latest Android debug build/install and existing HTTPS probe on dedicated test emulator | PASS, HTTP 200 / normalTls=true to the same API |
| Default worker | PASS, disabled exit 0 without storage activation |
| Explicit test profile with operational fixture | PASS, refused exit 2 before network access |
| Independent worker on validated existing disposable storage | PASS, one server / three recurring definitions / zero queued jobs |
| Graceful test-worker shutdown | PASS, server registration removed |
| Worker restart | PASS, same one-server/three-recurring/zero-queue state; subsequently stopped |
| Fresh VS Code integrated-terminal startup | PASS, owner confirmed both documented commands start; Computer Use unavailable, debugger UI not independently exercised |
| Complete solution build and offline regressions | PASS, full build and complete nine-project offline suite |
| Operational preservation | PASS, bounded operational schema/row-count metadata matched before/after; no operational writes initiated |
| Independent source review | Findings corrected: native VS Code files explicitly included; unsupported browser auto-open removed |
| Publication privacy | PASS, reviewed files contain no private configuration or identity values; remote verification recorded in the PR after publication |

No database was created/reset, no migration was applied, no production/Cloudflare
infrastructure accessed, and no unrelated process/worktree was stopped or removed.
Synthetic registration/login and worker operational records were limited to the
existing approved test catalogs. Real identity values, credentials, job payloads,
SQL host details, certificate fingerprints and private paths are excluded here.

Limits: native Windows/Linux end-to-end runtime and physical-device testing are not
established by this macOS/emulator evidence. No administrator was provisioned and
operational Hangfire activation remains unapproved. Offline regression checks do
not establish full financial concurrency, deployment or application-wide acceptance.

The user independently confirmed both documented root commands start in fresh
VS Code terminals. Computer Use permissions were unavailable, so native debugger
UI launch was not independently exercised. The review found no critical issues;
its publication/configuration findings were corrected before commit.

The verified SQL certificate pin must be reverified if the server certificate
changes (for example after rebuilding local infrastructure). It intentionally
fails on a different certificate. Do not enable TrustServerCertificate or disable
HTTPS checks to bypass that failure. Existing SQL-container identity and volume
were preserved; no restart was required for this setup.

Independent follow-up confirmed the two review findings are resolved: both native
VS Code JSON files are explicitly included despite the unchanged local exclusion,
and integrated-terminal launch profiles contain no unsupported serverReadyAction.

Final storage guard regression: both finance and jobs connection strings with
AttachDbFilename are rejected before opening SQL connections, so validation cannot
implicitly attach/provision a database. Both refusal fixtures and the valid
configuration case pass in the focused offline worker checks.
