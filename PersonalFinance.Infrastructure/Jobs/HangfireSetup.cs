using Hangfire;
using Hangfire.SqlServer;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace PersonalFinance.Infrastructure.Jobs;

/// <summary>Opt-in Hangfire setup using the separately provisioned OWLI_Hangfire catalog.</summary>
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
    }
}

/// <summary>Registers the stable recurring job IDs once at startup, only when Hangfire is explicitly enabled.</summary>
internal sealed class FinanceJobRegistration(IRecurringJobManager jobs) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        HangfireSetup.RegisterRecurringJobs(jobs);
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}