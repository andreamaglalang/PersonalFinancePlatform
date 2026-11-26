# Sanitized PR #1 CI portability evidence

> Source: GitHub Actions run 45812659812; official setup-android v3 manifest; bounded local verification of accompanying CI/test corrections
> Collected: 2026-11-26
> Published: 2026-11-26

## Original evidence

Failed head: `a7427ca811e98ce51503454201c7a0ae900910b9`.
[Android job](https://github.com/andreamaglalang/PersonalFinancePlatform/actions/runs/45812659812/job/139884226130)
failed during SDK setup because package `tools` was unavailable; Gradle did not run.
[.NET job](https://github.com/andreamaglalang/PersonalFinancePlatform/actions/runs/45812659812/job/139884225544)
built successfully then failed the LF-only transaction-edit antiforgery assertion.
The actual POST Edit method retained ValidateAntiForgeryToken.

The [official v3 action manifest](https://github.com/android-actions/setup-android/blob/v3/action.yml),
blob `2b23091730423deba6728fa9a964726ea09e2cd9`, declared default packages
`tools platform-tools`, configurable through `packages`, and license acceptance
true by default. The accompanying workflow selects only `platform-tools` at that
step and preserves the explicit SDK/build-tools installation and all Gradle tasks.

## Local verification of accompanying changes

- .NET 10 full solution build: exit 0, zero warnings/errors.
- Actual Test-Handoff.ps1 entry point: exit 0, all seven allowlisted offline
  projects, including the complete MVC UI suite. No live SQL flags were used.
- Original transaction assertion fails against CRLF source; fixed reflection
  selects Edit(int, MemberTransactionDraftViewModel, CancellationToken), requiring
  HttpPost and ValidateAntiForgeryToken metadata. Fixed LF/CRLF fixtures pass.
- A compiled isolated controller mutation removing antiforgery causes the fixed
  assertion to fail with its expected message. The tracked controller was untouched.
- Original shell check misses a stale-label CRLF fixture; normalized check rejects
  both LF and CRLF stale-label fixtures. Focused C# fixture/mutation runs omit
  Razor compilation; complete actual handoff/UI verification retains it.
- Existing Gradle wrapper with Java 17: assembleDebug, testDebugUnitTest and
  testReleaseUnitTest exit 0. Debug: 596 tests; release: 599 tests; no failures,
  errors or skips. SDK 36 and Build Tools 36.0.0 unchanged.
- Temporary PowerShell and Java archives were verified against official published
  checksums. No installed tool upgrades, application changes, DB operations,
  Hangfire activation or production access were performed.

Hosted checks for the new commit are pending at this evidence capture. Both jobs
must pass on the latest PR head; consult PR #1 for the final exact-commit run.
Local/offline success is not SQL, full application, device or worker runtime acceptance.
