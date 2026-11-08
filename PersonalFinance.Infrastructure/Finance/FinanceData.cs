using System.Linq.Expressions;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Persistence;
using PersonalFinance.Infrastructure.Finance.Contracts;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Finance;

internal static class FinanceData
{
    internal static readonly Expression<Func<FinanceTransaction, TransactionResponse>> TransactionProjection = t =>
        new(t.TransactionId, t.ClientId, t.Type.ToString(), t.CategoryId, t.Category.Name,
            t.Amount, t.CurrencyCode, t.FxRate, t.FxRateDate, t.AmountBase,
            t.TransactionDate, t.Description, t.CreatedAtUtc, t.BillId, t.BillDueDate);

    internal static readonly Expression<Func<Category, CategoryResponse>> CategoryProjection = c =>
        new(c.CategoryId, c.Name, c.Type.ToString(), c.ColorHEX, c.IsActive, c.UserId == null);

    internal static IQueryable<Category> LockedCategory(ApplicationDbContext db, int id) =>
        db.Database.IsSqlServer()
            ? db.Categories.FromSqlInterpolated($"SELECT * FROM [Categories] WITH (UPDLOCK, HOLDLOCK) WHERE [CategoryId] = {id}")
            : db.Categories.Where(c => c.CategoryId == id);

    internal static Task<bool> HasReferencesAsync(ApplicationDbContext db, int categoryId, CancellationToken ct) =>
        HasReferencesCoreAsync(db, categoryId, ct);

    private static async Task<bool> HasReferencesCoreAsync(ApplicationDbContext db, int id, CancellationToken ct) =>
        await db.Transactions.AnyAsync(t => t.CategoryId == id, ct)
        || await db.Budgets.AnyAsync(b => b.CategoryId == id, ct)
        || await db.RecurringBills.AnyAsync(b => b.CategoryId == id, ct);

    internal static async Task<string?> BaseCurrencyAsync(ApplicationDbContext db, int userId, bool locked, CancellationToken ct)
    {
        var users = locked && db.Database.IsSqlServer()
            ? db.Users.FromSqlInterpolated($"SELECT * FROM [AspNetUsers] WITH (UPDLOCK, HOLDLOCK) WHERE [Id] = {userId}")
            : db.Users.Where(u => u.Id == userId);
        return await users.AsNoTracking().Select(u => u.BaseCurrency).SingleOrDefaultAsync(ct);
    }

    internal static bool SameState(FinanceTransaction a, FinanceTransaction b) =>
        a.ClientId == b.ClientId && a.CategoryId == b.CategoryId && a.Type == b.Type
        && a.Amount == b.Amount && a.CurrencyCode == b.CurrencyCode && a.TransactionDate == b.TransactionDate
        && a.FxRate == b.FxRate && a.FxRateDate == b.FxRateDate && a.AmountBase == b.AmountBase
        && a.Description == b.Description && a.BillId == b.BillId && a.BillDueDate == b.BillDueDate;
}

