using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("PersonalFinance.FinanceTests")]

namespace PersonalFinance.Infrastructure.Finance;

internal sealed record BudgetSpendingTotal(int CategoryId, int Year, int Month, decimal Spent);

public sealed class BudgetUsageService(ApplicationDbContext db) : IBudgetUsageService
{
    public Task<IReadOnlyList<BudgetResponse>> ReadAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct) =>
        CalculateAsync(userId, periods, false, ct);

    // Caller owns the user/category locks and transaction. Never open a nested write scope.
    public Task<IReadOnlyList<BudgetResponse>> RecalculateAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct) =>
        CalculateAsync(userId, periods, true, ct);

    private async Task<IReadOnlyList<BudgetResponse>> CalculateAsync(
        int userId, IReadOnlyCollection<BudgetPeriod> periods, bool resetAlerts, CancellationToken ct)
    {
        if (periods.Count == 0) return [];
        var keys = periods.ToHashSet();
        var categories = keys.Select(x => x.CategoryId).Distinct().ToArray();
        var months = keys.Select(x => x.PeriodMonth).Distinct().ToArray();
        var query = db.Budgets.Where(b => b.UserId == userId && categories.Contains(b.CategoryId) && months.Contains(b.PeriodMonth));
        var budgets = await (resetAlerts ? query : query.AsNoTracking()).Include(b => b.Category).ToListAsync(ct);
        budgets = budgets.Where(b => keys.Contains(new(b.CategoryId, b.PeriodMonth))).ToList();
        if (budgets.Count == 0) return [];

        var first = budgets.Min(b => b.PeriodMonth);
        var lastMonth = budgets.Max(b => b.PeriodMonth);
        var last = new DateOnly(lastMonth.Year, lastMonth.Month, DateTime.DaysInMonth(lastMonth.Year, lastMonth.Month));
        var sums = await SpendingTotalsQuery(db, userId, categories, first, last).ToListAsync(ct);
        var spending = sums.ToDictionary(x => new BudgetPeriod(x.CategoryId, new(x.Year, x.Month, 1)), x => x.Spent);
        var currency = await FinanceData.BaseCurrencyAsync(db, userId, false, ct)
            ?? throw new FinanceException(401, "Account unavailable.");
        var result = new List<BudgetResponse>();
        var changed = false;
        foreach (var budget in budgets.OrderBy(b => b.PeriodMonth).ThenBy(b => b.CategoryId))
        {
            var spent = spending.GetValueOrDefault(new(budget.CategoryId, budget.PeriodMonth));
            var status = BudgetStatusCalculator.Calculate(spent, budget.LimitAmount, budget.AlertThresholdPct);
            if (resetAlerts && status == BudgetAlertStatus.OK && budget.LastAlertStatus is not null)
            {
                budget.LastAlertStatus = null;
                // LastAlertAtUtc is historical delivery evidence, not current status.
                changed = true;
            }
            result.Add(new(budget.BudgetId, budget.CategoryId, budget.Category.Name, budget.Category.IsActive,
                budget.PeriodMonth, budget.LimitAmount, budget.AlertThresholdPct, spent,
                budget.LimitAmount - spent, status.ToString(), currency));
        }
        if (changed) await db.SaveChangesAsync(ct);
        return result;
    }

    internal static IQueryable<BudgetSpendingTotal> SpendingTotalsQuery(
        ApplicationDbContext context, int userId, int[] categories, DateOnly first, DateOnly last) =>
        context.Transactions.AsNoTracking()
            .Where(t => t.UserId == userId && t.Type == TransactionType.Expense
                && categories.Contains(t.CategoryId) && t.TransactionDate >= first && t.TransactionDate <= last)
            .GroupBy(t => new { t.CategoryId, t.TransactionDate.Year, t.TransactionDate.Month })
            .Select(g => new BudgetSpendingTotal(g.Key.CategoryId, g.Key.Year, g.Key.Month, g.Sum(t => t.AmountBase)));
}
