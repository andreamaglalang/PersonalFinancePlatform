-- OWLI migration 3 apply: 20261103141326_AddSavingsGoalContributionLedgerAndRowVersions ONLY. PREPARED, NOT EXECUTED.
-- Statements are identical to mig3_AddSavingsGoalContributionLedgerAndRowVersions.generated.sql (dotnet-ef 10.0.13),
-- wrapped with identity/baseline guards, XACT_ABORT, one explicit transaction, a per-batch transaction check and a
-- pre-commit object check. Requires sqlcmd -b -I (QUOTED_IDENTIFIER ON is required for the filtered unique index).
-- Run only after: preflight passed, a verified COPY_ONLY backup, API stopped, no other sessions.
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
-- (no GO: guards, BEGIN TRANSACTION and the first ALTER share one batch, so a failed guard aborts before BEGIN)
BEGIN TRANSACTION;
ALTER TABLE [SavingsGoals] ADD [RowVersion] rowversion NOT NULL;
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
ALTER TABLE [GoalContributions] ADD [RowVersion] rowversion NOT NULL;
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE TABLE [GoalContributionRequests] (
    [RequestId] bigint NOT NULL IDENTITY,
    [UserId] int NOT NULL,
    [ClientId] uniqueidentifier NULL,
    [GoalId] int NOT NULL,
    [ContributionId] int NOT NULL,
    [OriginalAmount] decimal(18,2) NOT NULL,
    [OriginalContributedOn] date NOT NULL,
    [OriginalNote] nvarchar(200) NULL,
    [CanonicalVersion] smallint NOT NULL,
    [RecordedAtUtc] datetime2 NOT NULL,
    [DeletedAtUtc] datetime2 NULL,
    CONSTRAINT [PK_GoalContributionRequests] PRIMARY KEY ([RequestId]),
    CONSTRAINT [CK_GoalContributionRequests_Amount] CHECK ([OriginalAmount] > 0),
    CONSTRAINT [CK_GoalContributionRequests_CanonicalVersion] CHECK ([CanonicalVersion] = 1),
    CONSTRAINT [CK_GoalContributionRequests_ClientId] CHECK ([ClientId] IS NULL OR [ClientId] <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT [CK_GoalContributionRequests_ContributionId] CHECK ([ContributionId] > 0),
    CONSTRAINT [CK_GoalContributionRequests_DeletedAtUtc] CHECK ([DeletedAtUtc] IS NULL OR [DeletedAtUtc] >= [RecordedAtUtc]),
    CONSTRAINT [FK_GoalContributionRequests_AspNetUsers_UserId] FOREIGN KEY ([UserId]) REFERENCES [AspNetUsers] ([Id]),
    CONSTRAINT [FK_GoalContributionRequests_SavingsGoals_GoalId] FOREIGN KEY ([GoalId]) REFERENCES [SavingsGoals] ([GoalId])
);
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE UNIQUE INDEX [IX_GoalContributionRequests_ContributionId] ON [GoalContributionRequests] ([ContributionId]);
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE INDEX [IX_GoalContributionRequests_GoalId] ON [GoalContributionRequests] ([GoalId]);
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE UNIQUE INDEX [IX_GoalContributionRequests_UserId_ClientId] ON [GoalContributionRequests] ([UserId], [ClientId]) WHERE [ClientId] IS NOT NULL;
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
INSERT INTO [__EFMigrationsHistory] ([MigrationId], [ProductVersion])
VALUES (N'20261103141326_AddSavingsGoalContributionLedgerAndRowVersions', N'10.0.13');
GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51621, 'STOP: migration transaction not active; earlier step failed.', 1;
IF COL_LENGTH(N'dbo.SavingsGoals', N'RowVersion') IS NULL OR COL_LENGTH(N'dbo.GoalContributions', N'RowVersion') IS NULL
   OR OBJECT_ID(N'dbo.GoalContributionRequests', N'U') IS NULL
   OR (SELECT COUNT(*) FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.GoalContributionRequests')
       AND name IN (N'IX_GoalContributionRequests_ContributionId', N'IX_GoalContributionRequests_GoalId', N'IX_GoalContributionRequests_UserId_ClientId')) <> 3
   OR (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 3
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51622, 'STOP: migration 3 objects incomplete; rolled back, nothing committed.', 1;
END;
COMMIT TRANSACTION;
SELECT N'MIGRATION 3 COMMITTED' AS Result;
GO
