using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PersonalFinance.Infrastructure.Jobs;
using PersonalFinance.Worker;

// Independent OWLI background worker (Generic Host). It loads only its own configuration: optional
// OWLI_WORKER_SETTINGS JSON file, then environment variables, then command-line arguments.
var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { Args = args, DisableDefaults = false });
var settingsFile = Environment.GetEnvironmentVariable("OWLI_WORKER_SETTINGS");
if (!string.IsNullOrWhiteSpace(settingsFile))
{
    builder.Configuration.AddJsonFile(Path.GetFullPath(settingsFile), optional: false, reloadOnChange: false);
    builder.Configuration.AddEnvironmentVariables();
    builder.Configuration.AddCommandLine(args);
}

using var startupLog = LoggerFactory.Create(logging => logging.AddSimpleConsole());
var log = startupLog.CreateLogger("PersonalFinance.Worker");
if (!HangfireSetup.IsEnabled(builder.Configuration))
{
    // Operational activation stays off until a controlled cutover is approved.
    log.LogWarning("OWLI worker is disabled (Hangfire:Enabled is not true). No storage connection was opened.");
    return 0;
}

if (builder.Configuration.GetValue<bool>("Development:TestStorageOnly"))
{
    if (!builder.Environment.IsDevelopment())
    {
        log.LogError("The test-storage launch profile requires Development.");
        return 2;
    }
    try { await DevelopmentStorageValidation.ValidateAsync(builder.Configuration); }
    catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Microsoft.Data.SqlClient.SqlException)
    {
        // SQL/connection-parser messages can contain private configuration. Keep diagnostics bounded.
        log.LogError("Development worker storage validation failed ({Type}). Verify the approved test catalogs, schema, TLS and absence of another worker.", exception.GetType().Name);
        return 2;
    }
}

try
{
    builder.Services.AddFinanceWorker(builder.Configuration);
}
catch (InvalidOperationException ex)
{
    log.LogError("OWLI worker configuration rejected: {Reason}", ex.Message);
    return 2;
}
builder.Services.Configure<HostOptions>(options => options.ShutdownTimeout = TimeSpan.FromSeconds(30));
builder.ConfigureContainer(new DefaultServiceProviderFactory(new ServiceProviderOptions { ValidateOnBuild = true, ValidateScopes = true }));

using var host = builder.Build();
await host.RunAsync();
return 0;
