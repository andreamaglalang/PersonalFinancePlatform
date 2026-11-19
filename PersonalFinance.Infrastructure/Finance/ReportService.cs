using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance.Contracts
{
    public sealed class MonthlyReportQuery
    {
        /// <summary>First day of the reported month (same convention as budget PeriodMonth).</summary>
        public DateOnly? Month { get; set; }
    }

    public sealed record ReportCategoryTotal(int CategoryId, string CategoryName, decimal Total, int TransactionCount);

    /// <summary>All amounts are base-currency (AmountBase) totals, so mixed source currencies are never added up.</summary>
    public sealed record MonthlyReportResponse(DateOnly Month, string BaseCurrency, decimal TotalIncome, decimal TotalExpenses,
        decimal NetCashFlow, int TransactionCount, decimal SavingsContributed,
        IReadOnlyList<ReportCategoryTotal> ExpenseByCategory, IReadOnlyList<ReportCategoryTotal> IncomeByCategory,
        IReadOnlyList<BudgetResponse> Budgets);
}

namespace PersonalFinance.Infrastructure.Finance
{
    using PersonalFinance.Infrastructure.Finance.Contracts;

    public interface IReportService
    {
        Task<FinanceResult<MonthlyReportResponse>> MonthlyAsync(MonthlyReportQuery query, CancellationToken ct);
    }

    /// <summary>Monthly totals for one user. Shared with the monthly-summary job so both report identical figures.</summary>
    public static class MonthlyTotals
    {
        public sealed record Totals(decimal Income, decimal Expenses, decimal Savings, int Count);

        public static async Task<Totals> ComputeAsync(ApplicationDbContext db, int userId, DateOnly month, CancellationToken ct)
        {
            var end = month.AddMonths(1);
            var rows = await db.Transactions.AsNoTracking()
                .Where(t => t.UserId == userId && t.TransactionDate >= month && t.TransactionDate < end)
                .GroupBy(t => t.Type).Select(g => new { g.Key, Sum = g.Sum(t => t.AmountBase), Count = g.Count() }).ToListAsync(ct);
            // Contributions are recorded in the user's base currency.
            var savings = await db.GoalContributions.AsNoTracking()
                .Where(c => c.Goal.UserId == userId && c.ContributedOn >= month && c.ContributedOn < end)
                .SumAsync(c => (decimal?)c.Amount, ct) ?? 0m;
            decimal Sum(TransactionType type) => rows.Where(r => r.Key == type).Sum(r => r.Sum);
            return new(Sum(TransactionType.Income), Sum(TransactionType.Expense), savings, rows.Sum(r => r.Count));
        }
    }

    public sealed class ReportService(ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes, IBudgetUsageService budgets)
        : FinanceServiceBase(db, current, writes), IReportService
    {
        public Task<FinanceResult<MonthlyReportResponse>> MonthlyAsync(MonthlyReportQuery query, CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;
            if (query.Month is not { Day: 1 } month || month.Year is < 2000 or > 9998)
                throw new FinanceException(400, "Month must be the first day of a month.");
            var currency = await FinanceData.BaseCurrencyAsync(Db, userId, false, ct)
                ?? throw new FinanceException(401, "Account unavailable.");
            var end = month.AddMonths(1);
            var totals = await MonthlyTotals.ComputeAsync(Db, userId, month, ct);
            var byCategory = await Db.Transactions.AsNoTracking()
                .Where(t => t.UserId == userId && t.TransactionDate >= month && t.TransactionDate < end)
                .GroupBy(t => new { t.Type, t.CategoryId, t.Category.Name })
                .Select(g => new { g.Key.Type, g.Key.CategoryId, g.Key.Name, Total = g.Sum(t => t.AmountBase), Count = g.Count() })
                .ToListAsync(ct);
            IReadOnlyList<ReportCategoryTotal> Ranked(TransactionType type) => byCategory.Where(x => x.Type == type)
                .OrderByDescending(x => x.Total).ThenBy(x => x.Name).ThenBy(x => x.CategoryId)
                .Select(x => new ReportCategoryTotal(x.CategoryId, x.Name, x.Total, x.Count)).ToArray();
            var periods = await Db.Budgets.AsNoTracking().Where(b => b.UserId == userId && b.PeriodMonth == month)
                .Select(b => new BudgetPeriod(b.CategoryId, b.PeriodMonth)).ToArrayAsync(ct);
            var budgetRows = periods.Length == 0 ? [] : await budgets.ReadAsync(userId, periods, ct);
            return FinanceResult<MonthlyReportResponse>.Success(new(month, currency, totals.Income, totals.Expenses,
                totals.Income - totals.Expenses, totals.Count, totals.Savings, Ranked(TransactionType.Expense), Ranked(TransactionType.Income), budgetRows));
        });
    }
}
