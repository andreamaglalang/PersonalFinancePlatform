using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class GoalContributionRequestConfiguration : IEntityTypeConfiguration<GoalContributionRequest>
{
    public void Configure(EntityTypeBuilder<GoalContributionRequest> b)
    {
        b.HasKey(x => x.RequestId);
        b.Property(x => x.RequestId).UseIdentityColumn();
        ConfigurationRules.Money(b.Property(x => x.OriginalAmount));
        b.Property(x => x.OriginalNote).HasMaxLength(200);
        // ApplicationDbContext applies the existing UTC converter to both timestamp properties.
        b.Property(x => x.RecordedAtUtc).HasColumnType("datetime2");
        b.Property(x => x.DeletedAtUtc).HasColumnType("datetime2");
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.HasOne<SavingsGoal>().WithMany().HasForeignKey(x => x.GoalId).OnDelete(DeleteBehavior.NoAction);
        // No ContributionId relationship: deletion must preserve the request evidence.
        b.HasIndex(x => new { x.UserId, x.ClientId }).IsUnique().HasFilter("[ClientId] IS NOT NULL");
        b.HasIndex(x => x.ContributionId).IsUnique();
        b.HasIndex(x => x.GoalId);
        b.ToTable("GoalContributionRequests", t =>
        {
            t.HasCheckConstraint("CK_GoalContributionRequests_Amount", "[OriginalAmount] > 0");
            t.HasCheckConstraint("CK_GoalContributionRequests_ClientId",
                "[ClientId] IS NULL OR [ClientId] <> '00000000-0000-0000-0000-000000000000'");
            t.HasCheckConstraint("CK_GoalContributionRequests_ContributionId", "[ContributionId] > 0");
            t.HasCheckConstraint("CK_GoalContributionRequests_CanonicalVersion", "[CanonicalVersion] = 1");
            t.HasCheckConstraint("CK_GoalContributionRequests_DeletedAtUtc",
                "[DeletedAtUtc] IS NULL OR [DeletedAtUtc] >= [RecordedAtUtc]");
        });
    }
}
