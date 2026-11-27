# OWLI deployment handoff guide

**Audience:** the external deployment team that hosts OWLI through its existing Cloudflare configuration
(public site: https://www.owli-auf.online/).

**Division of responsibilities (decision D10):**
- The development team delivers a verified, deployment-ready codebase on `dev` and this guide.
- The deployment team owns hosting, Cloudflare, production databases, secrets, signing keys, operational
  Hangfire activation and every production operation.
- Local release readiness and production deployment are **separate verification statuses**. Nothing in this
  repository claims that production deployment or production runtime verification has passed.

The release commit, test evidence and known limitations are in
[the acceptance record](../docs/audits/OWLI_Phase3_Acceptance.md).

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
git checkout dev && git pull --ff-only
git rev-parse HEAD   # must match the release commit in the acceptance record
```

`main` is not the release branch until the owner explicitly approves a `dev` → `main` merge.

## 4. Private configuration (never commit)

Inject values through the host's secret store or the per-service environment. The `*.env.template` files in
this folder list the variables with placeholders only. They are not loaded automatically, and must not be
sourced as shell scripts.

| Service | Variables |
| --- | --- |
| API ([api.env.template](api.env.template)) | `ASPNETCORE_ENVIRONMENT=Production`, `AllowedHosts`, `ConnectionStrings__DefaultConnection`, `Jwt__Issuer`, `Jwt__Audience`, `Jwt__SigningKeyBase64` (≥ 32 random bytes, base64), `CurrencyPolicy__MaximumRateAgeDays`, `Hangfire__Enabled=false`, Kestrel HTTPS URL/certificate |
| MVC ([mvc.env.template](mvc.env.template)) | `ASPNETCORE_ENVIRONMENT=Production`, `AllowedHosts`, `BackendApi__BaseUrl` (the API's HTTPS origin reachable from the MVC server, no path), Kestrel HTTPS URL/certificate |
| Worker ([worker.env.template](worker.env.template)) | `DOTNET_ENVIRONMENT=Production`, `ConnectionStrings__DefaultConnection` (PersonalFinanceDb), `ConnectionStrings__Hangfire` (`OWLI_Hangfire`, same instance), `Hangfire__Enabled` (`false` until activation, see §8) |
| Android build | `owliApiBaseUrl` Gradle property (public HTTPS API origin) and the signing keystore, both held by the deployment team |

Rules:
- Never put SQL credentials or JWT keys into MVC or Android.
- Leave `OWLI_DEV_CERT_THUMBPRINT` unset.
- Use a least-privilege SQL login for runtime, and a separate privileged login only for migrations.

## 5. Database backup and migration

The schema has 5 EF Core migrations. The applications never migrate on startup.

1. **Back up** `PersonalFinanceDb`, for example
   `BACKUP DATABASE PersonalFinanceDb TO DISK = N'<path>\PersonalFinanceDb_<date>.bak' WITH COPY_ONLY, CHECKSUM;`,
   and verify it with `RESTORE VERIFYONLY`.
2. **Rehearse on a restored copy** (for example `PersonalFinanceDb_Rehearsal`) on the same SQL version:
   ```bash
   dotnet ef database update --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api \
     --connection "<rehearsal connection string>"
   ```
   Or generate a reviewable script:
   `dotnet ef migrations script --idempotent --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api -o owli-migrations.sql`
3. **Guards:** migrations 4 and 5 contain transactional preflight guards (`THROW 5103x/5180x/5104x`). If a guard
   fires, the migration rolls back and changes nothing. Stop, keep the backup, and reconcile the reported
   condition with the development team. Never edit migrations or remove guards.
4. Apply to production only after the rehearsal succeeds, inside the deployment team's maintenance window.
5. Verify with `SELECT MigrationId FROM __EFMigrationsHistory ORDER BY MigrationId;`. The last entry must be
   `20261123100000_UsePhilippineLocalBillPaymentDates`.

Verified locally: all 5 migrations apply to an empty SQL Server database, also in CI. **Not verified:** an
upgrade of a populated production database. That is what the rehearsal is for.

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

The script refuses existing output folders and fails if any development settings or secret-bearing file
appear in the output. Ship only the publish folders and their checksums.

## 7. Start and supervise the API and MVC

Run each app under its own service account and supervisor (systemd, Windows service/IIS, or the team's
standard) with its own environment, then:

```text
dotnet PersonalFinance.Api.dll          # e.g. ASPNETCORE_URLS=https://127.0.0.1:8443
dotnet PersonalFinance.AdminMvc.dll     # e.g. ASPNETCORE_URLS=https://127.0.0.1:8444
```

- Both apps require `Request.IsHttps = true`. Plain HTTP is rejected on authentication and finance routes.
  Origins must listen on HTTPS (§9).
- MVC keeps member sessions in process. Run **one** MVC instance; a restart signs users out (decision D12).
- The API rate-limits authentication: 20 requests per minute per client IP when anonymous, per user when
  signed in.

## 8. Worker and dedicated Hangfire storage

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

**Controlled activation** happens only when the owner approves the cutover:
1. Inspect `OWLI_Hangfire`. It must be empty, or hold only these three jobs.
2. Set `Hangfire__Enabled=true` for the worker **only**.
3. Start the worker and confirm in `OWLI_Hangfire`:
   - `HangFire.Server` has 1 row;
   - the `recurring-jobs` set holds exactly `owli-bill-reminders` (07:00), `owli-budget-alerts` (hourly) and
     `owli-monthly-summaries` (day 1, 00:30), all in `Asia/Manila`.
4. Never set `Hangfire__Enabled=true` on the API (it has no effect there, by design).

## 9. Cloudflare integration (existing tunnel; deployment team only)

- Publish separate hostnames for the MVC site and the API through the existing remotely managed tunnel, for
  example `www.owli-auf.online` → MVC and an API hostname → API.
- Each hostname must route to the app's **HTTPS** origin (`https://127.0.0.1:8444` / `:8443`). Use a Cloudflare
  Origin CA certificate on the origin, or the tunnel's TLS-verification option for a self-signed origin
  certificate.
- Do not enable or trust forwarded headers globally. The apps check HTTPS on their own connection.
- Set `AllowedHosts` and `BackendApi__BaseUrl` to the real hostnames.
- Allow outbound HTTPS from the API to `api.frankfurter.dev`.
- The tunnel token is a credential: keep it in the host's secret store, never in Git.
- The worker needs no public hostname.

See also [the existing Cloudflare notes](../docs/developer-guide/DEPLOYMENT.md).

## 10. Android release APK

```bash
cd android
./gradlew assembleRelease -PowliApiBaseUrl=https://<public-api-hostname>/   # or ORG_GRADLE_PROJECT_owliApiBaseUrl
```

- The value must be an `https://` origin, optionally with a path ending in `/`. Anything else fails the build.
  Without it, the release app starts in its "not configured" state; there is no default host.
- Release builds trust only system CAs, which a Cloudflare edge certificate satisfies.
- The output, `app/build/outputs/apk/release/app-release-unsigned.apk`, is **unsigned**. Sign it with the
  deployment team's keystore, which must never be committed:
  ```bash
  zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
  apksigner sign --ks <keystore> --out owli-release.apk app-release-aligned.apk
  apksigner verify owli-release.apk
  ```

## 11. Post-deployment verification

Use **synthetic accounts** only, and record the results separately from local readiness:

1. **TLS:** both hostnames have valid certificates; plain HTTP to `/api/auth/login` returns 400 "HTTPS is
   required"; there are no redirect loops.
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

## 12. Rollback

- Keep the previous publish folders, their checksums and the previous environment versions.
- **Application rollback:**
  1. Stop the worker first.
  2. Switch the API and MVC back to the previous folders and restart them.
  3. Re-enable traffic.
- **Schema rollback:** restore the pre-migration backup. Do not hand-write down-migrations against financial
  data; that decision belongs with the owner and the DBA.
- **Worker rollback:** stop it, then set `Hangfire__Enabled=false`. Job history stays in `OWLI_Hangfire`;
  do not delete it.
