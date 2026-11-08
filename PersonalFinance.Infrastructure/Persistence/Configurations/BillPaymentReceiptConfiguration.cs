
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class BillPaymentReceiptConfiguration
    : IEntityTypeConfiguration<BillPaymentReceipt>
{
    public void Configure(EntityTypeBuilder<BillPaymentReceipt> b)
    {
        b.HasKey(x => x.ReceiptId);
        b.Property(x => x.ReceiptId).UseIdentityColumn();

        ConfigurationRules.Money(b.Property(x => x.Amount));
        ConfigurationRules.Money(b.Property(x => x.AmountBase));
        ConfigurationRules.Currency(b.Property(x => x.CurrencyCode));
        ConfigurationRules.Currency(b.Property(x => x.BaseCurrency));

        b.Property(x => x.FxRate).HasPrecision(18, 8);
        b.Property(x => x.RecordedAtUtc).HasColumnType("datetime2");

        b.HasOne<ApplicationUser>()
            .WithMany()
            .HasForeignKey(x => x.UserId)
            .OnDelete(DeleteBehavior.NoAction);

        b.HasOne<RecurringBill>()
            .WithMany()
            .HasForeignKey(x => x.BillId)
            .OnDelete(DeleteBehavior.NoAction);

        b.HasOne<FinanceTransaction>()
            .WithMany()
            .HasForeignKey(x => x.TransactionId)
            .OnDelete(DeleteBehavior.NoAction);

        b.HasOne<Category>()
            .WithMany()
            .HasForeignKey(x => x.CategoryId)
            .OnDelete(DeleteBehavior.NoAction);

        b.HasIndex(x => new { x.UserId, x.ClientId }).IsUnique();
        b.HasIndex(x => new { x.BillId, x.DueDate }).IsUnique();
        b.HasIndex(x => x.TransactionId).IsUnique();

        b.ToTable("BillPaymentReceipts", t =>
        {
            t.HasTrigger("TR_BillPaymentReceipts_ValidateInsert");
            t.HasTrigger("TR_BillPaymentReceipts_AppendOnly");
            t.UseSqlOutputClause(false);
            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_ClientId",
                "[ClientId] <> '00000000-0000-0000-0000-000000000000'");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_Amount",
                "[Amount] > 0");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_AmountBase",
                "[AmountBase] >= 0");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_FxRate",
                "[FxRate] > 0");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_CurrencyCode",
                ConfigurationRules.CurrencyCheck("CurrencyCode"));

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_BaseCurrency",
                ConfigurationRules.CurrencyCheck("BaseCurrency"));

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_FxRateDate",
                "[FxRateDate] <= [PaidOn]");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_DueDate",
                "[DueDate] <= [PaidOn]");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_PaidOn",
                "[PaidOn] = CONVERT(date, [RecordedAtUtc])");

            t.HasCheckConstraint(
                "CK_BillPaymentReceipts_CanonicalVersion",
                "[CanonicalVersion] = 1");
        });

        foreach (var property in b.Metadata.GetProperties())
        {
            if (property.Name != nameof(BillPaymentReceipt.ReceiptId))
            {
                property.SetAfterSaveBehavior(PropertySaveBehavior.Throw);
            }
        }
    }
}
