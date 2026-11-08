using Microsoft.AspNetCore.DataProtection;
using System.Text;
using System.Xml.Linq;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using PersonalFinance.AdminProvisioning;
using PersonalFinance.Infrastructure;

try
{
    var environment = Environment.GetEnvironmentVariable("DOTNET_ENVIRONMENT");
    var aspnetEnvironment = Environment.GetEnvironmentVariable("ASPNETCORE_ENVIRONMENT");
    ProvisioningSafety.RequireDevelopment(environment, aspnetEnvironment);
    if (args.Length != 0) throw new ProvisioningException("No command-line arguments are accepted. Run from the solution root.");
    if (Console.IsInputRedirected || Console.IsOutputRedirected)
        throw new ProvisioningException("An interactive console is required.");

    var apiDirectory = Path.GetFullPath(Path.Combine(Directory.GetCurrentDirectory(), "PersonalFinance.Api"));
    var projectPath = Path.Combine(apiDirectory, "PersonalFinance.Api.csproj");
    if (!File.Exists(projectPath)) throw new ProvisioningException("Run from the PersonalFinancePlatform solution root.");

    var configuration = new ConfigurationBuilder().SetBasePath(apiDirectory)
        .AddJsonFile("appsettings.json", optional: false)
        .AddJsonFile("appsettings.Development.json", optional: true);
    var secretsId = XDocument.Load(projectPath).Descendants("UserSecretsId").FirstOrDefault()?.Value;
    if (!string.IsNullOrWhiteSpace(secretsId)) configuration.AddUserSecrets(secretsId);
    var settings = configuration.AddEnvironmentVariables().Build();
    var connection = settings.GetConnectionString("DefaultConnection");
    if (string.IsNullOrWhiteSpace(connection))
        throw new ProvisioningException("Configure DefaultConnection through API JSON settings, API user secrets, or environment variables before provisioning.");
    ProvisioningSafety.RequireTarget(connection);

    var services = new ServiceCollection();
    services.AddLogging(logging => logging.ClearProviders()); // No credential/SQL logging.
    services.AddInfrastructure(settings);
    services.AddDataProtection().UseEphemeralDataProtectionProvider();
    services.AddScoped<IProvisioningDatabase, SqlProvisioningDatabase>();
    services.AddScoped<AdministratorProvisioner>();
    await using var provider = services.BuildServiceProvider();
    await using var scope = provider.CreateAsyncScope();

    // Read-only live verification occurs before asking permission to write.
    await scope.ServiceProvider.GetRequiredService<IProvisioningDatabase>().VerifyAsync(default);
    Console.WriteLine(@"Verified development target: (localdb)\MSSQLLocalDB / PersonalFinanceDb (Windows Authentication).");
    Console.Write("New account email: "); var email = Console.ReadLine() ?? "";
    Console.Write("Full name: "); var name = Console.ReadLine() ?? "";
    var password = ReadPassword("Password: ");
    var confirmationPassword = ReadPassword("Confirm password: ");
    Console.WriteLine("Existing accounts will never be promoted, reset, or changed.");
    Console.WriteLine("To create this separate account with User and Administrator roles, type:");
    Console.WriteLine(ProvisioningSafety.Confirmation);
    var confirmation = Console.ReadLine() ?? "";
    var id = await scope.ServiceProvider.GetRequiredService<AdministratorProvisioner>().CreateAsync(
        environment!, aspnetEnvironment, confirmation, email, name, password, confirmationPassword);
    Console.WriteLine($"Created development account ID {id}; roles: User, Administrator.");
    return 0;
}
catch (ProvisioningException ex) { Console.Error.WriteLine(ex.Message); return 1; }
catch { Console.Error.WriteLine("Provisioning failed. No success was confirmed. Check local database availability and configuration; no existing account is reset or promoted. No exception details are logged."); return 1; }

static string ReadPassword(string prompt)
{
    Console.Write(prompt);
    var value = new StringBuilder();
    while (true)
    {
        var key = Console.ReadKey(intercept: true);
        if (key.Key == ConsoleKey.Enter) { Console.WriteLine(); return value.ToString(); }
        if (key.Key == ConsoleKey.Escape) throw new ProvisioningException("Cancelled.");
        if (key.Key == ConsoleKey.Backspace)
        {
            if (value.Length > 0) { value.Length--; Console.Write("\b \b"); }
        }
        else if (!char.IsControl(key.KeyChar) && value.Length < 129) { value.Append(key.KeyChar); Console.Write("*"); }
    }
}
