# Verification Record

All checks were **offline**: no API startup, no SQL Server connection, no device, no Hangfire, no migrations.

## How the offline suites were run (repeat this on the new PC)

```powershell
$env:APPDATA = "$env:TEMP\owli-empty-appdata"   # isolated, empty user-secrets profile
New-Item -ItemType Directory -Force $env:APPDATA | Out-Null
$env:ASPNETCORE_ENVIRONMENT = "Testing"; $env:DOTNET_ENVIRONMENT = "Testing"
$env:ConnectionStrings__DefaultConnection = ""; $env:Hangfire__Enabled = "false"
Remove-Item Env:OWLI_DEV_CERT_THUMBPRINT -ErrorAction SilentlyContinue

foreach ($p in Get-ChildItem tests\*\*.csproj, deployment\checks\ReportsContract\ReportsContract.csproj) {
  dotnet build $p -c Release -p:NuGetAudit=false
  dotnet run --project $p -c Release --no-build      # NO extra arguments
}
```

**Never pass these switches** during routine testing. They are database- or startup-capable:
`PersonalFinance.AuthenticationTests --sql`, `PersonalFinance.ApiSecurityTests --startup-smoke` / `--startup-smoke-http`.
`SchemaValidation` installs an interceptor that rejects any connection attempt.

Android (from `android/`): `.\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest`

## Results

| Check | Result | When |
| --- | --- | --- |
| Release build: Api, AdminMvc, AdminProvisioning, 7 test projects, ReportsContract | 11/11, 0 warnings, 0 errors | 2026-11-21, on the integration tree |
| FinanceTests | 411 passed, 0 failed | re-run after the provisioning merge |
| AuthenticationTests | 27 passed, 0 failed | re-run after the provisioning merge |
| ApiSecurityTests | 27 passed, 0 failed | re-run after the provisioning merge |
| AdminProvisioningTests | **35 passed**, 0 failed (was 22 before the username feature) | re-run after the provisioning merge |
| CategoryUniquenessTests | 15 passed | integration tree before the provisioning merge (unaffected: that merge only touched the provisioning tool, its tests and one doc) |
| SchemaValidation | 65 passed, no DB connections opened | same |
| AdminMvc.UiTests | PASS | same |
| deployment ReportsContract | PASS | same |
| Android `:app:assembleDebug` | BUILD SUCCESSFUL (debug APK ~12 MB) | integration tree; `android/` unchanged since |
| Android `:app:testDebugUnitTest` | 523 tests, 0 failures, 0 errors (64 classes) | same |

Total numbered .NET checks: 580 (411 + 27 + 27 + 35 + 15 + 65), plus the unnumbered UI and contract checks.

## Not verified

API startup and real HTTP calls · any SQL Server/LocalDB execution · migrations · MVC login in a browser ·
Android instrumented tests (`connectedDebugAndroidTest`) · phone↔API synchronization · Frankfurter live calls ·
Hangfire scheduling · Cloudflare/deployment. **Offline passes are not end-to-end proof.**

## Secret and privacy checks performed

- Pattern scans of all pushed commits and the integration tree for private keys, GitHub/AWS tokens, JWTs,
  password literals and connection-string credentials: only test-only literals and placeholders found.
- The only certificate in the tree is the public, debug-only `owli_localhost_dev_certificate.cer` (CN=localhost, no
  private key).
- Machine names, database GUID and local paths were removed or redacted from the integration branch tip (see KNOWN_ISSUES.md).
