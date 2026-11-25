namespace PersonalFinance.AdminMvc.Services;

/// <summary>Android-compatible arithmetic over the complete owner-scoped monthly budget set.</summary>
public sealed record BudgetPlanSummary(decimal Allocated, decimal Spent, decimal Remaining,
    string Currency, int? DaysLeft, decimal? DailyAllowance)
{
    public decimal Utilization => Percentage(Spent, Allocated);
    public decimal Fill => Math.Clamp(Utilization, 0m, 100m);
    public static DateOnly PhilippineToday => DateOnly.FromDateTime(DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(8)).DateTime);
    public static BudgetPlanSummary? Calculate(IReadOnlyList<BudgetResponse> budgets, DateOnly month, DateOnly today)
    {
        if (budgets.Count == 0 || budgets.Select(b => b.BaseCurrency).Distinct().Count() != 1) return null;
        var allocated = budgets.Sum(b => b.LimitAmount);
        var spent = budgets.Sum(b => b.SpentAmount);
        var remaining = allocated - spent;
        int? days = month.Year == today.Year && month.Month == today.Month
            ? DateTime.DaysInMonth(month.Year, month.Month) - today.Day + 1 : null;
        return new(allocated, spent, remaining, budgets[0].BaseCurrency, days,
            days is > 0 ? decimal.Round(Math.Max(remaining, 0m) / days.Value, 2, MidpointRounding.AwayFromZero) : null);
    }
    public static decimal Percentage(decimal spent, decimal limit) => limit > 0 ? spent / limit * 100m : 0m;
}
