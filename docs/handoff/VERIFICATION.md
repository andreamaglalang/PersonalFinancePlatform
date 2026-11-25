# Handoff verification — 25 November 2026

Verified locally with .NET SDK 10.0.402 and JDK 17, using the checked-in Gradle 9.1.0 wrapper and SDK 36 / Build Tools 36.0.0. All results below refer to the consolidated active source, not the archived alternative implementations.

| Check | Result |
| --- | --- |
| `dotnet build PersonalFinancePlatform.slnx --no-restore` | Passed, 0 warnings, 0 errors |
| FinanceTests (default) | 430 passed, 0 failed |
| AuthenticationTests (default) | 30 passed, 0 failed |
| ApiSecurityTests (default) | 32 passed, 0 failed |
| CategoryUniquenessTests (default) | 15 passed, 0 failed |
| SchemaValidation (default) | 65 passed, 0 failed; no connection attempts |
| SchemaValidation `--bill-model-only` | 27 passed, 0 failed; no connection attempts |
| SchemaValidation `--bill-migration-sql-only` | 12 passed, 0 failed; generated SQL only, no execution |
| AdminProvisioningTests (default) | 35 passed, 0 failed; real Identity with test doubles, no provisioning execution |
| AdminMvc.UiTests (default) | Passed, exit 0; controller/authorization, fake HTTP and compiled Razor rendering checks |
| BillPaymentSqlTests `--mode self-test-offline` | 245 passed, 0 failed |
| BillPaymentSqlPreflight `--mode self-test-offline` | 84 passed, 0 failed |
| BillPaymentSqlLivePreflight `--mode self-test-offline` | 719 passed, 0 failed |
| SavingsSqlTests, SavingsSqlLiveTests, HangfireTests | Each project builds with 0 warnings/errors; SQL/live modes NOT executed |
| Android `assembleDebug` | Passed |
| Android `testDebugUnitTest` | 596 tests across 72 suites, 0 failures, 0 skipped |
| Android `testReleaseUnitTest` | 599 tests across 74 suites, 0 failures, 0 skipped |
| Android `assembleDebugAndroidTest` | Passed; APK compiled only, no device installation or test execution |

The first Android attempt exhausted the default Kotlin heap. Checked-in Gradle settings now specify 2 GiB heaps and one worker; the successful rerun used those settings. Compiler deprecation/annotation warnings are recorded in ISSUES.md. Initial .NET attempts were blocked by sandbox access to NuGet configuration; verification succeeded with the required package/configuration access. The profile DTO collision found during consolidation was fixed by distinct internal request types. Two synthetic known-vector assertions affected by removal of original machine identifiers were corrected against the replacement fixture bytes and all 719 checks rerun successfully; this is not a refresh of a live approval or source-integrity baseline.

No SQL connection, migration execution, original database inspection, database export, live API request, device installation, provisioning run, deployment script, service restart or tunnel change was performed. SQL Server constraints, triggers, locks, migration execution, actual Hangfire storage and end-to-end Android/MVC operation remain unverified. The branch is a buildable source handoff with explicit remaining acceptance work, not a deployment certification.

`tools/Test-Handoff.ps1` reproduces the seven default offline .NET suites. Additional policy checks use the exact offline flags shown above. GitHub Actions configuration is included for .NET and Android offline verification; remote CI results are independent of these local results.

Before publication the candidate source was reviewed for private keys, token/JWT patterns, assigned long secrets, machine paths/identifiers and prohibited configuration/database/build files. The original Android debug certificate was removed and replaced by debug-localhost user-certificate trust. No original credentials, signing keys, Cloudflare tokens, local connection overlays, personal financial records, database backups or SDK paths were intentionally included. Synthetic credentials/financial fixtures in tests and demo implementations are not real accounts or records. The new branch does not rewrite existing public Git history.
