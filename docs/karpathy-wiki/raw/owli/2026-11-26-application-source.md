# Application source snapshot — targeted excerpts

> Source: andreamaglalang/PersonalFinancePlatform at 6687eb95ab3741a747da36d334aa3420c4ed7c23
> Collected: 2026-11-26
> Published: 2026-11-26

Application source remains unchanged through the reviewed Phase 0 proposal at 5b5b2756cb45ea347a8bcc1db6773468c94090ab. No worker project exists in this snapshot. These are source observations, not runtime results.

## PersonalFinance.Api/Program.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Api/Program.cs

```text
builder.Services.AddControllers();
builder.Services.AddInfrastructure(builder.Configuration);
builder.Services.AddApiAuthentication(builder.Configuration);
builder.Services.AddProblemDetails();
```

## PersonalFinance.Infrastructure/DependencyInjection.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/DependencyInjection.cs

```text
        services.AddScoped<IFinancialWriteScope, SqlFinancialWriteScope>();
        services.AddScoped<IConversionService, ConversionService>();
        services.AddScoped<IExchangeRateQuoteService, ExchangeRateQuoteService>();
        services.AddScoped<ITransactionService, TransactionService>();
        services.AddScoped<ICategoryService, CategoryService>();
        services.AddScoped<IBudgetUsageService, BudgetUsageService>();
        services.AddScoped<IBudgetService, BudgetService>();
        services.AddScoped<ISavingsGoalService, SavingsGoalService>();
        services.AddScoped<IRecurringBillService, RecurringBillService>();
        services.AddScoped<IBillPaymentService, BillPaymentService>();
        services.AddScoped<IReportService, ReportService>();
        services.AddScoped<INotificationService, NotificationService>();
        // Background jobs: off unless Hangfire:Enabled=true; never creates Hangfire tables.
        services.AddFinanceJobs(configuration);

        services.AddOptions<CurrencyPolicyOptions>()
            .Bind(configuration.GetSection(CurrencyPolicyOptions.SectionName))
            .Validate(
                x => x.MaximumRateAgeDays >= 0,
                "MaximumRateAgeDays cannot be negative.");
```

## PersonalFinance.Infrastructure/Jobs/HangfireSetup.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Jobs/HangfireSetup.cs

```text
    public const string ManilaTimeZoneId = "Asia/Manila";
    public static TimeZoneInfo ManilaTimeZone { get; } = TimeZoneInfo.FindSystemTimeZoneById(ManilaTimeZoneId);

    // Keep the established IDs so a later approved activation updates these recurring entries in place.
    public const string BillRemindersJobId = "owli-bill-reminders";
    public const string BudgetAlertsJobId = "owli-budget-alerts";
    public const string MonthlySummariesJobId = "owli-monthly-summaries";
```
```text
    public static void EnsureDedicatedStorage(string hangfireConnectionString, string financeConnectionString)
    {
        if (string.IsNullOrWhiteSpace(hangfireConnectionString))
            throw new InvalidOperationException("Hangfire requires a dedicated SQL storage connection.");
        if (string.IsNullOrWhiteSpace(financeConnectionString))
            throw new InvalidOperationException("Hangfire requires the existing finance connection for isolation validation.");

        var hangfire = new SqlConnectionStringBuilder(hangfireConnectionString);
        var finance = new SqlConnectionStringBuilder(financeConnectionString);
        if (!string.Equals(hangfire.InitialCatalog, "OWLI_Hangfire", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage must target the OWLI_Hangfire database.");
        if (string.IsNullOrWhiteSpace(hangfire.DataSource)
            || !string.Equals(hangfire.DataSource, finance.DataSource, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage must use the finance SQL instance and the separate OWLI_Hangfire catalog.");
        if (string.Equals(hangfire.InitialCatalog, finance.InitialCatalog, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage cannot share the finance database.");
    }

    public static void RegisterRecurringJobs(IRecurringJobManager jobs)
    {
        ArgumentNullException.ThrowIfNull(jobs);
        var local = new RecurringJobOptions { TimeZone = ManilaTimeZone };
        jobs.AddOrUpdate<FinanceJobs>(BillRemindersJobId,
            job => job.SendBillRemindersAsync(CancellationToken.None), Cron.Daily(7), local);
        jobs.AddOrUpdate<FinanceJobs>(BudgetAlertsJobId,
            job => job.SendBudgetAlertsAsync(CancellationToken.None), Cron.Hourly(), local);
        jobs.AddOrUpdate<FinanceJobs>(MonthlySummariesJobId,
            job => job.GenerateMonthlySummariesAsync(CancellationToken.None), Cron.Monthly(1, 0, 30), local);
    }

    public static IServiceCollection AddFinanceJobs(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddScoped<FinanceJobs>();
        if (!IsEnabled(configuration)) return services;

        var financeConnection = configuration.GetConnectionString("DefaultConnection");
        var hangfireConnection = configuration.GetConnectionString("Hangfire");
        if (string.IsNullOrWhiteSpace(financeConnection) || string.IsNullOrWhiteSpace(hangfireConnection))
            throw new InvalidOperationException("Hangfire requires separate finance and Hangfire SQL connection strings.");
        EnsureDedicatedStorage(hangfireConnection, financeConnection);

        services.AddHangfire(config => config
            .UseSimpleAssemblyNameTypeSerializer()
            .UseRecommendedSerializerSettings()
            .UseSqlServerStorage(() => new SqlConnection(hangfireConnection), new SqlServerStorageOptions
            {
                PrepareSchemaIfNecessary = false,
                QueuePollInterval = TimeSpan.FromSeconds(15),
            }));
        services.AddHangfireServer();
        services.AddHostedService<FinanceJobRegistration>();
        return services;
```

## PersonalFinance.Infrastructure/Jobs/FinanceJobs.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Jobs/FinanceJobs.cs

```text
public sealed class FinanceJobs(ApplicationDbContext db, IBudgetUsageService budgets, IFinancialWriteScope writes, TimeProvider clock)
{
    public const string BillReminderType = "BillReminder";
    public const string BudgetAlertType = "BudgetAlert";
    public const string MonthlySummaryType = "MonthlySummary";

    private DateOnly Today => HangfireSetup.ManilaToday(clock);
    private static string Money(decimal v, string currency) => currency + " " + v.ToString("#,##0.00", CultureInfo.InvariantCulture);

    /// <summary>One reminder per bill per due date, from ReminderDaysBefore days ahead up to the due date.</summary>
    [DisableConcurrentExecution(600), AutomaticRetry(Attempts = 3)]
    public async Task<int> SendBillRemindersAsync(CancellationToken ct)
    {
        var today = Today;
        var due = await db.RecurringBills.AsNoTracking()
            .Where(b => b.IsActive && b.NextDueDate >= today && b.NextDueDate.AddDays(-b.ReminderDaysBefore) <= today)
            .Select(b => new { b.BillId, b.UserId, b.Name, b.Amount, b.CurrencyCode, b.NextDueDate }).ToListAsync(ct);
```
```text
        var month = new DateOnly(Today.Year, Today.Month, 1);
        var users = await db.Budgets.AsNoTracking().Where(b => b.PeriodMonth == month).Select(b => b.UserId).Distinct().ToListAsync(ct);
        var created = 0;
        foreach (var userId in users)
        {
            created += await writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
            {
                db.ChangeTracker.Clear();
                var periods = await db.Budgets.AsNoTracking().Where(b => b.UserId == userId && b.PeriodMonth == month)
                    .Select(b => new BudgetPeriod(b.CategoryId, b.PeriodMonth)).ToArrayAsync(token);
                var results = await budgets.RecalculateAsync(userId, periods, token);
                var n = 0;
                foreach (var r in results)
                {
                    if (!Enum.TryParse<BudgetAlertStatus>(r.Status, out var status) || status == BudgetAlertStatus.OK) continue;
                    var budget = await db.Budgets.SingleAsync(b => b.BudgetId == r.BudgetId && b.UserId == userId, token);
                    if (budget.LastAlertStatus == status) continue; // already alerted for this transition
                    budget.LastAlertStatus = status;
                    budget.LastAlertAtUtc = clock.GetUtcNow().UtcDateTime;
                    db.Notifications.Add(new Notification
```
```text
    /// <summary>Previous month's summary per user with activity; existing summaries are left unchanged.</summary>
    [DisableConcurrentExecution(1800), AutomaticRetry(Attempts = 3)]
    public async Task<int> GenerateMonthlySummariesAsync(CancellationToken ct)
    {
        var current = new DateOnly(Today.Year, Today.Month, 1);
        var month = current.AddMonths(-1);
        var active = await db.Transactions.AsNoTracking().Where(t => t.TransactionDate >= month && t.TransactionDate < current)
            .Select(t => t.UserId).Union(db.GoalContributions.AsNoTracking()
                .Where(c => c.ContributedOn >= month && c.ContributedOn < current).Select(c => c.Goal.UserId))
            .Distinct().ToListAsync(ct);
        var created = 0;
        foreach (var userId in active)
        {
            if (await db.MonthlySummaries.AsNoTracking().AnyAsync(s => s.UserId == userId && s.SummaryMonth == month, ct)) continue;
            try
            {
                // One transaction under the user's write lock: the summary is saved first so its generated SummaryId can be
                // the notification's ReferenceId (CK_Notifications_Reference requires ReferenceType and ReferenceId together).
                // Any failure before commit rolls back both rows.
                created += await writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
                {
                    db.ChangeTracker.Clear();
                    if (await db.MonthlySummaries.AsNoTracking().AnyAsync(s => s.UserId == userId && s.SummaryMonth == month, token)) return 0;
                    var totals = await MonthlyTotals.ComputeAsync(db, userId, month, token);
                    var summary = new MonthlySummary
                    {
                        UserId = userId, SummaryMonth = month, TotalIncome = totals.Income, TotalExpense = totals.Expenses,
                        NetCashFlow = totals.Income - totals.Expenses, SavingsAllocated = totals.Savings, GeneratedAtUtc = clock.GetUtcNow().UtcDateTime,
                    };
```

## PersonalFinance.Infrastructure/Jobs/HangfireJobRegistration.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Jobs/HangfireJobRegistration.cs

```text
    public const string ManilaTimeZoneId = "Asia/Manila";
    public static readonly TimeZoneInfo ManilaTimeZone = TimeZoneInfo.FindSystemTimeZoneById(ManilaTimeZoneId);

    public static void Register(IRecurringJobManager manager)
    {
        ArgumentNullException.ThrowIfNull(manager);
        var options = new RecurringJobOptions { TimeZone = ManilaTimeZone };
        manager.AddOrUpdate<UpcomingBillReminderJob>("owli-upcoming-bill-reminders",
            job => job.RunAsync(CancellationToken.None), Cron.Daily(9), options);
        manager.AddOrUpdate<MonthlyFinancialSummaryJob>("owli-monthly-financial-summaries",
            job => job.RunAsync(CancellationToken.None), Cron.Monthly(1, 9), options);
        manager.AddOrUpdate<BudgetThresholdAlertJob>("owli-budget-threshold-alerts",
            job => job.RunAsync(CancellationToken.None), Cron.MinuteInterval(15), options);
```

## PersonalFinance.Infrastructure/Persistence/Configurations/NotificationConfiguration.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Persistence/Configurations/NotificationConfiguration.cs

```text
        b.HasKey(x => x.NotificationId);
        b.Property(x => x.Type).HasMaxLength(30).IsRequired();
        b.Property(x => x.ReferenceType).HasMaxLength(30);
        b.Property(x => x.Message).HasMaxLength(400).IsRequired();
        b.Property(x => x.DeduplicationKey).HasMaxLength(200)
            .UseCollation(ConfigurationRules.BinaryCollation).IsRequired();
        b.HasIndex(x => new { x.UserId, x.DeduplicationKey }).IsUnique();
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        b.ToTable("Notifications", t =>
```

## PersonalFinance.Infrastructure/Persistence/Configurations/MonthlySummaryConfiguration.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Persistence/Configurations/MonthlySummaryConfiguration.cs

```text
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
```

## PersonalFinance.Infrastructure/Finance/SqlFinancialWriteScope.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Finance/SqlFinancialWriteScope.cs

```text
    public async Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
    {
        try
        {
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, ct);
            foreach (var resource in resources.Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal))
            {
                await db.Database.ExecuteSqlInterpolatedAsync($"""
                    DECLARE @result int;
                    EXEC @result = sys.sp_getapplock @Resource={resource},
                        @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=10000;
                    IF @result < 0 THROW 51011, 'Financial write could not acquire its transaction lock.', 1;
                    """, ct);
            }
            var result = await operation(ct);
            await transaction.CommitAsync(ct);
            return result;
```

## PersonalFinance.Infrastructure/Persistence/Configurations/ConfigurationRules.cs

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/Persistence/Configurations/ConfigurationRules.cs

```text
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal static class ConfigurationRules
{
    internal const string BinaryCollation = "Latin1_General_100_BIN2";
    internal static string CurrencyCheck(string column) =>
        $"DATALENGTH([{column}]) = 3 AND [{column}] COLLATE {BinaryCollation} NOT LIKE '%[^A-Z]%'";
    internal static void Currency(PropertyBuilder<string> property) =>
        property.HasColumnType("char(3)").IsRequired();
    internal static void Money(PropertyBuilder<decimal> property) => property.HasPrecision(18, 2);
}

```

## PersonalFinance.Infrastructure/PersonalFinance.Infrastructure.csproj

Source: https://github.com/andreamaglalang/PersonalFinancePlatform/blob/6687eb95ab3741a747da36d334aa3420c4ed7c23/PersonalFinance.Infrastructure/PersonalFinance.Infrastructure.csproj

```text
    <ProjectReference Include="..\PersonalFinance.Domain\PersonalFinance.Domain.csproj" />
    <PackageReference Include="Microsoft.EntityFrameworkCore.SqlServer" Version="10.0.13" />
    <PackageReference Include="Microsoft.AspNetCore.Identity.EntityFrameworkCore" Version="10.0.13" />
    <PackageReference Include="System.Security.Cryptography.Pkcs" Version="10.0.13" />
    <PackageReference Include="Hangfire.Core" Version="1.8.25" />
    <PackageReference Include="Hangfire.SqlServer" Version="1.8.25" />
    <PackageReference Include="Hangfire.NetCore" Version="1.8.25" />
    <!-- Hangfire.Core references Newtonsoft.Json 11.0.1 (GHSA-5crp-9r3c-p9vr); pin a patched version. -->
    <PackageReference Include="Newtonsoft.Json" Version="13.0.4" />
  </ItemGroup>

  <ItemGroup>
    <FrameworkReference Include="Microsoft.AspNetCore.App" />
```
