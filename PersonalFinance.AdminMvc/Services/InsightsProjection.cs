using System.Globalization;
using System.Text.RegularExpressions;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>Display geometry only. Money always comes from complete API monthly reports.</summary>
public static partial class InsightsProjection
{
    public static readonly DateOnly EarliestMonth = new(2000, 6, 1); // API supports reports from January 2000.
    private static readonly string[] Palette = ["#6747C7", "#7E62D8", "#A55AA9", "#C9577A", "#CC5F49", "#B8691F", "#93791A", "#667F2A", "#2F8A5B", "#1F8A82", "#2F7FB0", "#4468C9", "#5F6F9A", "#8F6D5C", "#74508F"];
    [GeneratedRegex("^#[0-9a-fA-F]{6}$")] private static partial Regex HexPattern();
    public static string CategoryColor(int id, string? configured = null) => configured is not null && HexPattern().IsMatch(configured)
        ? configured : Palette[(id % Palette.Length + Palette.Length) % Palette.Length];
    public static string Number(decimal value) => value.ToString("0.######", CultureInfo.InvariantCulture);
    public static string Money(decimal value, string currency = "PHP") => (currency == "PHP" ? "₱" : currency + " ") + value.ToString("N2", CultureInfo.InvariantCulture);
    public static string SignedMoney(decimal value, string currency = "PHP") => (value > 0 ? "+" : value < 0 ? "−" : "") + Money(Math.Abs(value), currency);
    public static DateOnly CurrentMonth => new(BudgetPlanSummary.PhilippineToday.Year, BudgetPlanSummary.PhilippineToday.Month, 1);
    public static DateOnly ClampMonth(DateOnly month, DateOnly current) => month > current ? current : month < EarliestMonth ? EarliestMonth : month;
    public static DateOnly[] Months(DateOnly selected) => Enumerable.Range(0, 6).Select(i => selected.AddMonths(i - 5)).ToArray();
    public static decimal Share(decimal value, decimal total) => total > 0 ? value / total * 100m : 0m;
    public static decimal BarHeight(decimal value, decimal maximum, decimal pixels = 180m) => maximum > 0 ? Math.Clamp(value / maximum, 0m, 1m) * pixels : 0m;
    public static decimal Scale(IEnumerable<MemberInsightsViewModel.MonthPoint> points)
    {
        var largest = points.SelectMany(p => new[] { p.Income, p.Expenses }).DefaultIfEmpty(0m).Max();
        if (largest <= 0) return 1m;
        var rawStep = largest / 4m;
        var magnitude = (decimal)Math.Pow(10, Math.Floor(Math.Log10((double)rawStep)));
        var step = new[] { 1m, 2m, 2.5m, 5m, 10m }.First(candidate => candidate >= rawStep / magnitude);
        return step * magnitude * 4m;
    }
    public sealed record Slice(int CategoryId, string Name, decimal Amount, int Count, decimal Share, decimal Offset, string Color);
    public static IReadOnlyList<Slice> Slices(MonthlyReportResponse report, IReadOnlyDictionary<int, string>? colors = null)
    {
        if (report.TotalExpenses <= 0) return [];
        var offset = 0m;
        return report.ExpenseByCategory.OrderByDescending(c => c.Total).ThenBy(c => c.CategoryName, StringComparer.Ordinal).ThenBy(c => c.CategoryId)
            .Select(c => { var share = Share(c.Total, report.TotalExpenses); var slice = new Slice(c.CategoryId, c.CategoryName, c.Total, c.TransactionCount, share, offset, CategoryColor(c.CategoryId, colors?.GetValueOrDefault(c.CategoryId))); offset += share; return slice; }).ToArray();
    }
    public static string? SpendingComparison(DateOnly month, IReadOnlyList<MemberInsightsViewModel.MonthPoint> history)
    {
        var current = history.SingleOrDefault(p => p.Month == month);
        var previous = history.SingleOrDefault(p => p.Month == month.AddMonths(-1));
        if (current is null || previous is null) return null;
        var label = previous.Month.ToString("MMMM yyyy", CultureInfo.InvariantCulture);
        if (previous.Expenses <= 0) return current.Expenses == 0 ? "No spending recorded in either month." : $"No spending was recorded in {label}; a percentage comparison is unavailable.";
        var change = current.Expenses - previous.Expenses;
        if (change == 0) return $"Spending is unchanged compared with {label}.";
        var percent = decimal.Round(Math.Abs(change) / previous.Expenses * 100m, 1, MidpointRounding.AwayFromZero).ToString("0.0", CultureInfo.InvariantCulture);
        return $"Spending is {(change > 0 ? "up" : "down")} {percent}% compared with {label}.";
    }
}
