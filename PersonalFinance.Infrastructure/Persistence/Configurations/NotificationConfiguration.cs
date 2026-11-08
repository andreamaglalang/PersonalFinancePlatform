using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class NotificationConfiguration : IEntityTypeConfiguration<Notification>
{
    public void Configure(EntityTypeBuilder<Notification> b)
    {
        b.HasKey(x => x.NotificationId);
        b.Property(x => x.Type).HasMaxLength(30).IsRequired();
        b.Property(x => x.ReferenceType).HasMaxLength(30);
        b.Property(x => x.Message).HasMaxLength(400).IsRequired();
        b.Property(x => x.DeduplicationKey).HasMaxLength(200)
            .UseCollation(ConfigurationRules.BinaryCollation).IsRequired();
        b.HasIndex(x => new { x.UserId, x.DeduplicationKey }).IsUnique();
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("Notifications", t =>
        {
            t.HasCheckConstraint("CK_Notifications_Reference", "([ReferenceType] IS NULL AND [ReferenceId] IS NULL) OR ([ReferenceType] IS NOT NULL AND [ReferenceId] IS NOT NULL)");
            t.HasCheckConstraint("CK_Notifications_RequiredText", "LEN(LTRIM(RTRIM([Type]))) > 0 AND LEN(LTRIM(RTRIM([Message]))) > 0 AND LEN(LTRIM(RTRIM([DeduplicationKey]))) > 0");
        });
    }
}

