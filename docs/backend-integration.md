# OWLI backend integration reference

Source-inspection baseline: `6687eb95ab3741a747da36d334aa3420c4ed7c23`; application
source is unchanged by Phase 0. Expected scope is in [PRODUCT.md](../PRODUCT.md).
This is a navigation/compatibility reference, not a replacement for request DTOs,
controllers or OpenAPI. It records implemented contracts, not runtime test results.
OpenAPI is mapped in Development by [API startup](../PersonalFinance.Api/Program.cs);
no API was started or live schema fetched during this preparation.

## Authentication and account compatibility

| Contract | Current behavior/source |
| --- | --- |
| `POST /api/auth/register` | `RegisterRequest`: fullName, email, password, confirmPassword; creates member, returns current-user shape (201), not a login session |
| `POST /api/auth/login`, `/refresh` | Login email/password; refresh uses refreshToken. Successful responses contain access/refresh session data (200). Never log or publish those values |
| `POST /api/auth/logout`, `/logout-all` | Logout uses refresh token; logout-all requires authentication. Success is 204 |
| MVC `GET/PUT /api/auth/me` | `CurrentUserResponse`: id, fullName, email, baseCurrency, roles; update request contains fullName |
| Android `GET/PUT /api/account/profile` | `AccountProfileResponse`: id, fullName, email, baseCurrency, version; update requires fullName plus uppercase 64-hex version in the body; stale version can return 409 |
| Password change | MVC `/api/auth/change-password`: currentPassword/newPassword. Android `/api/account/change-password`: also confirmPassword. Success 204; contracts are intentionally distinct |

Sources: [AuthController](../PersonalFinance.Api/Controllers/AuthController.cs),
[AccountController](../PersonalFinance.Api/Controllers/AccountController.cs),
[auth requests](../PersonalFinance.Infrastructure/Authentication/Contracts/AuthRequests.cs),
[account DTOs](../PersonalFinance.Infrastructure/Authentication/Contracts/AccountContracts.cs),
[MVC auth client](../PersonalFinance.AdminMvc/Services/AuthenticationApiClient.cs),
[Android account client](../android/app/src/main/java/com/elect3/mobdev/personalfinance/data/remote/LiveAccountPlanRepository.kt).

These differences are confirmed source facts, not independently reproduced bugs.
Do not consolidate DTOs/routes without a compatibility decision and client tests.
Identity/JWT and security-stamp/session invalidation are shared concerns;
[authentication setup](../PersonalFinance.Api/Authentication/AuthenticationConfiguration.cs)
and [AccountService](../PersonalFinance.Infrastructure/Authentication/AccountService.cs)
are the authority for actual policy. MVC uses server-side API clients and sessions;
Android uses its auth/session repository. Neither client should infer ownership
or authorization from posted financial identifiers.

## Financial operations, errors and synchronization

| Area | Current contract and coordination boundary |
| --- | --- |
| Transactions | `/api/transactions` create/list/get/update/delete. Create requires nonempty client-generated `clientId` plus type/categoryId/amount/currencyCode/transactionDate and optional description. Response includes transactionId, clientId, original amount/currency, fxRate/fxRateDate, amountBase and dates. Create returns 201 or replay success; retain the same ID and payload across uncertain delivery |
| Categories/budgets | `/api/categories`, `/api/budgets` CRUD; system-default category mutations require Administrator. Budget responses include period, limit/threshold, spent/remaining, status and baseCurrency. Do not invent ETag support where controllers do not provide it |
| Savings | `/api/savings-goals` and contribution resources. Resource version is base64 SQL rowversion; applicable mutations require one strong quoted `If-Match`, missing 428/malformed 400/stale 412. Creation receipts distinguish replay and retained deletion. This is different from account profile's body version |
| Bills/payments | `/api/recurring-bills` and `/{id}/payments`; payment success may be 201 or 200 replay. Preserve occurrence/client request identity and receipts; do not resend uncertain payments under a fresh identity |
| FX | `/api/exchange-rates/quote`; conversion uses Frankfurter and server policy. Stored transaction snapshot drives history/report amounts; do not recompute old records with today's rate |
| Notifications | `GET /api/notifications`, paged/optional unread filter, scoped to the authenticated user. Only read access is exposed here; no assumed mark-read, push or email contract |

[Controllers](../PersonalFinance.Api/Controllers),
[financial DTOs](../PersonalFinance.Infrastructure/Finance/Contracts),
[transaction service](../PersonalFinance.Infrastructure/Finance/TransactionService.cs),
[savings controller](../PersonalFinance.Api/Controllers/SavingsGoalsController.cs),
[bill controller](../PersonalFinance.Api/Controllers/RecurringBillsController.cs),
[notification service](../PersonalFinance.Infrastructure/Finance/NotificationService.cs)
remain authoritative; this table is intentionally not a complete endpoint schema.

Common outcomes include 200/201/204 success, 400 validation/malformed requests,
401 missing/invalid authentication, 403 forbidden roles, 404 unavailable or
unowned resources, 409 conflicts and 503 transient financial/rate availability.
Savings precondition errors additionally use 428/412. Do not apply one resource's
retry or error semantics to every endpoint. Inspect its controller/service and
ProblemDetails response; never display a failed/ambiguous write as confirmed.
Transport middleware returns 400 ProblemDetails with title `HTTPS is required.`
for plain-HTTP financial-route requests; this is a transport rejection, not a
field-validation result. It also sets no-store headers. Preserve rate limits,
validation and HTTPS; expose no bearer/refresh tokens in browser scripts.
Outside Development, `/api/dev` returns 404 before that transport check; its
manual-job endpoint is an audit/cutover concern, not a public client contract.

Money maps to SQL decimal(18,2); FX rates to decimal(18,8). Server validation
rounds accepted rates to 8 decimal places and converted amounts to 2 using
AwayFromZero. See [configuration rules](../PersonalFinance.Infrastructure/Persistence/Configurations/ConfigurationRules.cs),
[transaction mapping](../PersonalFinance.Infrastructure/Persistence/Configurations/TransactionConfiguration.cs),
[financial validation](../PersonalFinance.Infrastructure/Finance/FinanceValidation.cs).
Receipts, unique constraints and transaction-owned user locks, rather than
client UI controls, provide the relevant financial write safeguards.

Room pending/synced/rejected records and the Android creation coordinator exist;
source presence does not verify offline conflict/retry/account-switch recovery.
See [offline ledger](../android/app/src/main/java/com/elect3/mobdev/personalfinance/data/local/OfflineLedger.kt)
and [creation coordinator](../android/app/src/main/java/com/elect3/mobdev/personalfinance/data/remote/TransactionCreationCoordinator.kt).
Coordinate request identity, ambiguous delivery, cache invalidation and explicit
refresh across clients; real-time push is not presumed required.

## Reporting and background-generated results

MVC [ReportsApiClient](../PersonalFinance.AdminMvc/Services/ReportsApiClient.cs)
uses `GET /api/reports/monthly?month=YYYY-MM-01`. The backend report recomputes
base-currency totals via shared `MonthlyTotals`; it does not retrieve the stored
MonthlySummary row. Signed-in Android (R8) uses the same `GET /api/reports/monthly` with strict
validation, so both clients show the server's totals, including savings contributed and budgets. Its local
[LoadFinancialReport](../android/app/src/main/java/com/elect3/mobdev/personalfinance/domain/reports/LoadFinancialReport.kt)
calculation remains only for the offline Mock Demo. Same-cent parity still needs runtime evidence.

The selected jobs write financial notifications/monthly summaries into the shared
finance database. Notification records are available through the user-scoped API;
consumption of persisted summary snapshots by MVC/Android remains unverified.
[Worker preparation](worker-config/README.md) records the approved host separation.
No contract changed during Phase 0. Prove both clients' eventual shared-account,
financial/report/currency/notification consistency with runtime evidence on
approved synthetic/disposable targets before claiming integration complete.
