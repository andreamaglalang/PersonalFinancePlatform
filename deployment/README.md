# OWLI deployment handoff guide

**Audience:** the external deployment team that hosts OWLI through its existing Cloudflare configuration
(public site: https://www.owli-auf.online/).

**Division of responsibilities (decision D10):**
- The development team delivers a verified, deployment-ready codebase on `dev` and this guide.
- The deployment team owns hosting, Cloudflare, production databases, secrets, signing keys, operational
  Hangfire activation and every production operation.
- Local release readiness and production deployment are **separate verification statuses**. Nothing in this
  repository claims that production deployment or production runtime verification has passed.

**Final implementation source:** `ec73858ef54a7323523f32989942220ca53461b1` on `dev`,
PRs #33–#38 merged; its exact [dev-push CI passed](https://github.com/andreamaglalang/PersonalFinancePlatform/actions/runs/46097900708).
[Final handoff](../docs/FINAL_HANDOFF.md) holds the single receiving-team checklist, owner acceptance,
current limitations and the distinction between implementation and documentation/package commits.
Dated [E2E](../docs/audits/OWLI_E2E_Acceptance.md) and [Phase3–5](../docs/audits/OWLI_Phase3_Acceptance.md)
records retain the evidence for their original test sessions.

**Current authorities:** this production runbook, its publish script and placeholder templates;
[developer setup](../docs/DEVELOPER_SETUP.md) for local development/phone installation;
[worker implementation guidance](../docs/worker-config/README.md#implementation-and-verification-r2);
and the final handoff/checklist. `deployment/VALIDATION.md`, generated `deployment/evidence/`,
original handoff snapshots, old Phase/Stage reports and original-PC deployment notes are historical.
A current section is not made historical merely because it links to dated evidence.

## 1. Components

| Component | Project | Runs as | Talks to |
| --- | --- | --- | --- |
| Web API | `PersonalFinance.Api` | ASP.NET Core (Kestrel, HTTPS) | SQL Server `PersonalFinanceDb`, Frankfurter FX (outbound HTTPS) |
| MVC site (members + administrators) | `PersonalFinance.AdminMvc` | ASP.NET Core (Kestrel, HTTPS) | the API only (server-side calls) |
| Background worker | `PersonalFinance.Worker` | .NET console (Generic Host), **separately supervised** | `PersonalFinanceDb` + dedicated `OWLI_Hangfire` (same SQL instance) |
| Android app | `android/` | release APK | the API's public HTTPS origin |

The API never hosts Hangfire. Jobs run only in the worker.

## 2. Required software

- **.NET 10:** the SDK to build (CI uses 10.0.x; validated on 10.0.400), and the ASP.NET Core 10 runtime on
  every server that runs the API, MVC or the worker. The worker needs the ASP.NET Core shared runtime too,
  because Infrastructure references it.
- **SQL Server:** 2019 or later. Development used 2022. Not LocalDB.
- **Android builds:** JDK 17–25 (CI uses 17; Android Studio's bundled JBR works; **JDK 26 fails** the build)
  and Android SDK platform 36 / build-tools 36.0.0.
- **`cloudflared`:** already operated by the deployment team.
- **Optional:** `dotnet-ef` 10.x to generate or apply migrations (`dotnet tool install -g dotnet-ef`).

## 3. Get the release source

```bash
git clone https://github.com/andreamaglalang/PersonalFinancePlatform.git
cd PersonalFinancePlatform
git switch --detach <accepted-handoff-or-package-commit>
git rev-parse HEAD   # record the full documentation/package SHA
```

The final verified **implementation** is `ec73858ef54a7323523f32989942220ca53461b1`.
The accepted documentation/package commit can be later. Confirm ancestry and inspect its delta:

```bash
git merge-base --is-ancestor ec73858ef54a7323523f32989942220ca53461b1 HEAD
git diff --name-only ec73858ef54a7323523f32989942220ca53461b1 HEAD
```

For this closeout, every changed path must be Markdown. If later changes include code, scripts,
configuration templates, tests or migration files, do not silently reuse this verification;
obtain the corresponding reviewed source/evidence. Keep both SHAs in the release record.

`main` is not the release branch until the owner explicitly approves a `dev` → `main` merge.

## 4. Private configuration (never commit)

Inject values through the host's secret store or the per-service environment. The `*.env.template` files in
this folder list the variables with placeholders only. They are not loaded automatically, and must not be
sourced as shell scripts.

| Service | Variables |
| --- | --- |
| API ([api.env.template](api.env.template)) | `ASPNETCORE_ENVIRONMENT=Production`, `AllowedHosts`, `ConnectionStrings__DefaultConnection`, `Jwt__Issuer`, `Jwt__Audience`, `Jwt__SigningKeyBase64` (≥ 32 random bytes, base64), `CurrencyPolicy__MaximumRateAgeDays`, `Hangfire__Enabled=false`, Kestrel HTTPS URL/certificate |
| MVC ([mvc.env.template](mvc.env.template)) | `ASPNETCORE_ENVIRONMENT=Production`, `AllowedHosts`, `BackendApi__BaseUrl` (the API's HTTPS origin reachable from the MVC server, no path), Kestrel HTTPS URL/certificate |
| Worker ([worker.env.template](worker.env.template)) | `DOTNET_ENVIRONMENT=Production`, `ConnectionStrings__DefaultConnection` (PersonalFinanceDb), `ConnectionStrings__Hangfire` (`OWLI_Hangfire`, same instance), `Hangfire__Enabled` (`false` until activation, see §9) |
| Android build | `owliApiBaseUrl` Gradle property (public HTTPS API origin) and the signing keystore, both held by the deployment team |

Rules:
- Never put SQL credentials or JWT keys into MVC or Android.
- Leave `OWLI_DEV_CERT_THUMBPRINT` unset.
- Use a least-privilege SQL login for runtime, and a separate privileged login only for migrations.
- Arrange an approved production administrator account/access procedure before acceptance. The
  repository's interactive administrator CLI is **Development-only** and restricted to LocalDB
  or disposable `OWLI_Runtime_*` targets. It is not a production bootstrap tool; never relabel a
  production target as Development or remove guards to make it run. For a fresh production DB,
  owner/DBA-approved Identity provisioning is a receiving-team prerequisite, not an automatic seed.


## 5. Database backup and migration

The schema has 5 EF Core migrations. The applications never migrate on startup.

Choose the target branch **before** running anything:

- **Existing data:** follow backup/restore rehearsal below; verify connected server/catalog and
  migration history first. Quiesce all application writes for the final backup/cutover (§7).
- **Fresh production initialization:** the receiving DBA creates an approved empty SQL Server
  database and least-privilege runtime/separate migration logins on the verified instance.
  Confirm it is empty and is the intended target, then apply the same reviewed migration bundle
  below, verify all five history entries and required roles/reference data, and start with no
  imported development/synthetic records. No LocalDB, sample database, demo seed or owner's
  private backup is a production prerequisite. Arrange approved administrator access separately (§4).

The commands below are receiving-team instructions, not operations performed by this closeout.


1. **Back up** `PersonalFinanceDb`, for example
   `BACKUP DATABASE PersonalFinanceDb TO DISK = N'<path>\PersonalFinanceDb_<date>.bak' WITH COPY_ONLY, CHECKSUM;`,
   and verify it with `RESTORE VERIFYONLY`.
2. **Build the migration bundle** from the release commit. It is a self-contained executable, so no .NET SDK is
   needed where it runs:
   ```bash
   dotnet ef migrations bundle --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api \
     -r <runtime, e.g. linux-x64 or win-x64> --self-contained -o owli-migrate
   ```
3. **Rehearse on a restored copy** of the production backup (for example `PersonalFinanceDb_Rehearsal`), on the same
   SQL Server version:
   ```bash
   ./owli-migrate --connection "<rehearsal connection string, privileged migration login>"
   ```
   Then run step 6's check on the copy, and smoke-test the new API against it.
   - **Do not** apply `dotnet ef migrations script` output (idempotent or not) with `sqlcmd` or SSMS. Rehearsed on
     27 November 2026, both forms fail on SQL Server:
     - the idempotent script uses `EXEC(CONCAT(…))`;
     - the plain script compiles a migration's later statements before its new columns exist.
   - The bundle, or `dotnet ef database update --connection …` from the release source, applies each statement
     correctly.
4. **Guards:** migrations 4 and 5 contain transactional preflight guards (`THROW 5103x/5180x/5104x`). If a guard
   fires, the migration rolls back and changes nothing. Stop, keep the backup, and reconcile the reported
   condition with the development team. Never edit migrations or remove guards.
5. Apply to production with the same bundle, only after the rehearsal succeeds, inside the maintenance window and
   with the worker stopped and public/API/MVC writes gated (§7).
6. Verify with `SELECT MigrationId FROM __EFMigrationsHistory ORDER BY MigrationId;`. The last entry must be
   `20261123100000_UsePhilippineLocalBillPaymentDates`.

Verified locally (27 November 2026, disposable databases):
- `BACKUP … WITH COPY_ONLY, CHECKSUM`, `RESTORE VERIFYONLY` and `RESTORE … WITH MOVE` work;
- the bundle upgrades a database from migration 3 through migrations 4 and 5, and a second run is a no-op;
- `database update` is a no-op on a populated copy that is already current, and its row counts are unchanged;
- all 5 migrations apply to an empty database, also in CI.

**Not verified:** upgrading a populated database that predates migration 4. Only the production rehearsal can
prove that against real data; the guards stop it safely if that data needs reconciliation.

## 6. Build and publish

```powershell
./deployment/Publish-OWLI.ps1     # API, MVC and worker into deployment/out/{api,mvc,worker} + SHA256SUMS.txt
```

Or, on any OS:

```bash
dotnet publish PersonalFinance.Api/PersonalFinance.Api.csproj -c Release --self-contained false -p:UseAppHost=false -o out/api
dotnet publish PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj -c Release --self-contained false -p:UseAppHost=false -o out/mvc
dotnet publish PersonalFinance.Worker/PersonalFinance.Worker.csproj -c Release --self-contained false -p:UseAppHost=false -o out/worker
```

The script refuses existing output folders and checks a specific filename/extension denylist
(for example Development/Local settings, `.env`, keys and SQL backup files), then writes checksums.
It is **not a complete secret/content scanner**: other filenames, embedded values or copied test
assets can escape that filter. Manual `dotnet publish` commands bypass even that guard/checksum step.

Use a clean, pinned release workspace. Inspect the exact output contents and configuration with
the team's approved secret scan; exclude private stores/certificates, machine settings, test
assemblies/demo seed tools and exported synthetic records. Supply production secrets/certificates
outside the package. For manual publishing, perform equivalent artifact inspection and generate
checksums yourself. Ship only the reviewed publish folders, required migration bundle, signed
release APK and checksums; retain source/test code in the source handoff, not runtime payloads.
This documentation closeout did not rebuild or certify new compiled production packages.

## 7. Coordinated release of API, MVC and Android

This release changes the transaction contract between the server and both clients, so **all three ship together**:

- The API now **requires** the transaction `version` on every edit (400 "The current transaction version is
  required" without it; 409 when it is stale). Neither the previous MVC nor previous Android builds send it, so
  their transaction edits fail against the new API.
- The new Android build calls endpoints that only the new API has (notification read state, stored monthly
  summaries). It shows an error for those sections against an older API.

Rehearse the backup upgrade/rollback and prepare artifacts before the maintenance window.
Order inside the approved window:

1. Gate public write traffic and stop the worker; stop/quiesce API and MVC writes as appropriate
   so neither clients nor jobs can mutate records during the final backup/migration.
2. Take/verify the final database backup, then apply only the approved, rehearsed migrations (§5).
3. Deploy API and MVC together and restart both (§8), keeping public traffic gated for validation.
4. Run the pre-activation application/TLS/event/offline smoke checks (§12) with the prepared
   signed APK and verified rollback available; keep public traffic gated.
5. Activate one independent worker last among the services, after explicit approval and storage
   checks (§9). Then perform §12's worker/scheduled-notification checks while traffic remains gated.
6. Record receiving acceptance of both phases, publish the signed APK (§11), and coordinate
   public reopening/client updates. Old transaction editors may be incompatible; do not open
   an uncoordinated mix. Retain previous artifacts/backups and reconcile any post-backup writes
   before considering a database restore.

## 8. Start and supervise the API and MVC

Run each app under its own service account and supervisor (systemd, Windows service/IIS, or the team's
standard) with its own environment, then:

```text
dotnet PersonalFinance.Api.dll          # e.g. ASPNETCORE_URLS=https://127.0.0.1:8443
dotnet PersonalFinance.AdminMvc.dll     # e.g. ASPNETCORE_URLS=https://127.0.0.1:8444
```

- Both apps require `Request.IsHttps = true`. Plain HTTP is rejected on authentication and finance routes.
  Origins must listen on HTTPS (§10).
- MVC keeps member sessions in process. Run **one** MVC instance; a restart signs users out (decision D12).
- The API rate-limits authentication: 20 requests per minute per client IP when anonymous, per user when
  signed in.

## 9. Worker and dedicated Hangfire storage

**Prepare storage (once, by the DBA):**
1. Create an empty `OWLI_Hangfire` database on the **same instance** as `PersonalFinanceDb`.
2. Install the Hangfire 1.8.25 schema with that package's `tools/install.sql`. Verify its SHA-256 against
   [worker preparation](../docs/worker-config/README.md), and run it with `QUOTED_IDENTIFIER ON`.
3. Confirm schema version 9 and zero servers, jobs and recurring entries. The worker never creates or upgrades
   the schema (`PrepareSchemaIfNecessary=false`).

**Run the worker** as a separately supervised process:

```text
dotnet PersonalFinance.Worker.dll       # environment from worker.env.template, or OWLI_WORKER_SETTINGS=<json file>
```

| `Hangfire__Enabled` | Behaviour |
| --- | --- |
| not `true` | Logs "worker is disabled" and exits **0** without opening a connection. This is the safe default. |
| `true` | Validates that the Hangfire database is `OWLI_Hangfire` on the same instance and that the finance catalog is separate (exits **2** otherwise), registers one server and three recurring jobs, then runs until stopped. |

The supervisor must stop the worker with **SIGTERM** (or a Windows service stop) and allow 30 s for a
graceful shutdown.

**Controlled activation** happens only when the owner approves the cutover. Do it after the API, MVC and
migrations and pre-activation application checks have passed, with public traffic still gated (§7, §12):
1. Inspect `OWLI_Hangfire`. It must be empty, or hold only these three jobs:
   ```sql
   SELECT (SELECT COUNT(*) FROM HangFire.Server) AS servers, (SELECT COUNT(*) FROM HangFire.Job) AS jobs;
   SELECT Value FROM HangFire.[Set] WHERE [Key] = 'recurring-jobs';
   ```
2. Set `Hangfire__Enabled=true` for the worker **only**.
3. Start the worker and confirm in `OWLI_Hangfire`:
   - `HangFire.Server` has 1 row;
   - the `recurring-jobs` set holds exactly `owli-bill-reminders` (07:00), `owli-budget-alerts` (hourly) and
     `owli-monthly-summaries` (day 1, 00:30), all in `Asia/Manila`.
4. Expect catch-up runs. On first start the worker runs any schedule already due today, for example the 07:00
   reminders if started later. The jobs are idempotent: one reminder per bill occurrence, one alert per budget
   threshold, one summary per user and month. Re-runs and restarts were verified to create no duplicates.
5. Run **one** worker process per environment, and keep it supervised (restart on failure, SIGTERM on stop).
6. Never set `Hangfire__Enabled=true` on the API (it has no effect there, by design).

## 10. Cloudflare integration (existing tunnel; deployment team only)

- Publish separate hostnames for the MVC site and the API through the existing remotely managed tunnel, for
  example `www.owli-auf.online` → MVC and an API hostname → API.
- Each hostname must route to the app's **HTTPS** origin (`https://127.0.0.1:8444` / `:8443`).
  The certificate chain must be trusted by `cloudflared` and its SAN must match the expected origin
  name. Set `originServerName` to that certificate hostname when connecting by loopback/IP; for
  a private CA, provide the verified CA through `caPool`. Keep `noTLSVerify=false` (Disable TLS
  certificate verification off). Edge TLS does not replace tunnel-to-origin validation. See
  [Cloudflare origin parameters](https://developers.cloudflare.com/tunnel/reference/origin-parameters/).
- Do not enable or trust forwarded headers globally. The apps check HTTPS on their own connection.
- Set `AllowedHosts` and `BackendApi__BaseUrl` to the real hostnames.
- Allow outbound HTTPS from the API to `api.frankfurter.dev`.
- The tunnel token is a credential: keep it in the host's secret store, never in Git.
- The worker needs no public hostname.

See also [the existing Cloudflare notes](../docs/developer-guide/DEPLOYMENT.md).

## 11. Android release APK

```bash
cd android
./gradlew assembleRelease -PowliApiBaseUrl=https://<public-api-hostname>/ \
  -PowliVersionCode=<n> -PowliVersionName=<x.y.z>      # or ORG_GRADLE_PROJECT_<name>
```

**Versioning:**

| Setting | Rule |
| --- | --- |
| `owliVersionCode` | A whole number. Production policy: choose a code **higher** than previously distributed applicable builds and raise it for each published update. Android downgrade protection and store publishing constraints must be respected; manual same-code debug updates are not the release versioning policy. |
| `owliVersionName` | The version users see, for example `1.0.0`. |
| Defaults | Without these properties, builds are `versionCode 1` / `0.1.0`, as used in development and testing. |

Select the production code from the actual distribution/installed-build history, not a fixed
example: the acceptance records include a versionCode3 build. Use a code above the highest
applicable previously distributed same-key build and record it with the signing certificate.
The default development code1 is not a safe production versioning policy.

**Signing:**
- Use **one** release keystore for the life of the app. Android only installs an update signed with the same key.
- The keystore, its passwords and alias belong to the deployment team. Never commit them, or put them in CI logs
  or this repository.
- Development builds are signed with a local debug key. A device with a debug build cannot be upgraded to the
  release-signed app; it must uninstall first, which deletes on-device data.
  - Testers should sync first, so that "waiting to sync" is empty, before uninstalling.
  - Production users who only ever install release builds are not affected.
- Verified on 27 November 2026 with the debug key: the original handoff APK upgraded in place to this candidate.
  - Database version 2 → 3; cached categories kept.
  - A pending offline expense was kept and synced exactly once after reconnecting.
  - The same upgrade must be repeated by the deployment team with their release key, between two release builds.

- The value must be an `https://` origin, optionally with a path ending in `/`. Anything else fails the build.
  Without it, the release app starts in its "not configured" state; there is no default host.
- Release builds trust only system CAs, which a Cloudflare edge certificate satisfies.
- The output, `app/build/outputs/apk/release/app-release-unsigned.apk`, is **unsigned**. Sign it with the
  deployment team's keystore, which must never be committed:
  ```bash
  zipalign -P 16 -f -v 4 app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk
  apksigner sign --ks <private-keystore> --ks-key-alias <alias> --out owli-release.apk app-release-aligned.apk
  apksigner verify --verbose --print-certs owli-release.apk      # the certificate digest must match every earlier release
  zipalign -c -P 16 -v 4 owli-release.apk
  aapt2 dump badging owli-release.apk | head -1          # shows versionCode and versionName
  ```

Alignment must precede signing; use the [Android zipalign guidance](https://developer.android.com/tools/zipalign).

## 12. Post-deployment verification

Use **synthetic accounts** only, and record the results separately from local readiness:

1. **TLS:** both public hostnames and tunnel origins validate their certificate chains/names;
   there are no redirect loops. Use a **credential-free** HTTP probe to confirm the public edge
   refuses or redirects plain HTTP. An intentionally exposed application HTTP listener must
   reject auth requests with400; an HTTPS-only origin need not expose one. Never send credentials
   over HTTP merely to test this distinction.
2. **Registration and login:**
   - register on MVC, sign in on MVC and on Android;
   - register on Android, sign in on MVC;
   - logout returns to sign-in.
3. **Transactions:** create on Android, edit on MVC, delete on Android. Each change appears on the other
   client after refresh.
4. **Goals and bills:** a contribution and a due-bill payment each record once.
5. **Reports:** a report matches between the clients for the same month.
6. **Administrator:** the administrator reaches Dashboard, Categories, Budgets and Reports; a member is denied.
7. **Worker (after activation):** one server and three jobs. The next hourly `owli-budget-alerts` run succeeds.
8. **API isolation:** the API logs show no Hangfire server.
9. **Notifications:** follow [the exact notification guide](../docs/NOTIFICATION_TESTING.md),
   adjusting sample dates to the actual Manila day. Verify immediate goal/payment/password
   events before worker activation; then scheduled upcoming/overdue/budget/summary notices,
   shared unread totals and cross-client read state after approved worker activation.
10. **Offline/upgrade:** on an approved release-test device and synthetic member, record offline,
    reconnect, confirm one accepted record/local feedback, and verify the other client. Preserve
    pending Room data during the same-key upgrade rehearsal; never use real-user data for a destructive test.
11. **FX/recovery:** verify outbound provider reachability, a supported foreign-currency snapshot,
    session renewal/logout and the documented rejection/error behavior. Record actual results;
    local test evidence is not a substitute for this production topology check.


## 13. Rollback (without losing financial data)

Financial data that users record after the cutover must survive any rollback.

- Keep the previous publish folders, their checksums and the previous environment versions.
- **Application rollback (the normal path):**
  1. Stop the worker first.
  2. Switch the API and MVC back to the previous folders **together** (§7), and restart them.
  3. Re-enable traffic.
  - The database stays as it is, so every transaction, contribution, payment and notification recorded on the
    new release is kept.
  - This release adds no migration beyond the five in §5. If production was already at
    `20261123100000_UsePhilippineLocalBillPaymentDates` before the cutover, the previous applications run on the
    same schema.
  - Rehearse compatibility with the specific previous API/MVC and installed APK versions. Do not
    assume every new APK works against every old API; keep traffic gated if the tested rollback
    combination cannot preserve required operations.
- **Schema rollback:** the owner/DBA must verify and reconcile **all writes since the backup**,
  including synthetic smoke records and background jobs. Gating public traffic alone does not
  prove that no writes occurred. Restore the pre-migration backup only under the approved plan
  when no required new records will be discarded.
  - Once users/jobs have created required records, do not blindly restore the backup; that would delete them.
  - Keep the migrated database, and roll the application back as above or fix forward with the development team.
  - Never hand-write down-migrations against financial data; that decision belongs with the owner and the DBA.
- **Worker rollback:**
  1. Stop the worker.
  2. Set `Hangfire__Enabled=false`.
  - Job history stays in `OWLI_Hangfire`; do not delete it.


## Notification enhancement release delta

PR #37, merged into `dev`, extends the existing inbox with
immediate savings/payment/password events and worker-produced overdue notices. Deploy
compatible API, MVC, Android and independent Worker artifacts together after reviewed
integration; an old worker cannot produce the new overdue type. There are **no new
migrations, dependencies, secrets, cron schedules or runtime configuration keys**.
Preserve existing notification/read history, certificates, Room data and private settings.
The API still must not host a processing server. Existing rollback/storage rules above apply.

Receiving-team smoke test: follow [notification testing](../docs/NOTIFICATION_TESTING.md).
First stop only the approved test worker and verify zero registered test servers; exercise
all event types through API/Android/MVC. Then start the guarded disposable worker profile
and exercise the unchanged three recurring IDs, including overdue and both budget transitions.
Check shared unread totals/read state, isolation, duplicates and Android offline feedback.
Do not infer worker-off status from old PID files alone. Current candidate evidence is in
[E2E acceptance](../docs/audits/OWLI_E2E_Acceptance.md#notification-enhancement-continuation--28-november-2026).
This delta is preparation for release; it does not certify production runtime or authorize
`main` integration/deployment.


## Final release record and checklist

Development closeout is complete at `ec73858ef54a7323523f32989942220ca53461b1`; owner testing
is recorded at its stated scope. Use the [single receiving-team checklist](../docs/FINAL_HANDOFF.md#final-receiving-team-handoff-checklist).
Production cutover, administrator access, real-data migration/rollback rehearsal, private secrets,
Cloudflare routing, release signing and actual production verification still belong to the
receiving team. No operation in this guide was executed by this documentation closeout.
