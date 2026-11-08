using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class SavingsGoalConfiguration : IEntityTypeConfiguration<SavingsGoal>
{
    public void Configure(EntityTypeBuilder<SavingsGoal> b)
    {
        b.HasKey(x => x.GoalId);
        b.Property(x => x.Name).HasMaxLength(100).IsRequired();
        ConfigurationRules.Money(b.Property(x => x.TargetAmount));
        b.Property(x => x.Status).HasConversion<string>().HasMaxLength(20).IsRequired();
        b.Property(x => x.RowVersion).IsRowVersion();
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("SavingsGoals", t =>
        {
            t.HasCheckConstraint("CK_SavingsGoals_Target", "[TargetAmount] > 0");
            t.HasCheckConstraint("CK_SavingsGoals_Name", "LEN(LTRIM(RTRIM([Name]))) > 0");
            t.HasCheckConstraint("CK_SavingsGoals_Status", "[Status] COLLATE Latin1_General_100_BIN2 IN ('Active','Completed','Cancelled')");
        });
    }
}

internal sealed class GoalContributionConfiguration : IEntityTypeConfiguration<GoalContribution>
{
    public void Configure(EntityTypeBuilder<GoalContribution> b)
    {
        b.HasKey(x => x.ContributionId);
        ConfigurationRules.Money(b.Property(x => x.Amount));
        b.Property(x => x.Note).HasMaxLength(200);
        b.Property(x => x.RowVersion).IsRowVersion();
        b.HasOne(x => x.Goal).WithMany().HasForeignKey(x => x.GoalId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("GoalContributions", t => t.HasCheckConstraint("CK_GoalContributions_Amount", "[Amount] > 0"));
    }
}

