using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace PersonalFinance.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddBillPaymentReceiptsAndGuards : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.Sql("""
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
                """);

            migrationBuilder.AddColumn<DateOnly>(
                name: "AnchorDate",
                table: "RecurringBills",
                type: "date",
                nullable: false);

            migrationBuilder.CreateTable(
                name: "BillPaymentReceipts",
                columns: table => new
                {
                    ReceiptId = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    UserId = table.Column<int>(type: "int", nullable: false),
                    ClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    BillId = table.Column<int>(type: "int", nullable: false),
                    TransactionId = table.Column<int>(type: "int", nullable: false),
                    DueDate = table.Column<DateOnly>(type: "date", nullable: false),
                    CategoryId = table.Column<int>(type: "int", nullable: false),
                    Amount = table.Column<decimal>(type: "decimal(18,2)", precision: 18, scale: 2, nullable: false),
                    CurrencyCode = table.Column<string>(type: "char(3)", nullable: false),
                    FxRate = table.Column<decimal>(type: "decimal(18,8)", precision: 18, scale: 8, nullable: false),
                    FxRateDate = table.Column<DateOnly>(type: "date", nullable: false),
                    AmountBase = table.Column<decimal>(type: "decimal(18,2)", precision: 18, scale: 2, nullable: false),
                    BaseCurrency = table.Column<string>(type: "char(3)", nullable: false),
                    PaidOn = table.Column<DateOnly>(type: "date", nullable: false),
                    CanonicalVersion = table.Column<short>(type: "smallint", nullable: false),
                    RecordedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_BillPaymentReceipts", x => x.ReceiptId);
                    table.CheckConstraint("CK_BillPaymentReceipts_Amount", "[Amount] > 0");
                    table.CheckConstraint("CK_BillPaymentReceipts_AmountBase", "[AmountBase] >= 0");
                    table.CheckConstraint("CK_BillPaymentReceipts_BaseCurrency", "DATALENGTH([BaseCurrency]) = 3 AND [BaseCurrency] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'");
                    table.CheckConstraint("CK_BillPaymentReceipts_CanonicalVersion", "[CanonicalVersion] = 1");
                    table.CheckConstraint("CK_BillPaymentReceipts_ClientId", "[ClientId] <> '00000000-0000-0000-0000-000000000000'");
                    table.CheckConstraint("CK_BillPaymentReceipts_CurrencyCode", "DATALENGTH([CurrencyCode]) = 3 AND [CurrencyCode] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'");
                    table.CheckConstraint("CK_BillPaymentReceipts_DueDate", "[DueDate] <= [PaidOn]");
                    table.CheckConstraint("CK_BillPaymentReceipts_FxRate", "[FxRate] > 0");
                    table.CheckConstraint("CK_BillPaymentReceipts_FxRateDate", "[FxRateDate] <= [PaidOn]");
                    table.CheckConstraint("CK_BillPaymentReceipts_PaidOn", "[PaidOn] = CONVERT(date, [RecordedAtUtc])");
                    table.ForeignKey(
                        name: "FK_BillPaymentReceipts_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id");
                    table.ForeignKey(
                        name: "FK_BillPaymentReceipts_Categories_CategoryId",
                        column: x => x.CategoryId,
                        principalTable: "Categories",
                        principalColumn: "CategoryId");
                    table.ForeignKey(
                        name: "FK_BillPaymentReceipts_RecurringBills_BillId",
                        column: x => x.BillId,
                        principalTable: "RecurringBills",
                        principalColumn: "BillId");
                    table.ForeignKey(
                        name: "FK_BillPaymentReceipts_Transactions_TransactionId",
                        column: x => x.TransactionId,
                        principalTable: "Transactions",
                        principalColumn: "TransactionId");
                });

            migrationBuilder.AddCheckConstraint(
                name: "CK_RecurringBills_AnchorDate",
                table: "RecurringBills",
                sql: "[AnchorDate] > '00010101' AND [NextDueDate] >= [AnchorDate]");

            migrationBuilder.AddCheckConstraint(
                name: "CK_RecurringBills_ReminderDaysBefore",
                table: "RecurringBills",
                sql: "[ReminderDaysBefore] BETWEEN 0 AND 30");

            migrationBuilder.CreateIndex(
                name: "IX_BillPaymentReceipts_BillId_DueDate",
                table: "BillPaymentReceipts",
                columns: new[] { "BillId", "DueDate" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_BillPaymentReceipts_CategoryId",
                table: "BillPaymentReceipts",
                column: "CategoryId");

            migrationBuilder.CreateIndex(
                name: "IX_BillPaymentReceipts_TransactionId",
                table: "BillPaymentReceipts",
                column: "TransactionId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_BillPaymentReceipts_UserId_ClientId",
                table: "BillPaymentReceipts",
                columns: new[] { "UserId", "ClientId" },
                unique: true);

            migrationBuilder.Sql("""
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
                """);

            migrationBuilder.Sql("""
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
                """);

            migrationBuilder.Sql("""
                EXEC(N'CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_AppendOnly]
                ON [dbo].[BillPaymentReceipts]
                AFTER UPDATE, DELETE
                AS
                BEGIN
                    SET NOCOUNT ON;

                    IF EXISTS (SELECT 1 FROM deleted)
                        THROW 51060, ''Accepted bill-payment receipts cannot be modified or deleted.'', 1;
                END;');
                """);

            migrationBuilder.Sql("""
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
                """);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            throw new InvalidOperationException(
                "This migration is forward-only and must not be downgraded automatically because doing so could destroy accepted financial history.");
        }
    }
}
