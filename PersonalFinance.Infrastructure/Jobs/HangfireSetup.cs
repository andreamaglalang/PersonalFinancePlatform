using Hangfire;
using Hangfire.SqlServer;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace PersonalFinance.Infrastructure.Jobs;

/// <summary>
/// Hangfire wiring. Disabled unless configuration "Hangfire:Enabled" is "true". Even when enabled, Hangfire never
/// creates or migrates its storage tables (PrepareSchemaIfNecessary = false): the schema must be installed
/// separately and explicitly. Recurring jobs use fixed IDs, so re-registration on restart replaces, never duplicates.
/// </summary>
public static class HangfireSetup
{
    public const string BillRemindersJobId = "owli-bill-reminders";
    public const string BudgetAlertsJobId = "owli-budget-alerts";
    public const string MonthlySummariesJobId = "owli-monthly-summaries";

    public static bool IsEnabled(IConfiguration configuration) =>
        string.Equals(configuration["Hangfire:Enabled"], "true", StringComparison.OrdinalIgnoreCase);

    public static IServiceCollection AddFinanceJobs(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddScoped<FinanceJobs>();
        if (!IsEnabled(configuration)) return services;

        var connectionString = configuration.GetConnectionString("DefaultConnection");
        if (string.IsNullOrWhiteSpace(connectionString))
            throw new InvalidOperationException("Hangfire is enabled but ConnectionStrings:DefaultConnection is missing.");
        services.AddHangfire(config => config
            .UseSimpleAssemblyNameTypeSerializer()
            .UseRecommendedSerializerSettings()
            .UseSqlServerStorage(() => new SqlConnection(connectionString), new SqlServerStorageOptions
            {
                PrepareSchemaIfNecessary = false,
                QueuePollInterval = TimeSpan.FromSeconds(15),
            }));
        services.AddHangfireServer();
        services.AddHostedService<FinanceJobRegistration>();
        return services;
    }
}

/// <summary>Registers (or replaces) the three recurring jobs once at startup.</summary>
internal sealed class FinanceJobRegistration(IRecurringJobManager jobs) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var local = new RecurringJobOptions { TimeZone = TimeZoneInfo.Local };
        jobs.AddOrUpdate<FinanceJobs>(HangfireSetup.BillRemindersJobId, j => j.SendBillRemindersAsync(CancellationToken.None), "0 7 * * *", local);
        jobs.AddOrUpdate<FinanceJobs>(HangfireSetup.BudgetAlertsJobId, j => j.SendBudgetAlertsAsync(CancellationToken.None), "0 * * * *", local);
        jobs.AddOrUpdate<FinanceJobs>(HangfireSetup.MonthlySummariesJobId, j => j.GenerateMonthlySummariesAsync(CancellationToken.None), "30 0 1 * *", local);
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
