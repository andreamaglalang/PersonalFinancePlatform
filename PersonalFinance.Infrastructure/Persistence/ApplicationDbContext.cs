
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Persistence;

public sealed class ApplicationDbContext(DbContextOptions<ApplicationDbContext> options)
    : IdentityDbContext<ApplicationUser, IdentityRole<int>, int>(options)
{
    public DbSet<Category> Categories => Set<Category>();
    public DbSet<FinanceTransaction> Transactions => Set<FinanceTransaction>();
    public DbSet<Budget> Budgets => Set<Budget>();
    public DbSet<RecurringBill> RecurringBills => Set<RecurringBill>();
    public DbSet<BillPaymentReceipt> BillPaymentReceipts => Set<BillPaymentReceipt>();
    public DbSet<SavingsGoal> SavingsGoals => Set<SavingsGoal>();
    public DbSet<GoalContribution> GoalContributions => Set<GoalContribution>();
    public DbSet<GoalContributionRequest> GoalContributionRequests => Set<GoalContributionRequest>();
    public DbSet<ExchangeRate> ExchangeRates => Set<ExchangeRate>();
    public DbSet<Notification> Notifications => Set<Notification>();
    public DbSet<MonthlySummary> MonthlySummaries => Set<MonthlySummary>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

    public DbSet<PlatformAnnouncement> PlatformAnnouncements => Set<PlatformAnnouncement>();
    public DbSet<SupportRequest> SupportRequests => Set<SupportRequest>();
    public DbSet<SupportReply> SupportReplies => Set<SupportReply>();
    public DbSet<PlatformSetting> PlatformSettings => Set<PlatformSetting>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);
        builder.ApplyConfigurationsFromAssembly(typeof(ApplicationDbContext).Assembly);

        var announcements = builder.Entity<PlatformAnnouncement>();
        announcements.HasIndex(x => new { x.Status, x.PublishedAtUtc, x.Id });
        announcements.Property(x => x.UpdatedAtUtc).IsConcurrencyToken();

        var requests = builder.Entity<SupportRequest>();
        requests.HasIndex(x => new { x.OwnerId, x.UpdatedAtUtc, x.Id });
        requests.HasIndex(x => new { x.UpdatedAtUtc, x.Id });
        requests.Property(x => x.UpdatedAtUtc).IsConcurrencyToken();
        requests.HasMany(x => x.Replies).WithOne().HasForeignKey(x => x.SupportRequestId);
        requests.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.OwnerId).OnDelete(DeleteBehavior.Restrict);

        var settings = builder.Entity<PlatformSetting>();
        settings.HasIndex(x => x.Key).IsUnique();
        settings.Property(x => x.UpdatedAtUtc).IsConcurrencyToken();
        settings.HasData(
            new PlatformSetting { Id = 1, Key = "welcomeMessage" },
            new PlatformSetting { Id = 2, Key = "supportInstructions" });

        // Bound Identity composite keys to safe SQL Server index widths, consistently at design/runtime.
        builder.Entity<IdentityUserLogin<int>>().Property(x => x.LoginProvider).HasMaxLength(128);
        builder.Entity<IdentityUserLogin<int>>().Property(x => x.ProviderKey).HasMaxLength(128);
        builder.Entity<IdentityUserToken<int>>().Property(x => x.LoginProvider).HasMaxLength(128);
        builder.Entity<IdentityUserToken<int>>().Property(x => x.Name).HasMaxLength(128);

        foreach (var property in builder.Model.GetEntityTypes().SelectMany(x => x.GetProperties()))
        {
            if (property.Name.EndsWith("Utc", StringComparison.Ordinal)
                && (property.ClrType == typeof(DateTime) || property.ClrType == typeof(DateTime?)))
                property.SetValueConverter(new UtcDateTimeConverter());
        }
    }
}
