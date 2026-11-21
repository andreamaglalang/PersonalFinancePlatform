-- OWLI migration 4 apply: 20261107054048_AddBillPaymentReceiptsAndGuards ONLY. PREPARED.
-- Statements between the markers are identical to EF's generated script (dotnet-ef 10.0.13), which is a single batch.
-- Added: XACT_ABORT (any error rolls back everything), identity and baseline guards before BEGIN TRANSACTION, and a
-- pre-commit object check replacing EF's bare COMMIT. Run only after: migration 3 verified, preflight passed,
-- a verified COPY_ONLY backup, API stopped, no other sessions. Forward-only by design; see mig4_5 for the empty-state rollback.
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
-- ===== EF statements (unchanged) =====
BEGIN TRANSACTION;
IF @@TRANCOUNT = 0 OR XACT_STATE() <> 1
    THROW 51030, 'This migration requires an active committable transaction.', 1;

IF OBJECT_ID(N'dbo.RecurringBills', N'U') IS NULL
    OR OBJECT_ID(N'dbo.Transactions', N'U') IS NULL
    THROW 51031, 'Expected financial tables are missing.', 1;

IF OBJECT_ID(N'dbo.BillPaymentReceipts') IS NOT NULL
    THROW 51032, 'Unexpected payment receipt object exists. Reconciliation is required.', 1;

IF COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NOT NULL
    THROW 51033, 'Unexpected AnchorDate column exists. Reconciliation is required.', 1;

DECLARE @RecurringBillCount bigint;
DECLARE @LinkedTransactionCount bigint;

SELECT @RecurringBillCount = COUNT_BIG(*)
FROM [dbo].[RecurringBills] WITH (TABLOCKX, HOLDLOCK);

SELECT @LinkedTransactionCount = COUNT_BIG(*)
FROM [dbo].[Transactions] WITH (TABLOCKX, HOLDLOCK)
WHERE [BillId] IS NOT NULL
   OR [BillDueDate] IS NOT NULL;

IF @RecurringBillCount <> 0
    THROW 51034, 'Existing recurring bills require an approved AnchorDate backfill.', 1;

IF @LinkedTransactionCount <> 0
    THROW 51035, 'Existing bill-linked transactions require payment-history reconciliation.', 1;

ALTER TABLE [RecurringBills] ADD [AnchorDate] date NOT NULL;

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE TABLE [BillPaymentReceipts] (
    [ReceiptId] bigint NOT NULL IDENTITY,
    [UserId] int NOT NULL,
    [ClientId] uniqueidentifier NOT NULL,
    [BillId] int NOT NULL,
    [TransactionId] int NOT NULL,
    [DueDate] date NOT NULL,
    [CategoryId] int NOT NULL,
    [Amount] decimal(18,2) NOT NULL,
    [CurrencyCode] char(3) NOT NULL,
    [FxRate] decimal(18,8) NOT NULL,
    [FxRateDate] date NOT NULL,
    [AmountBase] decimal(18,2) NOT NULL,
    [BaseCurrency] char(3) NOT NULL,
    [PaidOn] date NOT NULL,
    [CanonicalVersion] smallint NOT NULL,
    [RecordedAtUtc] datetime2 NOT NULL,
    CONSTRAINT [PK_BillPaymentReceipts] PRIMARY KEY ([ReceiptId]),
    CONSTRAINT [CK_BillPaymentReceipts_Amount] CHECK ([Amount] > 0),
    CONSTRAINT [CK_BillPaymentReceipts_AmountBase] CHECK ([AmountBase] >= 0),
    CONSTRAINT [CK_BillPaymentReceipts_BaseCurrency] CHECK (DATALENGTH([BaseCurrency]) = 3 AND [BaseCurrency] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'),
    CONSTRAINT [CK_BillPaymentReceipts_CanonicalVersion] CHECK ([CanonicalVersion] = 1),
    CONSTRAINT [CK_BillPaymentReceipts_ClientId] CHECK ([ClientId] <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT [CK_BillPaymentReceipts_CurrencyCode] CHECK (DATALENGTH([CurrencyCode]) = 3 AND [CurrencyCode] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'),
    CONSTRAINT [CK_BillPaymentReceipts_DueDate] CHECK ([DueDate] <= [PaidOn]),
    CONSTRAINT [CK_BillPaymentReceipts_FxRate] CHECK ([FxRate] > 0),
    CONSTRAINT [CK_BillPaymentReceipts_FxRateDate] CHECK ([FxRateDate] <= [PaidOn]),
    CONSTRAINT [CK_BillPaymentReceipts_PaidOn] CHECK ([PaidOn] = CONVERT(date, [RecordedAtUtc])),
    CONSTRAINT [FK_BillPaymentReceipts_AspNetUsers_UserId] FOREIGN KEY ([UserId]) REFERENCES [AspNetUsers] ([Id]),
    CONSTRAINT [FK_BillPaymentReceipts_Categories_CategoryId] FOREIGN KEY ([CategoryId]) REFERENCES [Categories] ([CategoryId]),
    CONSTRAINT [FK_BillPaymentReceipts_RecurringBills_BillId] FOREIGN KEY ([BillId]) REFERENCES [RecurringBills] ([BillId]),
    CONSTRAINT [FK_BillPaymentReceipts_Transactions_TransactionId] FOREIGN KEY ([TransactionId]) REFERENCES [Transactions] ([TransactionId])
);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
ALTER TABLE [RecurringBills] ADD CONSTRAINT [CK_RecurringBills_AnchorDate] CHECK ([AnchorDate] > '00010101' AND [NextDueDate] >= [AnchorDate]);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
ALTER TABLE [RecurringBills] ADD CONSTRAINT [CK_RecurringBills_ReminderDaysBefore] CHECK ([ReminderDaysBefore] BETWEEN 0 AND 30);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE UNIQUE INDEX [IX_BillPaymentReceipts_BillId_DueDate] ON [BillPaymentReceipts] ([BillId], [DueDate]);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE INDEX [IX_BillPaymentReceipts_CategoryId] ON [BillPaymentReceipts] ([CategoryId]);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE UNIQUE INDEX [IX_BillPaymentReceipts_TransactionId] ON [BillPaymentReceipts] ([TransactionId]);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
CREATE UNIQUE INDEX [IX_BillPaymentReceipts_UserId_ClientId] ON [BillPaymentReceipts] ([UserId], [ClientId]);

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
EXEC(N'CREATE TRIGGER [dbo].[TR_Transactions_ValidateBillPaymentInsert]
ON [dbo].[Transactions]
AFTER INSERT
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS
    (
        SELECT 1
        FROM inserted
        WHERE [BillId] IS NOT NULL
           OR [BillDueDate] IS NOT NULL
    )
        RETURN;

    IF EXISTS
    (
        SELECT 1
        FROM inserted
        WHERE
            ([BillId] IS NOT NULL AND [BillDueDate] IS NULL)
            OR
            ([BillId] IS NULL AND [BillDueDate] IS NOT NULL)
    )
        THROW 51040, ''A bill payment requires both bill linkage fields.'', 1;

    DECLARE @Bills TABLE
    (
        [BillId] int NOT NULL PRIMARY KEY,
        [UserId] int NOT NULL,
        [CategoryId] int NOT NULL,
        [Amount] decimal(18,2) NOT NULL,
        [CurrencyCode] char(3) NOT NULL,
        [NextDueDate] date NOT NULL,
        [IsActive] bit NOT NULL
    );

    INSERT INTO @Bills
    (
        [BillId], [UserId], [CategoryId], [Amount],
        [CurrencyCode], [NextDueDate], [IsActive]
    )
    SELECT
        b.[BillId], b.[UserId], b.[CategoryId], b.[Amount],
        b.[CurrencyCode], b.[NextDueDate], b.[IsActive]
    FROM [dbo].[RecurringBills] AS b WITH (UPDLOCK, HOLDLOCK)
    WHERE EXISTS
    (
        SELECT 1
        FROM inserted AS i
        WHERE i.[BillId] = b.[BillId]
          AND i.[BillId] IS NOT NULL
    );

    DECLARE @Categories TABLE
    (
        [CategoryId] int NOT NULL PRIMARY KEY,
        [UserId] int NULL,
        [Type] nvarchar(10) NOT NULL,
        [IsActive] bit NOT NULL
    );

    INSERT INTO @Categories
    (
        [CategoryId], [UserId], [Type], [IsActive]
    )
    SELECT
        c.[CategoryId], c.[UserId], c.[Type], c.[IsActive]
    FROM [dbo].[Categories] AS c WITH (UPDLOCK, HOLDLOCK)
    WHERE EXISTS
    (
        SELECT 1
        FROM @Bills AS b
        WHERE b.[CategoryId] = c.[CategoryId]
    );

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS i
        LEFT JOIN @Bills AS b ON b.[BillId] = i.[BillId]
        LEFT JOIN @Categories AS c ON c.[CategoryId] = b.[CategoryId]
        WHERE
            (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
            AND
            (
                b.[BillId] IS NULL
                OR b.[UserId] <> i.[UserId]
                OR b.[IsActive] <> 1
                OR CONVERT(varbinary(max), i.[Type])
                    <> CONVERT(varbinary(max), N''Expense'')
                OR i.[CategoryId] <> b.[CategoryId]
                OR i.[Amount] <> b.[Amount]
                OR CONVERT(varbinary(max), i.[CurrencyCode])
                    <> CONVERT(varbinary(max), b.[CurrencyCode])
                OR i.[BillDueDate] <> b.[NextDueDate]
                OR c.[CategoryId] IS NULL
                OR c.[IsActive] <> 1
                OR CONVERT(varbinary(max), c.[Type])
                    <> CONVERT(varbinary(max), N''Expense'')
                OR (c.[UserId] IS NOT NULL AND c.[UserId] <> i.[UserId])
            )
    )
        THROW 51041, ''Bill payment does not match an active owned bill and accessible expense category.'', 1;

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS i
        WHERE
            (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
            AND
            (
                i.[BillDueDate] > i.[TransactionDate]
                OR i.[TransactionDate] <> CONVERT(date, i.[CreatedAtUtc])
                OR i.[Description] IS NULL
                OR CONVERT(varbinary(max), i.[Description])
                    <> CONVERT(varbinary(max), N''Recurring bill payment'')
            )
    )
        THROW 51042, ''Bill payment date or description is invalid.'', 1;

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS i
        WHERE
            (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
            AND i.[ClientId] = ''00000000-0000-0000-0000-000000000000''
    )
        THROW 51043, ''A bill payment requires a nonempty ClientId.'', 1;
END;');

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
EXEC(N'CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_ValidateInsert]
ON [dbo].[BillPaymentReceipts]
AFTER INSERT
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted)
        RETURN;

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS r
        WHERE r.[CanonicalVersion] <> 1
           OR r.[ClientId] = ''00000000-0000-0000-0000-000000000000''
    )
        THROW 51050, ''Receipt request identity or canonical version is invalid.'', 1;

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS r
        LEFT JOIN [dbo].[Transactions] AS t WITH (UPDLOCK, HOLDLOCK)
            ON t.[TransactionId] = r.[TransactionId]
        LEFT JOIN [dbo].[RecurringBills] AS b WITH (UPDLOCK, HOLDLOCK)
            ON b.[BillId] = r.[BillId]
        LEFT JOIN [dbo].[AspNetUsers] AS u WITH (UPDLOCK, HOLDLOCK)
            ON u.[Id] = r.[UserId]
        WHERE
            t.[TransactionId] IS NULL
            OR b.[BillId] IS NULL
            OR u.[Id] IS NULL
            OR t.[UserId] <> r.[UserId]
            OR b.[UserId] <> r.[UserId]
            OR t.[ClientId] <> r.[ClientId]
            OR t.[BillId] IS NULL
            OR t.[BillDueDate] IS NULL
            OR t.[BillId] <> r.[BillId]
            OR t.[BillDueDate] <> r.[DueDate]
            OR CONVERT(varbinary(max), t.[Type])
                <> CONVERT(varbinary(max), N''Expense'')
            OR t.[CategoryId] <> r.[CategoryId]
            OR t.[Amount] <> r.[Amount]
            OR CONVERT(varbinary(max), t.[CurrencyCode])
                <> CONVERT(varbinary(max), r.[CurrencyCode])
            OR t.[FxRate] <> r.[FxRate]
            OR t.[FxRateDate] <> r.[FxRateDate]
            OR t.[AmountBase] <> r.[AmountBase]
            OR t.[TransactionDate] <> r.[PaidOn]
            OR t.[CreatedAtUtc] <> r.[RecordedAtUtc]
            OR t.[Description] IS NULL
            OR CONVERT(varbinary(max), t.[Description])
                <> CONVERT(varbinary(max), N''Recurring bill payment'')
            OR CONVERT(varbinary(max), u.[BaseCurrency])
                <> CONVERT(varbinary(max), r.[BaseCurrency])
    )
        THROW 51051, ''Receipt does not match protected payment history.'', 1;
END;');

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
EXEC(N'CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_AppendOnly]
ON [dbo].[BillPaymentReceipts]
AFTER UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF EXISTS (SELECT 1 FROM deleted)
        THROW 51060, ''Accepted bill-payment receipts cannot be modified or deleted.'', 1;
END;');

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
EXEC(N'CREATE OR ALTER TRIGGER [dbo].[TR_Transactions_ProtectBillPayments]
ON [dbo].[Transactions]
AFTER UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    IF EXISTS
    (
        SELECT 1
        FROM deleted AS d
        WHERE d.[BillId] IS NOT NULL
           OR d.[BillDueDate] IS NOT NULL
    )
        THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;

    IF EXISTS
    (
        SELECT 1
        FROM inserted AS i
        WHERE i.[BillId] IS NOT NULL
           OR i.[BillDueDate] IS NOT NULL
    )
        THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;

    IF EXISTS
    (
        SELECT 1
        FROM deleted AS d
        INNER JOIN [dbo].[BillPaymentReceipts] AS r WITH (HOLDLOCK)
            ON r.[TransactionId] = d.[TransactionId]
    )
        THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;
END;');

GO
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
INSERT INTO [__EFMigrationsHistory] ([MigrationId], [ProductVersion])
VALUES (N'20261107054048_AddBillPaymentReceiptsAndGuards', N'10.0.13');

GO
-- ===== end of EF statements =====
IF @@TRANCOUNT <> 1 OR XACT_STATE() <> 1 THROW 51721, 'STOP: migration transaction not active; earlier step failed.', 1;
IF COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NULL OR OBJECT_ID(N'dbo.BillPaymentReceipts', N'U') IS NULL
   OR (SELECT COUNT(*) FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.BillPaymentReceipts') AND name IN (N'IX_BillPaymentReceipts_BillId_DueDate', N'IX_BillPaymentReceipts_CategoryId', N'IX_BillPaymentReceipts_TransactionId', N'IX_BillPaymentReceipts_UserId_ClientId')) <> 4
   OR (SELECT COUNT(*) FROM sys.triggers WHERE name IN (N'TR_Transactions_ValidateBillPaymentInsert', N'TR_BillPaymentReceipts_ValidateInsert', N'TR_BillPaymentReceipts_AppendOnly', N'TR_Transactions_ProtectBillPayments') AND is_disabled = 0) <> 4
   OR OBJECT_DEFINITION(OBJECT_ID(N'dbo.TR_Transactions_ProtectBillPayments')) NOT LIKE N'%BillPaymentReceipts%'
   OR (SELECT COUNT(*) FROM dbo.__EFMigrationsHistory) <> 4 OR NOT EXISTS (SELECT 1 FROM dbo.__EFMigrationsHistory WHERE MigrationId = N'20261107054048_AddBillPaymentReceiptsAndGuards')
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51722, 'STOP: migration 4 objects incomplete; rolled back, nothing committed.', 1;
END;
COMMIT TRANSACTION;
SELECT N'MIGRATION 4 COMMITTED' AS Result;
GO
