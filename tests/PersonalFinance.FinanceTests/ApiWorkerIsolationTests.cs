using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure;

namespace PersonalFinance.FinanceTests;

/// <summary>Roadmap R2 acceptance A: the API composition never hosts Hangfire processing, recurring registration or a
/// direct job execution path, regardless of configuration. (Acceptance B, worker-only processing, is in HangfireTests.)</summary>
internal static class ApiWorkerIsolationTests
{
    private static void Assert(bool condition, string detail) { if (!condition) throw new Exception(detail); }

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        foreach (var enabled in new[] { "false", "true" })
            await check($"API services register no Hangfire server, storage or recurring registration (Hangfire:Enabled={enabled})", _ =>
            {
                var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["Hangfire:Enabled"] = enabled,
                    ["ConnectionStrings:DefaultConnection"] = "Server=localhost;Database=OWLI_Runtime_isolation;User ID=x;Password=y",
                    ["ConnectionStrings:Hangfire"] = "Server=localhost;Database=OWLI_HangfireTests_isolation;User ID=x;Password=y",
                    ["Jwt:Issuer"] = "i", ["Jwt:Audience"] = "a", ["Jwt:SigningKeyBase64"] = Convert.ToBase64String(new byte[64]),
                }).Build();
                var services = new ServiceCollection();
                services.AddInfrastructure(configuration);
                services.AddApiAuthentication(configuration);
                var hangfire = services.Where(d => FromHangfire(d.ServiceType) || FromHangfire(d.ImplementationType)).ToArray();
                Assert(hangfire.Length == 0, "Hangfire services registered: " + string.Join(", ", hangfire.Select(d => d.ServiceType.Name)));
                var hosted = services.Where(d => d.ServiceType == typeof(IHostedService)).Select(d => d.ImplementationType?.FullName ?? "factory").ToArray();
                Assert(hosted.All(name => !name.Contains("Job", StringComparison.OrdinalIgnoreCase) && !name.Contains("Hangfire", StringComparison.OrdinalIgnoreCase)),
                    "API hosted services include job processing: " + string.Join(", ", hosted));
                return Task.CompletedTask;
            });

        await check("API source has no Hangfire hosting or direct job controller", _ =>
        {
            var api = FindApiDirectory();
            Assert(!File.Exists(Path.Combine(api, "Controllers", "DevJobsController.cs")), "DevJobsController still exists");
            var offenders = Directory.EnumerateFiles(api, "*.cs", SearchOption.AllDirectories)
                .Where(f => !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") && !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
                .Where(f => File.ReadAllText(f) is var text && (text.Contains("Hangfire") || text.Contains("FinanceJobs")))
                .ToArray();
            Assert(offenders.Length == 0, "API references Hangfire/FinanceJobs: " + string.Join(", ", offenders.Select(Path.GetFileName)));
            return Task.CompletedTask;
        });
    }

    private static bool FromHangfire(Type? type) => type?.Assembly.GetName().Name?.StartsWith("Hangfire", StringComparison.Ordinal) == true;

    private static string FindApiDirectory()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "PersonalFinance.Api");
            if (File.Exists(Path.Combine(candidate, "PersonalFinance.Api.csproj"))) return candidate;
        }
        throw new DirectoryNotFoundException("PersonalFinance.Api source not found.");
    }
}
