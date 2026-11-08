using System.Data;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class SqlFinancialWriteScope(ApplicationDbContext db) : IFinancialWriteScope
{
    public async Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
    {
        try
        {
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, ct);
            foreach (var resource in resources.Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal))
            {
                await db.Database.ExecuteSqlInterpolatedAsync($"""
                    DECLARE @result int;
                    EXEC @result = sys.sp_getapplock @Resource={resource},
                        @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=10000;
                    IF @result < 0 THROW 51011, 'Financial write could not acquire its transaction lock.', 1;
                    """, ct);
            }
            var result = await operation(ct);
            await transaction.CommitAsync(ct);
            return result;
        }
        catch (SqlException ex) when (ex.Number is 1205 or 1222 or 51011)
        { throw new FinanceException(503, "Financial records are busy. Retry the request."); }
    }
}
