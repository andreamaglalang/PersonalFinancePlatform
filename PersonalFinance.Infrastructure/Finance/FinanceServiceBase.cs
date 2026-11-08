using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public abstract class FinanceServiceBase(ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes)
{
    protected ApplicationDbContext Db { get; } = db;
    protected ICurrentUser Current { get; } = current;
    protected IFinancialWriteScope Writes { get; } = writes;
    protected int UserId => Current.UserId ?? throw new FinanceException(401, "Authentication is required.");

    protected static async Task<FinanceResult<T>> Guard<T>(Func<Task<FinanceResult<T>>> operation)
    {
        try { return await operation(); }
        catch (FinanceException ex) { return FinanceResult<T>.Failure(ex.StatusCode, ex.Message); }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 547 })
        { return FinanceResult<T>.Failure(409, "The operation conflicts with existing related records."); }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 51001 })
        { return FinanceResult<T>.Failure(409, "Bill payments require a controlled reversal and cannot be deleted."); }
    }
}

