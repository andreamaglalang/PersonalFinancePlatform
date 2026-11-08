using System.Data;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.AdminProvisioning;

public sealed class SqlProvisioningDatabase(ApplicationDbContext db) : IProvisioningDatabase
{
    public async Task VerifyAsync(CancellationToken ct)
    {
        ProvisioningSafety.RequireTarget(db.Database.GetConnectionString() ?? "");
        await db.Database.OpenConnectionAsync(ct);
        try
        {
            await using var command = db.Database.GetDbConnection().CreateCommand();
            command.CommandText = "SELECT DB_NAME(), CONVERT(int, SERVERPROPERTY('IsLocalDB'));";
            await using var reader = await command.ExecuteReaderAsync(ct);
            if (!await reader.ReadAsync(ct) || reader.GetString(0) != "PersonalFinanceDb" ||
                reader.IsDBNull(1) || reader.GetInt32(1) != 1)
                throw new ProvisioningException("The connected database is not the expected LocalDB development database.");
        }
        finally { await db.Database.CloseConnectionAsync(); }
    }

    public async Task<T> ExecuteAsync<T>(Func<Task<T>> action, CancellationToken ct)
    {
        // All Identity EF stores share this scoped DbContext and transaction.
        await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        try
        {
            var result = await action();
            await transaction.CommitAsync(ct);
            return result;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None);
            db.ChangeTracker.Clear();
            throw;
        }
    }
}
