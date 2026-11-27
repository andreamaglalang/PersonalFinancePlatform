# Sanitized independent local startup evidence

> Source: OWLI source baseline 9d5038256aebf517ac1d5c6bef6d9b528c03359b; bounded local runtime checks and owner confirmation
> Collected: 2026-11-27
> Published: 2026-11-27

The private runtime file contained a valid JWT key and approved disposable SQL
connections, but ordinary terminals did not source it. API's registered user-secret
store was absent; MVC and Worker had no UserSecretsId. Existing local VS Code tasks
were ignored and called a private ignored launcher. The API's first profile used
HTTP. Historical handoff referenced missing templates and API-hosted Hangfire.

The accompanying corrections register MVC/Worker stores, persist existing valid
JWT and SQL credentials with owner-only store permissions, and choose HTTPS for
ordinary API startup. All three executable projects load their own Development
user secrets. SQL connections validate the exact verified local server public
certificate with TrustServerCertificate=false. Existing HTTPS trust is reused.
No credentials/private keys or SQL container were regenerated/restarted.

Actual verification:

- Root API/MVC dotnet run commands in fresh environments without temporary config
  exports: normal HTTPS 200. Project-folder plain dotnet run after restart: same.
- Synthetic MVC registration and login: expected redirects; authenticated member
  page and API login returned 200. A JWT issued before API restart still worked.
- Owner confirmed both root commands start in fresh VS Code integrated terminals.
  Computer Use was unavailable; debugger UI operation is not independently proven.
- Latest Android debug app built/installed on dedicated emulator. Existing normal
  TLS diagnostic returned HTTP 200 with normalTls=true against the same API.
- Default worker exited disabled, without storage activation. Explicit test profile
  rejected an operational fixture before network access. Its read-only validation
  checks disposable pairing, actual catalogs/instance, SQL encryption/certificate
  validation, schema version 9 / eleven tables, and absence of another registered
  worker. No schema provisioning is performed.
- Independent worker started on the existing approved disposable catalogs: one
  server, three recurring definitions, zero queued jobs. Graceful shutdown removed
  registration. Restart repeated the same state; test worker was stopped afterward.
- Full solution build passed with zero warnings/errors. Complete nine-project
  offline handoff suite passed with live SQL test variables absent. Focused worker
  checks passed eight offline cases; optional live SQL harness was not invoked.
- Bounded operational finance/Hangfire schema/row-count metadata matched before/after.
  No operational writes, database creation/reset/migrations, production/Cloudflare
  access, unrelated process termination or worktree removal occurred.

New reusable templates/native tasks are reviewed for publication. Independent
review's concrete findings (locally excluded VS Code files and incompatible browser
ready action) were corrected. Final remote/PR checks are reported with publication.
Actual SQL endpoints, catalog suffixes, credentials, synthetic identity values,
certificate fingerprints, private paths and job payloads are excluded.

Limits: no native Windows/Linux runtime, physical-device or debugger-UI proof;
no administrator provisioning or operational worker activation. Public-certificate
pin changes require reverified target identity; validation failures are not bypassed.
These setup checks do not establish full financial/application runtime acceptance.
