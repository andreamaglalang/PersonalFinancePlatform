# Monthly Budgets API implementation report

Date: 2026-11-02
Result: Offline implementation and verification PASS. Live development database verification and SQL Server integration tests remain pending approval.

## Scope and source of truth

Implemented against the approved v3.1 specification, existing Budget entity/configuration, and the final approved Monthly Budgets decisions. Existing authentication, category uniqueness, FX conversion, bill-payment protections, and financial relationships are retained. No packages or project references were changed.

No SQL Server connection was opened, no real database was modified, no migration was generated or applied, and no historical category-concurrency package or evidence was touched. The main API and administrator provisioning console were not launched.

## Created files

| File | Purpose |
| --- | --- |
| PersonalFinance.Domain/Policies/BudgetStatusCalculator.cs | Pure decimal calculation of OK, Warning and Exceeded |
| PersonalFinance.Infrastructure/Finance/Contracts/BudgetContracts.cs | Budget create/update/query/response contracts and transaction deletion response |
| PersonalFinance.Infrastructure/Finance/BudgetService.cs | Owner-scoped budget CRUD, validation, conflicts and notification-reference restrictions |
| PersonalFinance.Infrastructure/Finance/BudgetUsageService.cs | Grouped expense aggregation, response construction and active-alert reset inside the caller's write scope |
| PersonalFinance.Api/Controllers/BudgetsController.cs | Thin authenticated budget endpoints |
| tests/PersonalFinance.FinanceTests/BudgetTests.cs | 49 additional offline checks, including actual SQL query translation |
| docs/MonthlyBudgets_DevelopmentMigrationVerification.sql | Separately approval-gated, unexecuted read-only metadata/history inspection |
| docs/MonthlyBudgets_Implementation_Report.md | This report |

## Modified files

| File | Change |
| --- | --- |
| PersonalFinance.Infrastructure/Finance/Abstractions.cs | Budget service/usage contracts, BudgetPeriod and typed transaction deletion response |
| PersonalFinance.Infrastructure/Finance/Contracts/FinanceResponses.cs | Optional additive AffectedBudgets property on the existing transaction response |
| PersonalFinance.Infrastructure/Finance/TransactionService.cs | Recalculate expense scopes inside the existing write transaction; include impacts on mutation/replay responses; return deletion DTO |
| PersonalFinance.Infrastructure/DependencyInjection.cs | Scoped registrations for IBudgetService and IBudgetUsageService |
| PersonalFinance.Api/Controllers/TransactionsController.cs | DELETE response metadata changed to HTTP 200 with TransactionDeletionResponse |
| PersonalFinance.Api/Authentication/FinanceTransportMiddleware.cs | Budget HTTPS/no-store protection |
| tests/PersonalFinance.FinanceTests/Program.cs | Updated transaction DELETE regression assertion; invokes budget checks |

Existing entities, EF configurations, migrations, model snapshot, initial SQL script, category uniqueness SQL script, authentication configuration, FX implementation, appsettings and launch settings were not edited.

## API contracts

Every budget endpoint requires a validated JWT. Owner ID comes exclusively from ICurrentUser, never a request body or query owner parameter. Administrator membership provides no ownership bypass.

| Endpoint | Success | Behavior |
| --- | --- | --- |
| POST /api/budgets | 201 + Location | Create one budget per owner/category/month |
| GET /api/budgets | 200 | Paged owned budgets with calculated spending/status |
| GET /api/budgets/{id} | 200 | Owned budget and calculated usage |
| PUT /api/budgets/{id} | 200 | Change only limit and threshold |
| DELETE /api/budgets/{id} | 204 | Delete only the budget, if not referenced by notifications |
| DELETE /api/transactions/{id} | 200 | TransactionDeletionResponse, including recalculated budgets |

Create body:

    {
      "categoryId": 2,
      "periodMonth": "2026-11-01",
      "limitAmount": 5000.00,
      "alertThresholdPct": 80
    }

Update body:

    {
      "limitAmount": 6000.00,
      "alertThresholdPct": 90
    }

Unknown JSON fields are rejected. CategoryId and PeriodMonth are immutable through PUT. Owner IDs, current status and delivery state cannot be overposted.

BudgetResponse includes BudgetId, CategoryId, CategoryName, IsCategoryActive, PeriodMonth, LimitAmount, AlertThresholdPct, SpentAmount, RemainingAmount, Status and BaseCurrency. It exposes no UserId or alert-delivery fields. RemainingAmount may be negative.

List filters: PeriodMonth (first day only), CategoryId, Page (default 1), PageSize (default 20, maximum 100). Stable ordering: month descending, category ID, budget ID. Empty pages are allowed. Invalid pagination or filters return 400.

Cross-user/missing budget IDs return 404. Anonymous requests return 401. Invalid input/category assignments return 400. Duplicate scopes, stale edits/deletions and notification-reference conflicts return safe 409 ProblemDetails responses.

## Business rules

- Creation requires an accessible, active Expense category: an owned category or a system category with NULL UserId.
- Private categories belonging to other users are never assignable, including by administrators.
- Existing budgets remain readable/editable when their category is archived.
- LimitAmount must be positive, within decimal(18,2), with at most two decimal places; the existing money validator is reused.
- Threshold is a required integer from 1 through 100.
- PeriodMonth must already be the first day; it is rejected rather than silently normalized.
- No undocumented restriction on historical or future budget months was added.
- Spending sums only the owner's Expense transactions in the same category and calendar month, using AmountBase. Income, savings contributions, other categories/months/owners are excluded.
- OK: spending below limit * threshold / 100.
- Warning: at/above threshold and at/below limit, including exact equality with the limit.
- Exceeded: strictly above the limit.
- All calculations use decimal arithmetic. No independently maintained current status or spending column was added.
- Returning to OK clears LastAlertStatus during a financial mutation. LastAlertAtUtc remains historical delivery evidence.
- Read endpoints do not reset persisted alert state.
- Warning/Exceeded are never written as delivered alerts, timestamps are never advanced, and no notifications are generated.

## Notification references and budget deletion

Inspection found polymorphic ReferenceType/ReferenceId columns with a paired-nullability constraint, but no Notification-to-Budget FK and no existing notification-producing service.

Deletion therefore checks both:

1. ReferenceId equal to the budget ID and ReferenceType equal to Budget, accepting case variants via a SQL-translatable comparison.
2. The existing NotificationKeys.BudgetTransition convention: an exact budget-ID prefix of "budget:{id}:transition:" in DeduplicationKey.

This protects canonical transition history even if a historical notification lacks typed reference fields. Read, unread, pending-email and delivered notifications all protect deletion. An unrelated reference type with the same numeric ID does not block it. No notification rows are rewritten or deleted.

Future notification writers must use the same user write lock when creating a reference; the polymorphic schema provides no database FK for that coordination. No notification writer is implemented in this phase.

Deleting a budget does not delete transactions or alter category relationships. Recreating its scope later calculates usage from existing transactions.

## Transaction integration and compatibility

Existing transaction response fields and positional constructor are preserved. Create and update add AffectedBudgets, an array of current BudgetResponse values for affected existing expense budgets; no matching budget gives an empty array.

GET/list transaction responses omit the optional property, preserving their existing JSON shape. No per-transaction budget aggregation is added to transaction history reads.

Create remains 201 for a new transaction and 200 for a ClientId replay. Replays preserve the stored transaction, use its stored category/month for impacts, and perform read-only usage calculation. They do not add spending, change FX, reset alerts or generate notifications.

Update recalculates the union of old and new expense category/month scopes. This covers amount, category, date, and Income/Expense changes. Income-only scopes do not affect budgets.

DELETE is the explicitly approved breaking API change: clients must accept HTTP 200 and parse:

    {
      "transactionId": 123,
      "deleted": true,
      "affectedBudgets": []
    }

There is no automatic compatibility alias returning 204. Existing category and budget DELETE endpoints continue returning 204. Bill-payment transaction deletion remains 409. FX snapshot rules and ClientId immutability/idempotency remain unchanged.

## Atomicity, concurrency and queries

Budget create/update/delete use the existing SqlFinancialWriteScope and established user/category resource names. Category availability and duplicate scope are checked inside the write boundary. SQL Server unique errors 2601/2627 are translated specifically on budget creation into a safe 409.

Budget update/deletion compare original limit/threshold values with the locked current row and reject a conflicting intervening edit. No new rowversion or migration was introduced.

Transaction mutations retain their existing serializable SQL write scope and lock ordering. Transaction save, affected-budget aggregation and any LastAlertStatus reset execute before the same scope commits. BudgetUsageService never opens a nested transaction. Exceptions leave that SQL scope uncommitted.

Aggregation uses one SQL GROUP BY over category/year/month with SUM(AmountBase), owner/type/date/category predicates, and inclusive calendar-month boundaries. December 9999 does not overflow when determining month end. Budgets/categories are loaded together; base currency is queried once per batch. There are no queries per budget or per transaction.

A test-only friend assembly permits the finance suite to inspect the actual grouped query's SQL translation. No SQL Server command is executed by that check.

## Executed verification

These are executable console test runners, not an xUnit discovery run.

| Command (from solution root) | Passed | Failed | Scope |
| --- | ---: | ---: | --- |
| dotnet run --project tests/PersonalFinance.FinanceTests --no-restore | 115 | 0 | 66 existing finance checks plus 49 budget/integration checks |
| dotnet run --project tests/PersonalFinance.AuthenticationTests --no-restore | 27 | 0 | Identity/authentication service regressions with test stores |
| dotnet run --project tests/PersonalFinance.ApiSecurityTests --no-restore | 23 | 0 | Real JWT/MVC/authorization middleware with test persistence |
| dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore | 47 | 0 | Offline SQL Server model/migration/configuration checks |
| dotnet run --project tests/PersonalFinance.CategoryUniquenessTests --no-restore | 15 | 0 | InMemory normalization and synthetic SQL-error regressions |
| dotnet run --project tests/PersonalFinance.AdminProvisioningTests --no-restore | 20 | 0 | Provisioning safeguards with transaction/store doubles |
| Total | 247 | 0 | No live SQL Server tests |

Budget coverage includes valid CRUD, invalid amounts/scale/thresholds/dates, expense-only and system-category assignment, archive retention/races, immutable fields/overposting, ownership/admin privacy, pagination/filters, uniqueness, threshold boundaries, base-currency expense aggregation, month edges, transaction moves/type changes/deletion, FX snapshots, repeated status transitions, ClientId replay, synthetic SQL conflicts, notification references, and concurrent requests/stale writes through the serialized test scope.

The actual spending query also translated successfully to SQL Server GROUP BY/SUM/DATEPART SQL while its connection remained closed.

The final complete solution build succeeded:

    dotnet build PersonalFinancePlatform.slnx --no-restore

Build result: 0 warnings, 0 errors. No build failure required correction.

The solution includes the API, Domain, Infrastructure and administrator provisioning tool; test projects are outside the solution and were run explicitly as listed.

## Schema and preserved evidence

- Offline HasPendingModelChanges check: false.
- Both existing migration IDs still match the current model snapshot.
- SHA-256 comparison against pre-edit hashes confirmed all six files in Infrastructure/Migrations were unchanged.
- Existing docs/InitialFinanceSchema.sql and docs/AddCategoryNameUniqueness.sql hashes were unchanged.
- Existing 17 Identity/application tables, integer ownership keys, financial FK restrictions, unique indexes, precisions and checks passed the offline schema suite.
- No new entity, column, index, package or migration is needed.
- No completed SQL concurrency tests were rerun, and no historical reports/logs were changed.

## Unexecuted checks and remaining limitations

- Development database migration verification is PENDING. The successful isolated category tests do not establish PersonalFinanceDb's current migration history.
- Live SQL Server budget concurrency, real unique-index exceptions, transaction rollback after a recalculation failure, lock contention and notification-writer races were NOT executed. The InMemory write scope serializes test requests but does not certify SQL Server locking or rollback.
- SQL Server SQL translation is verified offline; execution against SQL Server remains unverified for the new budget queries.
- No real API process, SQL-backed manual API test or live Frankfurter request was run. Currency behavior is covered by existing mocks and regressions.
- Background alerts, delivery, summary generation and other future-phase features remain out of scope.
- Git metadata was not available at the workspace root; the file inventory is based on this task's explicit edits, and migration/script preservation was independently checked by SHA-256.

No automated test failures or unresolved implementation blockers remain. Live checks require separate approval.

## Next approved verification steps

1. Review and separately approve the prepared docs/MonthlyBudgets_DevelopmentMigrationVerification.sql. It reads only metadata/history, guards the database name and LocalDB, and performs no automatic remediation. Explicitly connect to the approved LocalDB alias and select PersonalFinanceDb in SSMS before executing.
2. Confirm both existing migrations are applied, Budget unique index/first-day/positive-limit/threshold constraints exist, financial FKs remain NO_ACTION, and the Categories normalized-name column/index from the prior phase are installed. Stop on discrepancies; do not apply a migration automatically.
3. After manual testing is authorized, use HTTPS and existing regular-user test JWTs to POST a budget for an active expense category and first-of-month date; verify initial usage includes preexisting expenses.
4. Create/update transactions through threshold, exact-limit and exceeded boundaries; verify transaction AffectedBudgets and GET budget agree. Move an expense across category/month and verify both impacts.
5. Verify the HTTP 200 transaction DELETE contract, unchanged bill-payment 409, budget archive retention, duplicate-scope 409, immutable-field 400, and private budget 404 for another regular user and administrator.
6. Verify budget deletion leaves transactions intact. Test notification-reference conflicts only with separately approved isolated fixtures, never by rewriting development history.
7. Separately approve isolated SQL Server budget concurrency/rollback integration before claiming those checks pass.

Readiness: ready for manual API testing after the separately approved development migration-state inspection confirms the existing schema. No live database operation is authorized or performed by this report.