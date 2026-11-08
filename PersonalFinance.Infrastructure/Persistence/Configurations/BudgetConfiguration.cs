using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class BudgetConfiguration : IEntityTypeConfiguration<Budget>
{
    public void Configure(EntityTypeBuilder<Budget> b)
    {
        b.HasKey(x => x.BudgetId);
        ConfigurationRules.Money(b.Property(x => x.LimitAmount));
        b.Property(x => x.LastAlertStatus).HasConversion<string>().HasMaxLength(10);
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.HasOne(x => x.Category).WithMany().HasForeignKey(x => x.CategoryId).OnDelete(DeleteBehavior.NoAction);
        b.HasIndex(x => new { x.UserId, x.CategoryId, x.PeriodMonth }).IsUnique();
        b.ToTable("Budgets", t =>
        {
            t.HasCheckConstraint("CK_Budgets_Limit", "[LimitAmount] > 0");
            t.HasCheckConstraint("CK_Budgets_Threshold", "[AlertThresholdPct] BETWEEN 1 AND 100");
            t.HasCheckConstraint("CK_Budgets_PeriodMonth", "DAY([PeriodMonth]) = 1");
            t.HasCheckConstraint("CK_Budgets_LastAlertStatus", "[LastAlertStatus] IS NULL OR [LastAlertStatus] COLLATE Latin1_General_100_BIN2 IN ('OK','Warning','Exceeded')");
        });
    }
}

