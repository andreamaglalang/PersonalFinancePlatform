-- OWLI migration 4 ROLLBACK (guarded): undoes 20261107054048_AddBillPaymentReceiptsAndGuards ONLY while no bill data exists. Separate approval required.
-- EF marks this migration forward-only; this script restores the exact pre-migration state (original trigger text
-- captured from the database, SHA-256 595C8D31...). With any recurring bill, receipt or bill-linked transaction it refuses;
-- then the only way back is a full restore from the verified pre-migration backup.
SET NOCOUNT ON;
SET XACT_ABORT ON;
SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF;
IF ISNULL(CONVERT(int, SERVERPROPERTY('IsLocalDB')), 0) <> 1 THROW 51701, 'STOP: expected LocalDB.', 1;
IF CONVERT(nvarchar(128), SERVERPROPERTY('MachineName')) COLLATE Latin1_General_100_BIN2 <> N'LAPTOP-MHRETQLO' THROW 51702, 'STOP: unexpected machine.', 1;
IF DB_NAME() COLLATE Latin1_General_100_BIN2 <> N'PersonalFinanceDb' THROW 51703, 'STOP: unexpected database.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.database_recovery_status WHERE database_id = DB_ID()
               AND database_guid = CONVERT(uniqueidentifier, 'bfe2bf61-c692-4550-a5ad-30158344a45c'))
    THROW 51704, 'STOP: database GUID differs from the approved target.', 1;
IF @@TRANCOUNT <> 0 THROW 51705, 'STOP: active transaction context.', 1;
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND (state_desc <> N'ONLINE' OR is_read_only = 1))
    THROW 51706, 'STOP: database not ONLINE/READ_WRITE.', 1;
IF (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 4
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261107054048_AddBillPaymentReceiptsAndGuards')
    THROW 51731, 'STOP: history is not exactly migrations 1-4; rollback refused.', 1;
IF OBJECT_ID(N'dbo.BillPaymentReceipts', N'U') IS NULL OR COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NULL
    THROW 51732, 'STOP: migration 4 objects missing; investigate.', 1;
IF EXISTS (SELECT 1 FROM dbo.BillPaymentReceipts) OR EXISTS (SELECT 1 FROM dbo.RecurringBills)
   OR EXISTS (SELECT 1 FROM dbo.Transactions WHERE BillId IS NOT NULL OR BillDueDate IS NOT NULL)
    THROW 51733, 'STOP: bill data exists; rollback refused (restore the verified backup instead).', 1;
BEGIN TRANSACTION;
DROP TRIGGER [dbo].[TR_Transactions_ValidateBillPaymentInsert];
DROP TABLE [dbo].[BillPaymentReceipts];
ALTER TABLE [dbo].[RecurringBills] DROP CONSTRAINT [CK_RecurringBills_AnchorDate];
ALTER TABLE [dbo].[RecurringBills] DROP CONSTRAINT [CK_RecurringBills_ReminderDaysBefore];
ALTER TABLE [dbo].[RecurringBills] DROP COLUMN [AnchorDate];
DROP TRIGGER [dbo].[TR_Transactions_ProtectBillPayments];
DECLARE @originalTrigger nvarchar(max) = N'CREATE TRIGGER [TR_Transactions_ProtectBillPayments]' + NCHAR(13) + NCHAR(10)
    + N'ON [Transactions]' + NCHAR(13) + NCHAR(10)
    + N'AFTER DELETE, UPDATE' + NCHAR(13) + NCHAR(10)
    + N'AS' + NCHAR(13) + NCHAR(10)
    + N'BEGIN' + NCHAR(13) + NCHAR(10)
    + N'    SET NOCOUNT ON;' + NCHAR(13) + NCHAR(10)
    + N'    IF EXISTS (' + NCHAR(13) + NCHAR(10)
    + N'        SELECT 1 FROM deleted d' + NCHAR(13) + NCHAR(10)
    + N'        LEFT JOIN inserted i ON i.TransactionId = d.TransactionId' + NCHAR(13) + NCHAR(10)
    + N'        WHERE d.BillId IS NOT NULL AND (' + NCHAR(13) + NCHAR(10)
    + N'            i.TransactionId IS NULL OR i.BillId IS NULL OR i.BillDueDate IS NULL' + NCHAR(13) + NCHAR(10)
    + N'            OR i.BillId <> d.BillId OR i.BillDueDate <> d.BillDueDate))' + NCHAR(13) + NCHAR(10)
    + N'        THROW 51001, ''Bill payments cannot be deleted or detached. Controlled reversal is not implemented.'', 1;' + NCHAR(13) + NCHAR(10)
    + N'END;';
IF CONVERT(varchar(64), HASHBYTES('SHA2_256', @originalTrigger), 2) <> '595C8D314F5038D7323B0028601C9AF4B8E7C04A0EB907D80A43B2D0CB0A59C7'
    THROW 51736, 'STOP: embedded original trigger text does not match the captured definition.', 1;
EXEC(@originalTrigger);
DELETE FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261107054048_AddBillPaymentReceiptsAndGuards';
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51734, 'STOP: rollback transaction not active.', 1;
IF OBJECT_ID(N'dbo.BillPaymentReceipts') IS NOT NULL OR COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NOT NULL
   OR OBJECT_ID(N'dbo.TR_Transactions_ValidateBillPaymentInsert') IS NOT NULL
   OR (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 3
   OR CONVERT(varchar(64), HASHBYTES('SHA2_256', OBJECT_DEFINITION(OBJECT_ID(N'dbo.TR_Transactions_ProtectBillPayments'))), 2)
      <> '595C8D314F5038D7323B0028601C9AF4B8E7C04A0EB907D80A43B2D0CB0A59C7'
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51735, 'STOP: rollback result differs from the pre-migration state; nothing committed.', 1;
END;
COMMIT TRANSACTION;
SELECT N'MIGRATION 4 ROLLED BACK' AS Result;
GO
