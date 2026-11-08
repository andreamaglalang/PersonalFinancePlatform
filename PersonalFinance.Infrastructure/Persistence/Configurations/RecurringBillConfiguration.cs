
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class RecurringBillConfiguration : IEntityTypeConfiguration<RecurringBill>
{
    public void Configure(EntityTypeBuilder<RecurringBill> b)
    {
        b.HasKey(x => x.BillId);
        b.Property(x => x.Name).HasMaxLength(100).IsRequired();
        ConfigurationRules.Money(b.Property(x => x.Amount));
        ConfigurationRules.Currency(b.Property(x => x.CurrencyCode));
        b.Property(x => x.Frequency).HasConversion<string>().HasMaxLength(20).IsRequired();
        b.Property(x => x.IsActive).HasDefaultValue(true);
        b.Property(x => x.RowVersion).IsRowVersion();
        b.Property(x => x.AnchorDate).HasColumnType("date").IsRequired();

        b.HasOne<ApplicationUser>()
            .WithMany()
            .HasForeignKey(x => x.UserId)
            .OnDelete(DeleteBehavior.NoAction);

        b.HasOne(x => x.Category)
            .WithMany()
            .HasForeignKey(x => x.CategoryId)
            .OnDelete(DeleteBehavior.NoAction);

        b.ToTable("RecurringBills", t =>
        {
            t.HasCheckConstraint(
                "CK_RecurringBills_Amount",
                "[Amount] > 0");

            t.HasCheckConstraint(
                "CK_RecurringBills_Name",
                "LEN(LTRIM(RTRIM([Name]))) > 0");

            t.HasCheckConstraint(
                "CK_RecurringBills_CurrencyCode",
                ConfigurationRules.CurrencyCheck("CurrencyCode"));

            t.HasCheckConstraint(
                "CK_RecurringBills_Frequency",
                "[Frequency] COLLATE Latin1_General_100_BIN2 IN ('Weekly','Monthly','Quarterly','Yearly')");

            t.HasCheckConstraint(
                "CK_RecurringBills_IntendedDay",
                "([Frequency] = 'Weekly' AND [IntendedDayOfMonth] IS NULL) OR ([Frequency] IN ('Monthly','Quarterly','Yearly') AND [IntendedDayOfMonth] IS NOT NULL AND [IntendedDayOfMonth] BETWEEN 1 AND 31)");

            t.HasCheckConstraint(
                "CK_RecurringBills_ReminderDaysBefore",
                "[ReminderDaysBefore] BETWEEN 0 AND 30");

            t.HasCheckConstraint(
                "CK_RecurringBills_AnchorDate",
                "[AnchorDate] > '00010101' AND [NextDueDate] >= [AnchorDate]");
        });
    }
}
