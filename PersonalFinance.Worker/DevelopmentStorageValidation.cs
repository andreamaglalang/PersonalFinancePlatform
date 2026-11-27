using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using PersonalFinance.Infrastructure.Jobs;

namespace PersonalFinance.Worker;

/// <summary>Read-only checks for the explicit local test-worker launch profile. Never provisions storage.</summary>
public static class DevelopmentStorageValidation
{
    public static void ValidateConfiguration(IConfiguration configuration)
    {
        var finance = configuration.GetConnectionString("DefaultConnection") ?? string.Empty;
        var jobs = configuration.GetConnectionString("Hangfire") ?? string.Empty;
        HangfireSetup.EnsureDedicatedStorage(jobs, finance);
        var storage = new SqlConnectionStringBuilder(jobs);
        if (!storage.InitialCatalog.StartsWith(HangfireSetup.TestCatalogPrefix, StringComparison.Ordinal))
            throw new InvalidOperationException("The development test-worker profile refuses operational storage.");
        foreach (var value in new[] { finance, jobs })
        {
            var connection = new SqlConnectionStringBuilder(value);
            if (connection.TrustServerCertificate || connection.Encrypt == SqlConnectionEncryptOption.Optional)
                throw new InvalidOperationException("Development worker SQL connections require encryption and certificate validation.");
        }
    }

    public static async Task ValidateAsync(IConfiguration configuration)
    {
        ValidateConfiguration(configuration);
        var financeServer = await VerifyTargetAsync(configuration.GetConnectionString("DefaultConnection")!);
        var jobsServer = await VerifyTargetAsync(configuration.GetConnectionString("Hangfire")!);
        if (!string.Equals(financeServer, jobsServer, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("The connected test catalogs are on different SQL instances.");

        await using var storage = new SqlConnection(configuration.GetConnectionString("Hangfire"));
        await storage.OpenAsync();
        await using var command = storage.CreateCommand();
        command.CommandText = """
            SELECT (SELECT MAX([Version]) FROM [HangFire].[Schema]),
                   (SELECT COUNT(*) FROM sys.tables WHERE schema_id = SCHEMA_ID(N'HangFire')),
                   (SELECT COUNT(*) FROM [HangFire].[Server]);
            """;
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        if (reader.IsDBNull(0) || reader.GetInt32(0) != 9 || reader.GetInt32(1) != 11 || reader.GetInt32(2) != 0)
            throw new InvalidOperationException("Test Hangfire storage must have the prepared compatible schema and no registered worker.");
    }

    private static async Task<string> VerifyTargetAsync(string connectionString)
    {
        var expected = new SqlConnectionStringBuilder(connectionString).InitialCatalog;
        await using var connection = new SqlConnection(connectionString);
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT DB_NAME(), CONVERT(nvarchar(128), SERVERPROPERTY('ServerName'));";
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        if (!string.Equals(reader.GetString(0), expected, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("The connected catalog differs from the configured test target.");
        return reader.GetString(1);
    }
}
