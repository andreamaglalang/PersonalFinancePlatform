using Hangfire;
using Hangfire.SqlServer;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Jobs;

namespace PersonalFinance.Worker;

/// <summary>Composition for the independent OWLI worker: shared finance core plus worker-only Hangfire processing
/// and recurring registration. No HTTP, JWT, current-user or API services are registered.</summary>
public static class WorkerSetup
{
    public static IServiceCollection AddFinanceWorker(this IServiceCollection services, IConfiguration configuration)
    {
        var finance = configuration.GetConnectionString("DefaultConnection");
        var hangfire = configuration.GetConnectionString("Hangfire");
        if (string.IsNullOrWhiteSpace(finance) || string.IsNullOrWhiteSpace(hangfire))
            throw new InvalidOperationException("The worker requires separate finance (DefaultConnection) and Hangfire connection strings.");
        HangfireSetup.EnsureDedicatedStorage(hangfire, finance);

        services.AddFinanceCore(configuration);
        // Same serializer conventions as the previously approved setup, so persisted job data stays compatible.
        services.AddHangfire(config => config
            .UseSimpleAssemblyNameTypeSerializer()
            .UseRecommendedSerializerSettings()
            .UseSqlServerStorage(() => new SqlConnection(hangfire), new SqlServerStorageOptions
            {
                PrepareSchemaIfNecessary = false, // Schema is provisioned separately; never create or upgrade tables here.
                QueuePollInterval = TimeSpan.FromSeconds(configuration.GetValue("Hangfire:QueuePollIntervalSeconds", 15)),
            }));
        services.AddHangfireServer(options => options.ServerName = "owli-worker:" + Environment.MachineName);
        services.AddHostedService<RecurringJobRegistration>();
        return services;
    }
}

/// <summary>Registers (AddOrUpdate) the three stable recurring jobs once at worker startup.</summary>
internal sealed class RecurringJobRegistration(IRecurringJobManager jobs) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        HangfireSetup.RegisterRecurringJobs(jobs);
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
