using Hangfire;
using Hangfire.Common;
using Hangfire.States;
using Microsoft.Data.SqlClient;
using System.Linq.Expressions;

namespace PersonalFinance.Infrastructure.Jobs;

/// <summary>Schedules the OWLI automations in Philippine local time.</summary>
public static class HangfireJobRegistration
{
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
    }

    public static void EnsureDedicatedStorage(string hangfireConnectionString, string? financeConnectionString)
    {
        if (string.IsNullOrWhiteSpace(hangfireConnectionString))
            throw new InvalidOperationException("Hangfire requires a dedicated SQL storage connection.");

        var jobs = new SqlConnectionStringBuilder(hangfireConnectionString);
        if (string.IsNullOrWhiteSpace(jobs.InitialCatalog)
            || new[] { "PersonalFinanceDb", "master", "model", "msdb", "tempdb" }
                .Contains(jobs.InitialCatalog, StringComparer.OrdinalIgnoreCase))
            throw new InvalidOperationException("Hangfire storage must use its own non-system database.");

        if (!string.IsNullOrWhiteSpace(financeConnectionString))
        {
            var finance = new SqlConnectionStringBuilder(financeConnectionString);
            if (jobs.InitialCatalog.Equals(finance.InitialCatalog, StringComparison.OrdinalIgnoreCase)
                && jobs.DataSource.Equals(finance.DataSource, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Hangfire storage cannot share the finance database.");
        }
    }
}
