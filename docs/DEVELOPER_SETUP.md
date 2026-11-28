# OWLI — persistent local development setup

This is the current startup guide for Windows, macOS and Linux. The older
[handoff](handoff/README.md) and developer reports retain historical context.
See [local verification](runtime/LOCAL_SETUP_VERIFICATION.md) for actual evidence
and limits. Production deployment is separate; never deploy development secrets,
certificates, debug Android trust or the test-worker profile.

## Prerequisites and source

- .NET SDK 10; restore/build the repository's pinned dependencies.
- An approved local SQL Server with an already initialized testing catalog.
  Windows LocalDB is also supported. Docker is optional when SQL already exists.
- Android Studio, Java 17, SDK 36, Build Tools 36.0.0 and the included Gradle wrapper
  for Android. Put SDK `platform-tools` on PATH once if using `adb` tasks.
- VS Code with the Microsoft C# extension for debugging; CLI startup needs no IDE.
  PowerShell 7 is needed only for the cross-platform verification script.

Clone `dev`, then use a task branch from synchronized `origin/dev`:

```sh
git clone --branch dev https://github.com/andreamaglalang/PersonalFinancePlatform.git OWLI
cd OWLI
git fetch origin
git switch -c local/my-testing origin/dev
dotnet build PersonalFinancePlatform.slnx
```

If `dev` is checked out in another worktree, leave it there. `git worktree list`
shows ownership; use another branch in this checkout. Do not remove/reset someone
else's worktree. User secrets are associated with project IDs and are shared by
worktrees under the same OS account. Agree on target settings before changing them.

## One-time private configuration

API, MVC and Worker now have stable `UserSecretsId` registrations. Development
launch profiles load their own secret stores automatically. No shell script,
Cloudflare tunnel or per-terminal export is required. Environment variables still
have higher precedence; remove stale overrides from a terminal/IDE before testing.
Do not print `dotnet user-secrets list` into shared logs.

Existing configured machine: skip import. Reuse valid JWT/SQL credentials and
certificates. New developer: copy the safe examples to ignored private files:

```sh
mkdir -p .local/runtime
cp config/development/api.user-secrets.example.json .local/runtime/api.user-secrets.json
cp config/development/worker.user-secrets.example.json .local/runtime/worker.user-secrets.json
chmod 700 .local/runtime
chmod 600 .local/runtime/*.user-secrets.json
```

On Windows PowerShell, use `New-Item -ItemType Directory -Force .local/runtime`
and `Copy-Item` for those two files. Keep them under your own user account and
restrict their Windows ACLs; do not put them in a shared directory.

Edit the API financial connection to the approved testing catalog. For explicit
worker tests, configure the same SQL instance with separate disposable catalogs:
finance `OWLI_Runtime_<id>` (or `PersonalFinanceSqlTests_<id>`) and jobs
`OWLI_HangfireTests_<id>`. Leave `Hangfire.Enabled=false`. Never substitute
operational `PersonalFinanceDb` / `OWLI_Hangfire` for the test profile.

Use encrypted SQL connections with certificate validation. A trusted SQL certificate
needs `Encrypt=Mandatory;TrustServerCertificate=False`. A self-signed local SQL
server can instead use `ServerCertificate=<absolute path to its verified public
certificate>` with those same settings. Obtain/verify that public certificate
from the administrator of the intended local instance; do not silently trust a
network peer. The currently configured machine uses an exact verified public
certificate pin. If the SQL certificate changes, reverify the instance and update
the pin; do not work around the failure by disabling validation. See
[Microsoft's SQL certificate options](https://learn.microsoft.com/en-us/sql/connect/ado-net/connection-options).
Keep actual SQL endpoints, credentials and certificate paths outside Git.

Generate a JWT key only for a new, unconfigured machine. These examples insert
64 random bytes directly into the private JSON file without printing the key.

macOS/Linux:

```sh
python3 - <<'PYKEY'
import pathlib, json, secrets, base64
p = pathlib.Path('.local/runtime/api.user-secrets.json')
s = json.loads(p.read_text())
assert s['Jwt']['SigningKeyBase64'] == '<GENERATE_ONCE_ON_YOUR_MACHINE>'
s['Jwt']['SigningKeyBase64'] = base64.b64encode(secrets.token_bytes(64)).decode()
p.write_text(json.dumps(s, indent=2)); p.chmod(0o600)
PYKEY
dotnet user-secrets set --project PersonalFinance.Api < .local/runtime/api.user-secrets.json
dotnet user-secrets set --project PersonalFinance.AdminMvc < config/development/mvc.user-secrets.example.json
dotnet user-secrets set --project PersonalFinance.Worker < .local/runtime/worker.user-secrets.json
```

Windows PowerShell:

```powershell
$path = '.local/runtime/api.user-secrets.json'
$settings = Get-Content $path -Raw | ConvertFrom-Json
if ($settings.Jwt.SigningKeyBase64 -ne '<GENERATE_ONCE_ON_YOUR_MACHINE>') { throw 'Preserve the existing key.' }
$bytes = New-Object byte[] 64
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes); $rng.Dispose()
$settings.Jwt.SigningKeyBase64 = [Convert]::ToBase64String($bytes)
$settings | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $path
Get-Content $path -Raw | dotnet user-secrets set --project PersonalFinance.Api
Get-Content config/development/mvc.user-secrets.example.json -Raw | dotnet user-secrets set --project PersonalFinance.AdminMvc
Get-Content .local/runtime/worker.user-secrets.json -Raw | dotnet user-secrets set --project PersonalFinance.Worker
```

Skip the Worker import until its two placeholders have been replaced with verified
test connections. The default worker exits disabled even without connections.
After import, remove the private input copies if no longer needed; the per-project
stores persist outside the repository. On Unix, restrict each store directory to
700 and `secrets.json` to 600. Windows stores inherit your private user-profile
permissions. User secrets are local plaintext configuration, not a production vault.
See [Microsoft Secret Manager](https://learn.microsoft.com/en-us/aspnet/core/security/app-secrets?view=aspnetcore-10.0).

Check an existing certificate first; create/trust one only if needed:

```sh
dotnet dev-certs https --check --trust
# Only if the check fails:
dotnet dev-certs https --trust
```

Linux may require installing the public certificate into the OS/browser trust
store following the SDK's platform guidance. Keep private keys private. Never use
`curl -k`, permissive HttpClient handlers, custom trust-all managers or disabled
hostname verification. MVC uses the OS trust store for its HTTPS API calls.

## Daily startup — no configuration exports

Start the approved SQL service first. These commands do not create/reset databases
or apply migrations. From the repository root, in separate terminals:

```sh
dotnet run --project PersonalFinance.Api
# API: https://localhost:7027

dotnet run --project PersonalFinance.AdminMvc
# MVC: https://localhost:62978
```

From either project folder, plain `dotnet run` works. The first API launch profile
is HTTPS; both web profiles select Development and automatically load user secrets.
Do not use `--no-launch-profile` for this default workflow. Register a synthetic
member at `/Account/Register`, then sign in at `/Account/Login`. Registration does
not sign in automatically. MVC sessions are in memory: restart MVC, then sign in
again. Persistent configuration/JWT keys survive fresh terminals and app restarts.

The API never starts Hangfire. Ordinary worker startup is a disabled check:

```sh
dotnet run --project PersonalFinance.Worker
```

After approving and verifying existing disposable test storage and its explicit
Hangfire.SqlServer 1.8.25 schema preparation:

```sh
dotnet run --project PersonalFinance.Worker --launch-profile DevelopmentTest
```

This explicit profile checks Development, disposable pairing, encryption and SQL
certificate validation before connecting. It verifies actual catalogs/instance,
Hangfire schema version 9 with eleven tables, and no registered worker before
starting. It never creates/upgrades storage. It then starts the independent
processor and registers the three approved recurring jobs on test storage only.
Invalid, operational, incompatible or occupied storage fails closed. Stop with
Ctrl+C; supervisors should send SIGTERM and allow graceful deregistration.
Operational worker activation remains a separate approval/cutover task.

## VS Code and Android Studio

Open the repository folder. Tasks are native `dotnet` commands, with no dependency
on ignored `.local/runtime/owli.sh`. Use **Run Task → OWLI: API / MVC** in separate
terminals, or **Run and Debug → OWLI: API + MVC**. Individual launch configurations
and an explicitly named compound including the approved test worker are provided.
The default compound does not activate a worker. Debugging builds first; normal
terminal startup uses the same project launch profiles and user secrets.

Open `android/` in Android Studio, select Java 17 and your SDK, and run the debug
app on a dedicated emulator/device. Android Studio writes ignored `local.properties`.
With the API running, connect the selected device:

```sh
adb reverse tcp:7027 tcp:7027
```

For multiple devices use `adb -s <serial> reverse ...`; repeat after device reboot
or reconnection. Debug connects to `https://localhost:7027/`. A missing reverse
mapping produces “Cannot reach OWLI”; an untrusted developer certificate produces
“Secure connection failed.” Host-side curl success alone does not prove phone trust.

For app-scoped local debug trust, place **one public PEM certificate** for the
existing API localhost server in ignored `android/.local/localhost.pem`, then rebuild.
For the SDK's existing development certificate, export only its public part:

```sh
mkdir -p android/.local
dotnet dev-certs https --export-path android/.local/localhost.pem --format PEM
```

First check/reuse the existing valid certificate as above. Do not regenerate it or
use private-key export flags. Verify the exported certificate matches the one the
running API serves. The Gradle task rejects private-key/bundle/malformed input,
expired certificates, CAs, and certificates without localhost SAN/server-auth usage.
It generates a debug-only resource and adds it only to the exact localhost trust
scope. The normal platform chain, expiry and hostname checks remain active; other
hosts retain the original policy. When this optional file is absent, the original
debug policy is used (system trust plus user CAs for localhost). Removing the file
and rebuilding removes the generated trust resources. Never commit the certificate.
Release artifacts contain no local certificate/policy; production trust is separate.

A credential-free debug diagnostic can be launched with:

```sh
adb -s <serial> shell am start -S -W -n com.elect3.mobdev.personalfinance/.MainActivity --ez owli.debug.CHECK_HTTPS true
adb -s <serial> logcat -d -s OwliHttpsProbe
```

The first command relaunches the app; stored data is preserved, while unsaved form
input may be lost. The probe sends only `GET /openapi/v1.json`, with normal TLS and
no credentials. Expect `http=200 normalTls=true`; compare its public server fingerprint
with the intended API certificate. A trust-anchor failure identifies missing app/device
trust. Mock mode does not verify live API connectivity.

### Physical phone when USB installation is unavailable

Some Xiaomi phones require a SIM before enabling Install via USB. Use their normal
on-phone File Manager installer instead; retain the phone's security policies.
Build a normal debug APK from `android/`:

```sh
./gradlew :app:assembleDebug -Pandroid.injected.testOnly=false
adb -s <serial> push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/OWLI-debug.apk
```

Open File Manager → Download → `OWLI-debug.apk` and approve Install/Update. If
required, grant Install unknown apps to File Manager only; do not disable security
scanning. Update with the same signing key to preserve Room/session data; do not
uninstall or clear app data. The equivalent Android Studio action is **Build → Build
Bundle(s) / APK(s) → Build APK(s)** with the debug variant selected. The **Run** action
can generate `android:testOnly=true` APKs that the normal phone installer rejects as
an invalid package. Check the packaged manifest before sharing an APK. Android
Studio's automatic USB deployment remains subject to the phone's USB-install policy.

Sources: [Android build/install variants](https://developer.android.com/build/build-for-release),
[Xiaomi File Manager installation](https://www.mi.com/global/support/faq/details/KA-113603/).

## Database/admin safeguards and troubleshooting

No automatic migrations, SQL-container restart, administrator creation or operational
worker activation occurs during setup. A new database/schema needs verified target
identity and explicit migration/provisioning authorization; use [database guidance](handoff/DATABASE.md)
and [worker guidance](worker-config/README.md), not an untargeted EF update command.

[Administrator provisioning](Development_Admin_Provisioning.md) is a separate
interactive command from the solution root, after approval. It accepts an approved
disposable runtime catalog or the guarded Windows LocalDB target, reads API user
secrets, and requires Development plus exact target confirmation. Use `dotnet run --project tools/PersonalFinance.AdminProvisioning` from the root
only after approval; its Development launch profile supplies both environment
names. Enter passwords only at its masked prompt. Never promote/reset an existing account through SQL.

| Symptom | Check/correction |
| --- | --- |
| JWT signing-key validation error | Import API user secrets; preserve an existing valid key. Use the Development profile and remove stale environment overrides. |
| BackendApi absolute HTTPS-origin error | Import MVC user secrets; use `https://localhost:7027` without a path/query/fragment. |
| TLS/SQL certificate failure | Verify the actual endpoint, certificate trust/pin and hostname; reverify changed certificates instead of bypassing validation. |
| Address already in use | Inspect the owner with `lsof -nP -iTCP:<port> -sTCP:LISTEN` or Windows `Get-NetTCPConnection`; stop only your own instance or coordinate another worktree. |
| Worker exits disabled | Expected for ordinary launch. Use the explicit test profile only after test-storage validation. |
| Test worker refuses storage | Check disposable catalog pairing, same SQL instance, schema preparation and existing registrations. Do not delete/reset history. |
| Android cannot connect | Confirm API is running, selected device/reverse rule, and the device's trusted localhost public certificate. |
| Missing ignored launcher/template referenced by old docs | Use these tracked templates and native tasks; the legacy private launcher is not required. |

Offline verification: `dotnet build PersonalFinancePlatform.slnx` and
`pwsh -File tools/Test-Handoff.ps1` with `OWLI_SQL_TEST_SERVER` absent. Live SQL
harness flags create/drop their own test catalogs and are a separately authorized
operation. Offline success does not establish full application/device/runtime readiness.
