using Hangfire;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;

namespace PersonalFinance.Infrastructure.Jobs;

/// <summary>Shared Hangfire definitions for the selected <see cref="FinanceJobs"/> family: stable IDs, Manila schedules and
/// the storage isolation guard. Processing and recurring registration are hosted only by PersonalFinance.Worker.</summary>
public static class HangfireSetup
{
    public const string ManilaTimeZoneId = "Asia/Manila";
    public static TimeZoneInfo ManilaTimeZone { get; } = TimeZoneInfo.FindSystemTimeZoneById(ManilaTimeZoneId);

    // Keep the established IDs so a later approved activation updates these recurring entries in place.
    public const string BillRemindersJobId = "owli-bill-reminders";
    public const string BudgetAlertsJobId = "owli-budget-alerts";
    public const string MonthlySummariesJobId = "owli-monthly-summaries";

    public static bool IsEnabled(IConfiguration configuration) =>
        string.Equals(configuration["Hangfire:Enabled"], "true", StringComparison.OrdinalIgnoreCase);

    public static DateOnly ManilaToday(TimeProvider clock) =>
        DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(clock.GetUtcNow(), ManilaTimeZone).DateTime);

    public const string OperationalCatalog = "OWLI_Hangfire";
    public const string TestCatalogPrefix = "OWLI_HangfireTests_";
    private static readonly string[] DisposableFinancePrefixes = ["OWLI_Runtime_", "PersonalFinanceSqlTests_"];

    /// <summary>Operational storage is OWLI_Hangfire. A disposable OWLI_HangfireTests_* catalog may only be paired with a
    /// disposable finance catalog, so tests can never schedule work against operational finance data.</summary>
    public static void EnsureDedicatedStorage(string hangfireConnectionString, string financeConnectionString)
    {
        if (string.IsNullOrWhiteSpace(hangfireConnectionString))
            throw new InvalidOperationException("Hangfire requires a dedicated SQL storage connection.");
        if (string.IsNullOrWhiteSpace(financeConnectionString))
            throw new InvalidOperationException("Hangfire requires the existing finance connection for isolation validation.");

        var hangfire = new SqlConnectionStringBuilder(hangfireConnectionString);
        var finance = new SqlConnectionStringBuilder(financeConnectionString);
        var operational = string.Equals(hangfire.InitialCatalog, OperationalCatalog, StringComparison.OrdinalIgnoreCase);
        var disposable = IsDisposable(hangfire.InitialCatalog, TestCatalogPrefix);
        if (!operational && !disposable)
            throw new InvalidOperationException("Hangfire storage must target the OWLI_Hangfire database or a disposable OWLI_HangfireTests_* database.");
        if (disposable && !DisposableFinancePrefixes.Any(prefix => IsDisposable(finance.InitialCatalog, prefix)))
            throw new InvalidOperationException("Disposable Hangfire storage must be paired with a disposable finance database.");
        if (string.IsNullOrWhiteSpace(hangfire.DataSource)
            || !string.Equals(hangfire.DataSource, finance.DataSource, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage must use the finance SQL instance and a separate Hangfire catalog.");
        if (string.Equals(hangfire.InitialCatalog, finance.InitialCatalog, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage cannot share the finance database.");
    }

    private static bool IsDisposable(string? catalog, string prefix) =>
        catalog is not null && catalog.Length > prefix.Length && catalog.StartsWith(prefix, StringComparison.Ordinal)
        && catalog[prefix.Length..].All(c => char.IsAsciiLetterOrDigit(c) || c == '_');

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
}
