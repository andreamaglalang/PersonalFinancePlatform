# OWLI migration 4 review: AddBillPaymentReceiptsAndGuards

Status: REVIEW ONLY. Not applied anywhere known. Requires migration 3 first (EF applies in order). Repository docs mark it "UNSAFE TO APPLY" pending approval and live SQL validation.

## What it does
1. Guard batch (inside the migration transaction):
   - requires an active committable transaction (51030);
   - requires RecurringBills and Transactions (51031);
   - stops if BillPaymentReceipts or RecurringBills.AnchorDate already exist (51032, 51033);
   - takes TABLOCKX, HOLDLOCK on RecurringBills and Transactions and stops if ANY recurring bill exists (51034) or ANY transaction has BillId/BillDueDate (51035).
2. Adds `RecurringBills.AnchorDate date NOT NULL` with no default. Only valid because guard 51034 guarantees the table is empty.
3. Creates `BillPaymentReceipts` (append-only receipt per payment) with indexes and checks.
4. Triggers:
   - `TR_Transactions_ValidateBillPaymentInsert` (AFTER INSERT on Transactions)
   - `TR_BillPaymentReceipts_ValidateInsert` (AFTER INSERT on receipts)
   - `TR_BillPaymentReceipts_AppendOnly` (blocks UPDATE/DELETE of receipts)
   - `CREATE OR ALTER TR_Transactions_ProtectBillPayments` (blocks UPDATE/DELETE of bill-payment transactions; the trigger already exists from the initial schema and is extended to check receipts)

## Findings
| # | Area | Finding | Severity |
|---|---|---|---|
| F1 | Normal expense inserts | The insert trigger returns immediately when no inserted row has BillId/BillDueDate. Ordinary expenses are only charged one EXISTS check. | Low |
| F2 | EF Core and triggers | All three triggers are declared with `HasTrigger` (TransactionConfiguration, BillPaymentReceiptConfiguration), so EF does not use `OUTPUT` without `INTO` on these tables. Inserts are not broken by the triggers. | None (verified in source) |
| F3 | UTC payment date | `BillPaymentService` uses the UTC date as "today": it rejects `due > UTC today` with 409 "Future occurrences cannot be paid" and records `TransactionDate = UTC date`. The trigger enforces `TransactionDate = CONVERT(date, CreatedAtUtc)`. In Manila (UTC+8), a bill due today cannot be paid between 00:00 and 07:59 local time, and payments made then are dated the previous day. Consistent and safe (409, never a 500 or bad data), but wrong for users. | Medium (UX) |
| F4 | Guard strictness | 51034/51035 stop the migration if any bill or bill-linked transaction exists. On this laptop bills could never be created (the entity already maps AnchorDate), so both counts are expected to be 0, to be confirmed by preflight. | Low (protective) |
| F5 | Locking | TABLOCKX on Transactions and RecurringBills for the migration's duration. Must run with the API stopped. | Low in a maintenance window |
| F6 | Error mapping | Trigger errors 51040-51043/51050/51051/51060/51070 surface as SqlException. BillPaymentService maps the payment path; the generic Guard maps only 547 and 51001. A direct bill-linked insert outside the service would give 500. No current endpoint does that. | Low |
| F7 | Validation | Never executed against SQL Server; the SQL harnesses for bill payments are untracked in the main checkout and not on this branch. | Medium (unverified) |

## Proposed fixes (not applied)
1. F3: do not edit migration 4 (it is published on main). Add a later migration plus service change that accepts the member's local date: store a user time zone (default Asia/Manila), compute "today" in that zone, and relax the trigger to `TransactionDate BETWEEN DATEADD(day,-1,CONVERT(date,CreatedAtUtc)) AND DATEADD(day,1,CONVERT(date,CreatedAtUtc))`, with matching tests. Interim demo rule: do not pay bills between 00:00 and 08:00 Manila time.
2. F7: before any live use, run the existing bill-payment SQL harness against an isolated, disposable database (never PersonalFinanceDb).
3. F4/F5: preflight (read-only) for RecurringBills row count = 0, bill-linked transactions = 0, migration 3 present, no AnchorDate, no receipts table, other sessions = 0; then a guarded apply wrapper like migrations 2 and 3, after a verified backup.
4. F6: map 51040-51043 to 409 in the Guard helper if any future endpoint inserts bill-linked rows.

## Recommendation for the demonstration
Do not apply migration 4 today. Show recurring bills and reminders as implemented and offline-tested code (bill endpoint, payment and job checks pass). If time allows, apply migration 3 only (savings goals), which is additive and independent.
