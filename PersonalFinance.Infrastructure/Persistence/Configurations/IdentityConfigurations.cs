using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class ApplicationUserConfiguration : IEntityTypeConfiguration<ApplicationUser>
{
    public void Configure(EntityTypeBuilder<ApplicationUser> b)
    {
        b.Property(x => x.FullName).HasMaxLength(100).IsRequired();
        ConfigurationRules.Currency(b.Property(x => x.BaseCurrency));
        b.Property(x => x.BaseCurrency).HasDefaultValue("PHP");
        b.Property(x => x.CreatedAtUtc).HasColumnType("datetime2");
        b.Property(x => x.Email).HasMaxLength(256).IsRequired();
        // Identity normalizes email before persistence. Explicit CI collation prevents
        // case variants from bypassing uniqueness regardless of database default collation.
        b.Property(x => x.NormalizedEmail).HasMaxLength(256).IsRequired()
            .UseCollation("Latin1_General_100_CI_AS");
        b.HasIndex(x => x.NormalizedEmail).HasDatabaseName("EmailIndex").IsUnique().HasFilter(null);
        b.ToTable("AspNetUsers", t =>
        {
            t.HasTrigger("TR_AspNetUsers_LockBaseCurrency");
            t.UseSqlOutputClause(false);
            t.HasCheckConstraint("CK_AspNetUsers_BaseCurrency", ConfigurationRules.CurrencyCheck("BaseCurrency"));
            t.HasCheckConstraint("CK_AspNetUsers_FullName", "LEN(LTRIM(RTRIM([FullName]))) > 0");
            t.HasCheckConstraint("CK_AspNetUsers_Email", "LEN(LTRIM(RTRIM([Email]))) > 0 AND LEN(LTRIM(RTRIM([NormalizedEmail]))) > 0");
        });
    }
}

internal sealed class ApplicationRoleConfiguration : IEntityTypeConfiguration<IdentityRole<int>>
{
    public void Configure(EntityTypeBuilder<IdentityRole<int>> b)
    {
        b.HasData(
            new IdentityRole<int> { Id = 1, Name = ApplicationRoles.User, NormalizedName = "USER", ConcurrencyStamp = "finance-role-user-v1" },
            new IdentityRole<int> { Id = 2, Name = ApplicationRoles.Administrator, NormalizedName = "ADMINISTRATOR", ConcurrencyStamp = "finance-role-administrator-v1" });
    }
}

internal sealed class RefreshTokenConfiguration : IEntityTypeConfiguration<RefreshToken>
{
    public void Configure(EntityTypeBuilder<RefreshToken> b)
    {
        b.HasKey(x => x.Id);
        b.Property(x => x.TokenHash).HasColumnType("char(64)").UseCollation(ConfigurationRules.BinaryCollation).IsRequired();
        b.Property(x => x.ReplacedByTokenHash).HasColumnType("char(64)").UseCollation(ConfigurationRules.BinaryCollation);
        b.HasIndex(x => x.TokenHash).IsUnique();
        b.HasIndex(x => new { x.UserId, x.TokenFamilyId });
        b.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("RefreshTokens", t =>
        {
            t.HasCheckConstraint("CK_RefreshTokens_TokenHash", "DATALENGTH([TokenHash]) = 64 AND [TokenHash] NOT LIKE '%[^0-9A-F]%'");
            t.HasCheckConstraint("CK_RefreshTokens_ReplacementHash", "[ReplacedByTokenHash] IS NULL OR (DATALENGTH([ReplacedByTokenHash]) = 64 AND [ReplacedByTokenHash] NOT LIKE '%[^0-9A-F]%')");
            t.HasCheckConstraint("CK_RefreshTokens_Expiry", "[ExpiresAtUtc] > [CreatedAtUtc]");
        });
    }
}

