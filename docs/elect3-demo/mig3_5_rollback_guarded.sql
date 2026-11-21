-- OWLI migration 3 ROLLBACK (mirrors Down()) - EMERGENCY ONLY, separate approval required. PREPARED, NOT EXECUTED.
-- Drops GoalContributionRequests and both RowVersion columns and removes the history row, in one transaction.
-- Refuses if migration 4 is applied or GoalContributionRequests holds rows (that would lose ledger data: restore the
-- pre-migration backup instead). Requires sqlcmd -b -I.
SET NOCOUNT ON;
SET XACT_ABORT ON;
SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF;
IF ISNULL(CONVERT(int, SERVERPROPERTY('IsLocalDB')), 0) <> 1 THROW 51601, 'STOP: expected LocalDB.', 1;
IF CONVERT(nvarchar(128), SERVERPROPERTY('MachineName')) COLLATE Latin1_General_100_BIN2 <> N'LAPTOP-MHRETQLO' THROW 51602, 'STOP: unexpected machine.', 1;
IF DB_NAME() COLLATE Latin1_General_100_BIN2 <> N'PersonalFinanceDb' THROW 51603, 'STOP: unexpected database.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.database_recovery_status WHERE database_id = DB_ID()
               AND database_guid = CONVERT(uniqueidentifier, 'bfe2bf61-c692-4550-a5ad-30158344a45c'))
    THROW 51604, 'STOP: database GUID differs from the approved target.', 1;
IF @@TRANCOUNT <> 0 THROW 51605, 'STOP: active transaction context.', 1;
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND (state_desc <> N'ONLINE' OR is_read_only = 1))
    THROW 51606, 'STOP: database not ONLINE/READ_WRITE.', 1;
IF (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 3
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261103141326_AddSavingsGoalContributionLedgerAndRowVersions')
    THROW 51631, 'STOP: history is not exactly migrations 1-3; rollback refused.', 1;
IF OBJECT_ID(N'dbo.GoalContributionRequests', N'U') IS NULL THROW 51632, 'STOP: GoalContributionRequests missing; investigate.', 1;
IF EXISTS (SELECT 1 FROM dbo.GoalContributionRequests) THROW 51633, 'STOP: ledger rows exist; use the backup restore plan instead.', 1;
BEGIN TRANSACTION;
DROP TABLE [GoalContributionRequests];
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
ALTER TABLE [SavingsGoals] DROP COLUMN [RowVersion];
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
ALTER TABLE [GoalContributions] DROP COLUMN [RowVersion];
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
DELETE FROM [__EFMigrationsHistory] WHERE [MigrationId] = N'20261103141326_AddSavingsGoalContributionLedgerAndRowVersions';
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
IF OBJECT_ID(N'dbo.GoalContributionRequests') IS NOT NULL OR COL_LENGTH(N'dbo.SavingsGoals', N'RowVersion') IS NOT NULL
   OR COL_LENGTH(N'dbo.GoalContributions', N'RowVersion') IS NOT NULL OR (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 2
BEGIN ROLLBACK TRANSACTION; THROW 51634, 'STOP: rollback incomplete; nothing committed.', 1; END;
COMMIT TRANSACTION;
SELECT N'MIGRATION 3 ROLLED BACK' AS Result;
GO
