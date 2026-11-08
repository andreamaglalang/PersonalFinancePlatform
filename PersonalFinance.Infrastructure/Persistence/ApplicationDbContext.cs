
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

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);
        builder.ApplyConfigurationsFromAssembly(typeof(ApplicationDbContext).Assembly);

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
