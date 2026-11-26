using Microsoft.Data.SqlClient;

namespace PersonalFinance.SqlIntegrationTests;

/// <summary>Creates and drops one uniquely named disposable catalog. It never opens any other
/// user catalog: the configured connection must target master, and the generated name is the only
/// database this harness creates, migrates or drops.</summary>
internal sealed class DisposableSqlTarget : IAsyncDisposable
{
    public const string Prefix = "PersonalFinanceSqlTests_";
    private readonly string server;
    public string Database { get; }
    public string ConnectionString { get; }

    private DisposableSqlTarget(string serverConnection, string database)
    {
        server = serverConnection;
        Database = database;
        ConnectionString = new SqlConnectionStringBuilder(serverConnection) { InitialCatalog = database }.ConnectionString;
    }

    /// <summary>Validates the server connection without connecting. Returns the reason it is rejected, or null.</summary>
    public static string? Reject(string? serverConnection)
    {
        if (string.IsNullOrWhiteSpace(serverConnection)) return "not configured";
        SqlConnectionStringBuilder builder;
        try { builder = new SqlConnectionStringBuilder(serverConnection); }
        catch (Exception ex) when (ex is ArgumentException or FormatException or KeyNotFoundException) { return "not a valid SQL connection string"; }
        if (!string.IsNullOrEmpty(builder.AttachDBFilename)) return "AttachDbFilename is prohibited";
        if (!string.IsNullOrEmpty(builder.InitialCatalog) && !builder.InitialCatalog.Equals("master", StringComparison.OrdinalIgnoreCase))
            return "the server connection must target master; the harness generates its own disposable catalog";
        return null;
    }

    public static async Task<DisposableSqlTarget> CreateAsync(string serverConnection)
    {
        var reason = Reject(serverConnection);
        if (reason is not null) throw new InvalidOperationException("SQL target rejected: " + reason + ".");
        var target = new DisposableSqlTarget(serverConnection, Prefix + Guid.NewGuid().ToString("N"));
        await target.ExecuteOnMasterAsync($"CREATE DATABASE [{target.Database}];");
        return target;
    }

    private async Task ExecuteOnMasterAsync(string sql)
    {
        await using var connection = new SqlConnection(new SqlConnectionStringBuilder(server) { InitialCatalog = "master" }.ConnectionString);
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync();
    }

    public async ValueTask DisposeAsync()
    {
        // The name is generated here and always carries the prefix; never drop anything else.
        if (!Database.StartsWith(Prefix, StringComparison.Ordinal)) return;
        SqlConnection.ClearAllPools();
        await ExecuteOnMasterAsync(
            $"IF DB_ID(N'{Database}') IS NOT NULL BEGIN ALTER DATABASE [{Database}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{Database}]; END");
    }
}
