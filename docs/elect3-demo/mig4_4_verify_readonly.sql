-- OWLI migration 4 verification (READ-ONLY): 20261107054048_AddBillPaymentReceiptsAndGuards.
SET NOCOUNT ON;
IF ISNULL(CONVERT(int, SERVERPROPERTY('IsLocalDB')), 0) <> 1 THROW 51701, 'STOP: expected LocalDB.', 1;
IF CONVERT(nvarchar(128), SERVERPROPERTY('MachineName')) COLLATE Latin1_General_100_BIN2 <> N'LAPTOP-MHRETQLO' THROW 51702, 'STOP: unexpected machine.', 1;
IF DB_NAME() COLLATE Latin1_General_100_BIN2 <> N'PersonalFinanceDb' THROW 51703, 'STOP: unexpected database.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.database_recovery_status WHERE database_id = DB_ID()
               AND database_guid = CONVERT(uniqueidentifier, 'bfe2bf61-c692-4550-a5ad-30158344a45c'))
    THROW 51704, 'STOP: database GUID differs from the approved target.', 1;
IF @@TRANCOUNT <> 0 THROW 51705, 'STOP: active transaction context.', 1;
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND (state_desc <> N'ONLINE' OR is_read_only = 1))
    THROW 51706, 'STOP: database not ONLINE/READ_WRITE.', 1;
SELECT MigrationId, ProductVersion FROM dbo.__EFMigrationsHistory ORDER BY MigrationId;
SELECT c.name, t.name AS TypeName, c.is_nullable FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
WHERE c.object_id = OBJECT_ID(N'dbo.RecurringBills') AND c.name = N'AnchorDate';
SELECT name, is_unique FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.BillPaymentReceipts') AND name IS NOT NULL ORDER BY name;
SELECT name, OBJECT_NAME(parent_id) AS ParentTable, is_disabled, is_instead_of_trigger FROM sys.triggers WHERE name IN (N'TR_Transactions_ValidateBillPaymentInsert', N'TR_BillPaymentReceipts_ValidateInsert', N'TR_BillPaymentReceipts_AppendOnly', N'TR_Transactions_ProtectBillPayments') ORDER BY name;
SELECT name, OBJECT_NAME(parent_object_id) AS TableName, is_disabled, is_not_trusted FROM sys.check_constraints
WHERE parent_object_id IN (OBJECT_ID(N'dbo.BillPaymentReceipts'), OBJECT_ID(N'dbo.RecurringBills')) ORDER BY TableName, name;
SELECT name, is_disabled, is_not_trusted FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID(N'dbo.BillPaymentReceipts') ORDER BY name;
SELECT (SELECT COUNT(*) FROM dbo.RecurringBills) AS RecurringBillRows, (SELECT COUNT(*) FROM dbo.BillPaymentReceipts) AS ReceiptRows,
       (SELECT COUNT(*) FROM dbo.Transactions) AS TransactionRows, (SELECT COUNT(*) FROM dbo.SavingsGoals) AS SavingsGoalRows;
GO
