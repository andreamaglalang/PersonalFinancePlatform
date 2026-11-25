using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace PersonalFinance.Infrastructure.Migrations;

/// <summary>Use Philippine calendar dates while preserving UTC audit instants and payment history.</summary>
public partial class UsePhilippineLocalBillPaymentDates : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql(
            """
            IF @@TRANCOUNT = 0 OR XACT_STATE() <> 1
                THROW 51800, 'Philippine payment-date correction requires a committable transaction.', 1;
            IF EXISTS (SELECT 1 FROM dbo.BillPaymentReceipts WITH (TABLOCKX, HOLDLOCK)
                       WHERE PaidOn <> CONVERT(date, DATEADD(hour, 8, RecordedAtUtc)))
               OR EXISTS (SELECT 1 FROM dbo.Transactions WITH (TABLOCKX, HOLDLOCK)
                          WHERE (BillId IS NOT NULL OR BillDueDate IS NOT NULL)
                            AND TransactionDate <> CONVERT(date, DATEADD(hour, 8, CreatedAtUtc)))
                THROW 51801, 'Existing payment dates require reconciliation; no history will be rewritten.', 1;
            
            ALTER TABLE [dbo].[BillPaymentReceipts] DROP CONSTRAINT [CK_BillPaymentReceipts_PaidOn];
            ALTER TABLE [dbo].[BillPaymentReceipts] WITH CHECK ADD CONSTRAINT [CK_BillPaymentReceipts_PaidOn]
                CHECK ([PaidOn] = CONVERT(date, DATEADD(hour, 8, [RecordedAtUtc])));
            
            EXEC(N'CREATE OR ALTER TRIGGER [dbo].[TR_Transactions_ValidateBillPaymentInsert]
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
                            OR i.[TransactionDate] <> CONVERT(date, DATEADD(hour, 8, i.[CreatedAtUtc]))
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
    }

    protected override void Down(MigrationBuilder migrationBuilder) =>
        throw new InvalidOperationException(
            "Payment-date semantics are forward-only; reverting could invalidate accepted Philippine-local payment history.");
}
