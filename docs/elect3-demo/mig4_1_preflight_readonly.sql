-- OWLI migration 4 preflight (READ-ONLY): 20261107054048_AddBillPaymentReceiptsAndGuards. Requires migration 3 applied. PREPARED.
-- Run with: sqlcmd -X -S "(localdb)\MSSQLLocalDB" -d PersonalFinanceDb -E -N -C -I -b -x -l 30 -t 120 -W -s "|" -i <this file>
SET NOCOUNT ON;
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
IF ISNULL(CONVERT(int, SERVERPROPERTY('IsLocalDB')), 0) <> 1 THROW 51701, 'STOP: expected LocalDB.', 1;
IF CONVERT(nvarchar(128), SERVERPROPERTY('MachineName')) COLLATE Latin1_General_100_BIN2 <> N'LAPTOP-MHRETQLO' THROW 51702, 'STOP: unexpected machine.', 1;
IF DB_NAME() COLLATE Latin1_General_100_BIN2 <> N'PersonalFinanceDb' THROW 51703, 'STOP: unexpected database.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.database_recovery_status WHERE database_id = DB_ID()
               AND database_guid = CONVERT(uniqueidentifier, 'bfe2bf61-c692-4550-a5ad-30158344a45c'))
    THROW 51704, 'STOP: database GUID differs from the approved target.', 1;
IF @@TRANCOUNT <> 0 THROW 51705, 'STOP: active transaction context.', 1;
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND (state_desc <> N'ONLINE' OR is_read_only = 1))
    THROW 51706, 'STOP: database not ONLINE/READ_WRITE.', 1;
IF OBJECT_ID(N'dbo.__EFMigrationsHistory', N'U') IS NULL OR OBJECT_ID(N'dbo.RecurringBills', N'U') IS NULL
   OR OBJECT_ID(N'dbo.Transactions', N'U') IS NULL OR OBJECT_ID(N'dbo.Categories', N'U') IS NULL OR OBJECT_ID(N'dbo.AspNetUsers', N'U') IS NULL
    THROW 51710, 'STOP: required table missing.', 1;
IF (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 3
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261031023901_InitialFinanceSchema')
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261031115500_AddCategoryNameUniqueness')
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261103141326_AddSavingsGoalContributionLedgerAndRowVersions')
    THROW 51711, 'STOP: migration history is not exactly migrations 1-3 (apply migration 3 first).', 1;
IF COL_LENGTH(N'dbo.SavingsGoals', N'RowVersion') IS NULL OR COL_LENGTH(N'dbo.GoalContributions', N'RowVersion') IS NULL
   OR OBJECT_ID(N'dbo.GoalContributionRequests', N'U') IS NULL
    THROW 51712, 'STOP: migration 3 objects missing.', 1;
IF OBJECT_ID(N'dbo.BillPaymentReceipts') IS NOT NULL OR COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NOT NULL
   OR OBJECT_ID(N'dbo.TR_Transactions_ValidateBillPaymentInsert') IS NOT NULL
    THROW 51713, 'STOP: migration 4 objects already present or partial; investigate, do not retry.', 1;
IF OBJECT_ID(N'dbo.TR_Transactions_ProtectBillPayments', N'TR') IS NULL
    THROW 51714, 'STOP: existing TR_Transactions_ProtectBillPayments missing.', 1;
IF CONVERT(varchar(64), HASHBYTES('SHA2_256', OBJECT_DEFINITION(OBJECT_ID(N'dbo.TR_Transactions_ProtectBillPayments'))), 2)
   <> '595C8D314F5038D7323B0028601C9AF4B8E7C04A0EB907D80A43B2D0CB0A59C7'
    THROW 51715, 'STOP: TR_Transactions_ProtectBillPayments differs from the reviewed original.', 1;
IF EXISTS (SELECT 1 FROM dbo.RecurringBills) THROW 51716, 'STOP: recurring bills exist (AnchorDate backfill required).', 1;
IF EXISTS (SELECT 1 FROM dbo.Transactions WHERE BillId IS NOT NULL OR BillDueDate IS NOT NULL)
    THROW 51717, 'STOP: bill-linked transactions exist (reconciliation required).', 1;
IF EXISTS (SELECT 1 FROM sys.dm_exec_sessions WHERE database_id = DB_ID() AND session_id <> @@SPID)
    THROW 51718, 'STOP: other sessions are connected (stop the API first).', 1;
SELECT (SELECT COUNT(*) FROM dbo.RecurringBills) AS RecurringBillRows,
       (SELECT COUNT(*) FROM dbo.Transactions WHERE BillId IS NOT NULL OR BillDueDate IS NOT NULL) AS BillLinkedTransactions,
       (SELECT COUNT(*) FROM dbo.Transactions) AS TransactionRows,
       (SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE database_id = DB_ID() AND session_id <> @@SPID) AS OtherSessions;
SELECT N'MIGRATION 4 PREFLIGHT PASSED' AS Result;
GO
