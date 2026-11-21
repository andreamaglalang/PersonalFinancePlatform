# OWLI migration 3 plan: AddSavingsGoalContributionLedgerAndRowVersions

Status: PREPARED, NOT EXECUTED. Target: `(localdb)\MSSQLLocalDB` / `PersonalFinanceDb` (GUID `bfe2bf61-c692-4550-a5ad-30158344a45c`) on `LAPTOP-MHRETQLO`.

## What it changes (generated with dotnet-ef 10.0.13, `mig3_...generated.sql`)
1. `ALTER TABLE SavingsGoals ADD RowVersion rowversion NOT NULL`
2. `ALTER TABLE GoalContributions ADD RowVersion rowversion NOT NULL`
3. `CREATE TABLE GoalContributionRequests` (contribution ledger: amount > 0, canonical version 1, non-empty ClientId, deleted-after-recorded checks; FKs to AspNetUsers and SavingsGoals, NO ACTION)
4. Indexes: unique `ContributionId`; `GoalId`; filtered unique `(UserId, ClientId) WHERE ClientId IS NOT NULL`
5. History row `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`

Purely additive. SQL Server fills `rowversion` automatically for existing rows, so existing goals and contributions are kept. No existing column, index, trigger, category, transaction, budget or Identity object is touched.

## Why it is needed
The savings-goal and contribution entities map `RowVersion` and the ledger table. Without migration 3 every `api/savings-goals` request fails with a 500. Reports keep working without it (they only sum contribution amounts).

## Risks and mitigations
| Risk | Mitigation |
|---|---|
| EF script has no `XACT_ABORT`; a failed statement could still reach `COMMIT` | `mig3_3_apply_guarded.sql`: XACT_ABORT, one transaction, every later batch checks the transaction is still active, pre-commit object check, `sqlcmd -b` |
| Filtered unique index needs `QUOTED_IDENTIFIER ON` (sqlcmd defaults OFF) | Run with `sqlcmd -I`; script sets the required SET options |
| A table can hold only one `rowversion` column | Preflight stops if either table already has one |
| Schema lock on SavingsGoals / GoalContributions | Stop the API; preflight reports other sessions/locks; `-t 120` timeout rolls back |
| Later writes to GoalContributionRequests from non-default tools need the same SET options | App uses SqlClient defaults (correct); ad-hoc sqlcmd writes need `-I` |

## Execution prerequisites (all must hold; live execution needs separate approval)
- A time slot agreed with Android Codex (the API on port 7027 must be stopped) and Server Codex (no shared-database work).
- API process tree stopped, port 7027 without a listener, SSMS closed or disconnected from PersonalFinanceDb.
- `D:\Final Project\db-backups` exists with enough free space (the migration 2 backup was about 6.7 MB).
- Script hashes match this package (record them immediately before running).
- Migration 4 is out of scope and must not be applied in the same session.

## Execution order (each step needs separate approval)
1. Stop the API process tree and confirm the prerequisites above.
2. `mig3_1_preflight_readonly.sql` (read-only). Stop unless it prints `MIGRATION 3 PREFLIGHT PASSED` with 0 other sessions and 0 locks. Besides identity, history and schema checks, it now requires `AspNetUsers.Id` and `SavingsGoals.GoalId` to be `int NOT NULL` single-column primary keys (stop codes 51615, 51616), matching the new foreign-key columns.
3. Backup: `Backup-OwliPreMig3.ps1` (plan only by default; `-Execute` plus an exact confirmation phrase). It generates a unique name `PersonalFinanceDb_pre_mig3_<yyyyMMdd-HHmmss>_<8 hex>.bak`, refuses if it already exists, renders `mig3_2_backup.template.sql`, and fails closed unless sqlcmd exits 0 and prints `BACKUP VERIFIED`. The template itself refuses an unsubstituted or unexpected path (51640), a name already in backup history (51641), uses `NOINIT` (never overwrites), requires exactly one copy-only checksummed set (51642) and runs `RESTORE VERIFYONLY WITH CHECKSUM`. Size and SHA-256 are written to `<file>.manifest.json`.
4. `mig3_3_apply_guarded.sql`. Expect `MIGRATION 3 COMMITTED`.
5. `mig3_4_verify_readonly.sql`: 3 history rows; both RowVersion columns `timestamp` NOT NULL; 3 indexes (one filtered); checks and FKs enabled and trusted; row counts unchanged.
6. Restart the API; test `GET api/savings-goals` (expect 200, empty list) before any write.

Command for steps 2, 4, 5: `sqlcmd -X -S "(localdb)\MSSQLLocalDB" -d PersonalFinanceDb -E -N -C -I -b -x -l 30 -t 120 -W -s "|" -i <file> -o <file>.out.txt`

## Recovery plan and limitations
| Failure point | State | Action |
|---|---|---|
| Before the migration (preflight, backup or a guard fails) | Unchanged | Read the 516xx stop code, fix the cause, re-run the preflight. Never relax guards. A failed backup leaves at most an unverified file, which must not be used. |
| During the apply (error, timeout, lost connection) | Nothing committed | Run the verify script: expect 2 history rows and no migration 3 objects. If objects exist without the history row (not expected), stop: do not run the rollback script (it requires 3 history rows); restore the verified backup file recorded in its `.manifest.json` (see Full restore below) under approval. |
| After commit, ledger empty | Migration 3 committed | Stop the API. Either fix forward, or run `mig3_5_rollback_guarded.sql` (separate approval). |
| After new savings data exists | Ledger has rows | The rollback refuses (51633). Fix forward. A full restore from the verified backup file recorded in its `.manifest.json` is the only way back and loses every write made after the backup, including Android transactions and categories. |

Limitations:
- The rollback removes the RowVersion columns: existing goals and contributions stay, but every savings-goal endpoint fails again (500) until migration 3 is reapplied.
- Any recorded contribution creates ledger rows, so in practice the rollback is only usable before savings data is written.
- Full restore uses the backup created by `Backup-OwliPreMig3.ps1`: take the path from the `file` field of `PersonalFinanceDb_pre_mig3_<yyyyMMdd-HHmmss>_<8 hex>.bak.manifest.json`, confirm the file's current SHA-256 equals the manifest's `sha256`, then `RESTORE DATABASE PersonalFinanceDb FROM DISK = N'<path from manifest file field>' WITH REPLACE, CHECKSUM`. It needs exclusive access (all clients stopped) and separate approval. Never restore from a backup without a matching manifest.
- None of these steps were executed or tested against SQL Server; validation so far is offline (ScriptDom parse, statement inventory, diff against the EF output).

## Compatibility
Android (transactions, categories, budgets, reports) and the MVC (categories, budgets, reports) do not use the new objects. Migration 4 must still not be applied with this step.
