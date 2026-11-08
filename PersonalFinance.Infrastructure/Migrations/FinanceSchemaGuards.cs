using Microsoft.EntityFrameworkCore.Migrations;

namespace PersonalFinance.Infrastructure.Migrations;

/// <summary>Database guards for invariants that ordinary foreign keys/check constraints cannot express.</summary>
internal static class FinanceSchemaGuards
{
    internal static void Up(MigrationBuilder migrationBuilder)
    {
        CreateTrigger(migrationBuilder, """
            CREATE TRIGGER [TR_Transactions_ProtectBillPayments]
            ON [Transactions]
            AFTER DELETE, UPDATE
            AS
            BEGIN
                SET NOCOUNT ON;
                IF EXISTS (
                    SELECT 1 FROM deleted d
                    LEFT JOIN inserted i ON i.TransactionId = d.TransactionId
                    WHERE d.BillId IS NOT NULL AND (
                        i.TransactionId IS NULL OR i.BillId IS NULL OR i.BillDueDate IS NULL
                        OR i.BillId <> d.BillId OR i.BillDueDate <> d.BillDueDate))
                    THROW 51001, 'Bill payments cannot be deleted or detached. Controlled reversal is not implemented.', 1;
            END;
            """);
        CreateTrigger(migrationBuilder, """
            CREATE TRIGGER [TR_AspNetUsers_LockBaseCurrency]
            ON [AspNetUsers]
            AFTER UPDATE
            AS
            BEGIN
                SET NOCOUNT ON;
                IF NOT UPDATE(BaseCurrency) RETURN;
                IF EXISTS (
                    SELECT 1 FROM inserted i JOIN deleted d ON d.Id = i.Id
                    WHERE i.BaseCurrency <> d.BaseCurrency AND (
                        EXISTS (SELECT 1 FROM Transactions t WHERE t.UserId = i.Id)
                        OR EXISTS (SELECT 1 FROM Budgets b WHERE b.UserId = i.Id)
                        OR EXISTS (SELECT 1 FROM RecurringBills b WHERE b.UserId = i.Id)
                        OR EXISTS (SELECT 1 FROM SavingsGoals g WHERE g.UserId = i.Id)
                        OR EXISTS (SELECT 1 FROM MonthlySummaries s WHERE s.UserId = i.Id)))
                    THROW 51002, 'Base currency cannot change after financial records exist.', 1;
            END;
            """);
    }

    private static void CreateTrigger(MigrationBuilder migrationBuilder, string sql) =>
        migrationBuilder.Sql("EXEC(N'" + sql.Replace("'", "''") + "');");

    internal static void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("DROP TRIGGER [TR_Transactions_ProtectBillPayments];");
        migrationBuilder.Sql("DROP TRIGGER [TR_AspNetUsers_LockBaseCurrency];");
    }
}

