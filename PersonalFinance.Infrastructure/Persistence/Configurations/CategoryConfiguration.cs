using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class CategoryConfiguration : IEntityTypeConfiguration<Category>
{
    public void Configure(EntityTypeBuilder<Category> b)
    {
        b.HasKey(x => x.CategoryId);
        b.Property(x => x.Name).HasMaxLength(50).IsRequired();
        // The computed expression owns COLLATE; UseCollation here would append it again in SQL.
        b.Property<string>(CategoryNameNormalization.PropertyName)
            .HasColumnType("nvarchar(50)").HasMaxLength(50).IsUnicode()
            .HasComputedColumnSql(CategoryNameNormalization.ComputedSql, stored: true);
        b.HasIndex(x => x.UserId).HasDatabaseName("IX_Categories_UserId");
        b.HasIndex("UserId", "Type", CategoryNameNormalization.PropertyName)
            .HasDatabaseName(CategoryNameNormalization.UniqueIndexName)
            .IsUnique().IsClustered(false).HasFilter(null);
        b.Property(x => x.Type).HasConversion<string>().HasMaxLength(10).IsRequired();
        b.Property(x => x.ColorHEX).HasColumnType("char(7)");
        b.Property(x => x.IsActive).HasDefaultValue(true);
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("Categories", t =>
        {
            t.HasCheckConstraint("CK_Categories_Type", "[Type] COLLATE Latin1_General_100_BIN2 IN ('Income','Expense')");
            t.HasCheckConstraint("CK_Categories_NormalizedName", "[NormalizedName] IS NOT NULL AND DATALENGTH([NormalizedName]) > 0");
            t.HasCheckConstraint("CK_Categories_Name", "LEN(LTRIM(RTRIM([Name]))) > 0");
            t.HasCheckConstraint("CK_Categories_Color", "[ColorHEX] IS NULL OR ([ColorHEX] COLLATE Latin1_General_100_BIN2 LIKE '#[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]')");
        });
    }
}

