-- OWLI migration 3 post-verification. SELECT-ONLY. PREPARED, NOT EXECUTED.
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
GO
SELECT MigrationId, ProductVersion FROM dbo.__EFMigrationsHistory ORDER BY MigrationId;
SELECT OBJECT_NAME(c.object_id) AS TableName, c.name, t.name AS TypeName, c.is_nullable
FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
WHERE c.name = N'RowVersion' AND c.object_id IN (OBJECT_ID(N'dbo.SavingsGoals'), OBJECT_ID(N'dbo.GoalContributions'));
SELECT name, is_unique, has_filter, filter_definition FROM sys.indexes
WHERE object_id = OBJECT_ID(N'dbo.GoalContributionRequests') AND name IS NOT NULL;
SELECT name, is_disabled, is_not_trusted FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID(N'dbo.GoalContributionRequests');
SELECT name, is_disabled, is_not_trusted FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID(N'dbo.GoalContributionRequests');
SELECT (SELECT COUNT(*) FROM dbo.SavingsGoals) AS SavingsGoalRows, (SELECT COUNT(*) FROM dbo.GoalContributions) AS GoalContributionRows,
       (SELECT COUNT(*) FROM dbo.GoalContributionRequests) AS RequestRows, (SELECT COUNT(*) FROM dbo.Transactions) AS TransactionRows;
GO
