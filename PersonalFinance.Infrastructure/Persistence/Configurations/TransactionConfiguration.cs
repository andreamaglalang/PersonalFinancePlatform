using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Infrastructure.Identity;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class TransactionConfiguration : IEntityTypeConfiguration<FinanceTransaction>
{
    public void Configure(EntityTypeBuilder<FinanceTransaction> b)
    {
        b.HasKey(x => x.TransactionId);
        b.Property(x => x.Type).HasConversion<string>().HasMaxLength(10).IsRequired();
        ConfigurationRules.Money(b.Property(x => x.Amount));
        ConfigurationRules.Money(b.Property(x => x.AmountBase));
        ConfigurationRules.Currency(b.Property(x => x.CurrencyCode));
        b.Property(x => x.FxRate).HasPrecision(18, 8);
        b.Property(x => x.Description).HasMaxLength(200);
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.HasOne(x => x.Category).WithMany().HasForeignKey(x => x.CategoryId).OnDelete(DeleteBehavior.NoAction);
        b.HasOne(x => x.Bill).WithMany().HasForeignKey(x => x.BillId).OnDelete(DeleteBehavior.NoAction);
        b.HasIndex(x => new { x.UserId, x.ClientId }).IsUnique();
        b.HasIndex(x => new { x.BillId, x.BillDueDate }).IsUnique()
            .HasFilter("[BillId] IS NOT NULL AND [BillDueDate] IS NOT NULL");
        b.HasIndex(x => new { x.UserId, x.TransactionDate });
        b.HasIndex(x => new { x.UserId, x.CategoryId, x.TransactionDate });
        b.ToTable("Transactions", t =>
        {
            t.HasTrigger("TR_Transactions_ProtectBillPayments");
            t.HasTrigger("TR_Transactions_ValidateBillPaymentInsert");
            t.UseSqlOutputClause(false);
            t.HasCheckConstraint("CK_Transactions_Amount", "[Amount] > 0");
            t.HasCheckConstraint("CK_Transactions_AmountBase", "[AmountBase] >= 0");
            t.HasCheckConstraint("CK_Transactions_FxRate", "[FxRate] > 0");
            t.HasCheckConstraint("CK_Transactions_FxRateDate", "[FxRateDate] <= [TransactionDate]");
            t.HasCheckConstraint("CK_Transactions_CurrencyCode", ConfigurationRules.CurrencyCheck("CurrencyCode"));
            t.HasCheckConstraint("CK_Transactions_Type", "[Type] COLLATE Latin1_General_100_BIN2 IN ('Income','Expense')");
            t.HasCheckConstraint("CK_Transactions_BillPeriod", "([BillId] IS NULL AND [BillDueDate] IS NULL) OR ([BillId] IS NOT NULL AND [BillDueDate] IS NOT NULL)");
            t.HasCheckConstraint("CK_Transactions_BillExpense", "[BillId] IS NULL OR [Type] = 'Expense'");
        });
    }
}

