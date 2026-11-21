-- OWLI migration 3 preflight (AddSavingsGoalContributionLedgerAndRowVersions). SELECT-ONLY. PREPARED, NOT EXECUTED.
-- Run with: sqlcmd -X -S "(localdb)\MSSQLLocalDB" -d PersonalFinanceDb -E -N -C -I -b -x -l 30 -t 120 -W -s "|" -i <this file>
-- Batch 1 stops (THROW) on any failed condition; -b makes sqlcmd exit before batch 2.
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
IF OBJECT_ID(N'dbo.__EFMigrationsHistory', N'U') IS NULL OR OBJECT_ID(N'dbo.SavingsGoals', N'U') IS NULL
   OR OBJECT_ID(N'dbo.GoalContributions', N'U') IS NULL OR OBJECT_ID(N'dbo.AspNetUsers', N'U') IS NULL
    THROW 51610, 'STOP: required table missing.', 1;
IF (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 2
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261031023901_InitialFinanceSchema')
   OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261031115500_AddCategoryNameUniqueness')
    THROW 51611, 'STOP: migration history is not exactly InitialFinanceSchema + AddCategoryNameUniqueness.', 1;
IF OBJECT_ID(N'dbo.GoalContributionRequests') IS NOT NULL
   OR COL_LENGTH(N'dbo.SavingsGoals', N'RowVersion') IS NOT NULL
   OR COL_LENGTH(N'dbo.GoalContributions', N'RowVersion') IS NOT NULL
    THROW 51612, 'STOP: migration 3 objects already present or partial; investigate, do not retry.', 1;
-- A table may have only one rowversion/timestamp column.
IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
           WHERE t.name = N'timestamp' AND c.object_id IN (OBJECT_ID(N'dbo.SavingsGoals'), OBJECT_ID(N'dbo.GoalContributions')))
    THROW 51613, 'STOP: an existing rowversion column would block ADD rowversion.', 1;
IF COL_LENGTH(N'dbo.SavingsGoals', N'GoalId') IS NULL OR COL_LENGTH(N'dbo.AspNetUsers', N'Id') IS NULL
    THROW 51614, 'STOP: foreign-key target column missing.', 1;
-- Foreign-key targets must be NOT NULL int columns that are each the single-column primary key of their table,
-- matching the new int NOT NULL columns GoalContributionRequests.UserId and .GoalId.
IF (SELECT COUNT(*) FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
    WHERE t.name = N'int' AND c.is_nullable = 0
      AND ((c.object_id = OBJECT_ID(N'dbo.AspNetUsers') AND c.name = N'Id')
        OR (c.object_id = OBJECT_ID(N'dbo.SavingsGoals') AND c.name = N'GoalId'))) <> 2
    THROW 51615, 'STOP: foreign-key target column type is not int NOT NULL.', 1;
IF (SELECT COUNT(*) FROM sys.indexes i
    JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
    WHERE i.is_primary_key = 1
      AND ((i.object_id = OBJECT_ID(N'dbo.AspNetUsers') AND c.name = N'Id')
        OR (i.object_id = OBJECT_ID(N'dbo.SavingsGoals') AND c.name = N'GoalId'))
      AND (SELECT COUNT(*) FROM sys.index_columns x WHERE x.object_id = i.object_id AND x.index_id = i.index_id) = 1) <> 2
    THROW 51616, 'STOP: foreign-key target is not the single-column primary key.', 1;
GO
SELECT d.compatibility_level, d.collation_name, d.recovery_model_desc,
       (SELECT COUNT(*) FROM dbo.SavingsGoals) AS SavingsGoalRows,
       (SELECT COUNT(*) FROM dbo.GoalContributions) AS GoalContributionRows,
       (SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE database_id = DB_ID() AND session_id <> @@SPID) AS OtherSessionsOnDb,
       (SELECT COUNT(*) FROM sys.dm_tran_locks WHERE resource_database_id = DB_ID() AND request_session_id <> @@SPID
            AND resource_type <> N'DATABASE') AS OtherSessionLocks
FROM sys.databases d WHERE d.database_id = DB_ID();
SELECT N'MIGRATION 3 PREFLIGHT PASSED' AS Result;
GO
