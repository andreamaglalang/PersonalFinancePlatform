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
