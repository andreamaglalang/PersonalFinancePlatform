using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class MonthlySummaryConfiguration : IEntityTypeConfiguration<MonthlySummary>
{
    public void Configure(EntityTypeBuilder<MonthlySummary> b)
    {
        b.HasKey(x => x.SummaryId);
        ConfigurationRules.Money(b.Property(x => x.TotalIncome));
        ConfigurationRules.Money(b.Property(x => x.TotalExpense));
        ConfigurationRules.Money(b.Property(x => x.NetCashFlow));
        ConfigurationRules.Money(b.Property(x => x.SavingsAllocated));
        b.HasIndex(x => new { x.UserId, x.SummaryMonth }).IsUnique();
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("MonthlySummaries", t =>
        {
            t.HasCheckConstraint("CK_MonthlySummaries_Month", "DAY([SummaryMonth]) = 1");
            t.HasCheckConstraint("CK_MonthlySummaries_Totals", "[TotalIncome] >= 0 AND [TotalExpense] >= 0 AND [SavingsAllocated] >= 0");
            t.HasCheckConstraint("CK_MonthlySummaries_NetCashFlow", "[NetCashFlow] = [TotalIncome] - [TotalExpense]");
        });
    }
}

