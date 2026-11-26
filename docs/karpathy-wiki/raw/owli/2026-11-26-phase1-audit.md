# Sanitized Phase 1 system audit evidence

> Source: Phase 1 read-only source audit and offline verification of `dev` at `e15d859e3b98df6d0e9ed5ef5549b5a3730aec88`; full record in docs/audits/OWLI_Phase1_Audit.md
> Collected: 2026-11-26
> Published: 2026-11-26

## Verification run

- .NET 10 solution build: PASS, 0 warnings/errors.
- Offline harnesses: FinanceTests 430, AuthenticationTests 30, ApiSecurityTests 32,
  CategoryUniquenessTests 15, SchemaValidation 65, AdminProvisioningTests 35
  checks, all PASS. AdminMvc.UiTests: 43 PASS lines, exit 0.
- HangfireTests: BLOCKED (requires its dated disposable LocalDB target).
- Android testDebugUnitTest: 596 tests, 0 failures, 0 skipped.
- SQL Server, live API, rendered MVC, Android device, cross-client and worker
  behavior: NOT TESTED.

## Source-confirmed findings (selected)

- No PersonalFinance.Worker. HangfireSetup.AddFinanceJobs adds the server and
  recurring registration in the API when enabled (HangfireSetup.cs:76-77 via
  DependencyInjection.cs:90).
- FinanceJobs has no ICurrentUser/HTTP dependency. Other shared finance services
  take ICurrentUser, which only the API registers (AuthenticationConfiguration.cs:27).
- Android lacks registration (AuthApiClient.kt), transaction deletion
  (TransactionUpdateRepository.kt) and a notifications inbox.
- Notifications have no mark-read path; IsRead is never set true. MonthlySummary
  rows have no API reader.
- Android computes reports locally from transactions, without savings or budget data
  (LoadFinancialReport.kt, FinancialReport.kt).
- Transaction PUT has no client version token. Android savings/bill uncertain-write
  identities are memory-only (LiveAccountPlanRepository.kt:21-22).
- Android Room holds only local_transactions and local_categories (OwliDatabase.kt:122).
- A budget referenced by an alert notification cannot be deleted (BudgetService.cs:97-104).
- MVC admin budgets/reports use user-scoped endpoints, so admins see only their own data.

## Completion estimate (judgement, not measured)

Source implementation: about 70% overall. Runtime-verified functionality: about
0–10% (offline/unit evidence only).

No credentials, connection values, financial records or machine details are included.
