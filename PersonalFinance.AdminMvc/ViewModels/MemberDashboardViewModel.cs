using System.Globalization;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed record MemberCategoryRow(int CategoryId, string CategoryName, decimal Total, int TransactionCount,
    decimal Share, string Colour);

public sealed record MemberRing(decimal Percent, string Dash, string Offset, string Label);

public sealed class MemberDashboardViewModel
{
    public bool IsAllTime { get; set; } = true;
    public string Month { get; set; } = "";
    public DateOnly MonthDate { get; set; }
    public string BaseCurrency { get; set; } = "";
    public decimal TotalIncome { get; set; }
    public decimal TotalExpenses { get; set; }
    public decimal NetCashFlow { get; set; }
    public int TransactionCount { get; set; }
    public decimal SavingsContributed { get; set; }
    public bool HasData { get; set; }
    public string? Error { get; set; }

    public List<MemberCategoryRow> Expenses { get; set; } = [];
    public List<MemberCategoryRow> Income { get; set; } = [];
    public List<TransactionResponse> Recent { get; set; } = [];
    public MemberRing IncomeRing { get; set; } = new(0, "", "", "0%");
    public MemberRing ExpenseRing { get; set; } = new(0, "", "", "0%");

    public string MonthLabel => MonthDate.ToString("MMMM yyyy", CultureInfo.InvariantCulture);
    public string ReportingLabel => IsAllTime ? "All time" : MonthLabel;
    public string EntriesLabel => IsAllTime ? "entries all time" : "entries this month";
    public string PeriodOverviewLabel => IsAllTime ? "Your all-time overview" : "Your monthly overview";
    public DateOnly PreviousMonth => MonthDate.AddMonths(-1);
    public DateOnly NextMonth => MonthDate.AddMonths(1);
    public bool IsCurrentMonth => MonthDate == new DateOnly(BudgetPlanSummary.PhilippineToday.Year, BudgetPlanSummary.PhilippineToday.Month, 1);
    public string TopExpenseCategory => Expenses.Count > 0 ? Expenses[0].CategoryName : "—";
    public decimal TopExpenseShare => Expenses.Count > 0 ? Expenses[0].Share : 0;
    public string SignedNet => (NetCashFlow >= 0 ? "+" : "−")
        + Math.Abs(NetCashFlow).ToString("0.00", CultureInfo.InvariantCulture);

    public void ApplyAllTime(IReadOnlyList<TransactionResponse> transactions, string baseCurrency,
        IReadOnlyDictionary<int, string> colours)
    {
        var ordered = transactions.OrderByDescending(row => row.TransactionDate)
            .ThenByDescending(row => row.TransactionId).ToArray();
        IsAllTime = true;
        BaseCurrency = baseCurrency;
        TransactionCount = ordered.Length;
        TotalIncome = ordered.Where(row => row.Type == "Income").Sum(row => row.AmountBase);
        TotalExpenses = ordered.Where(row => row.Type == "Expense").Sum(row => row.AmountBase);
        NetCashFlow = TotalIncome - TotalExpenses;
        HasData = ordered.Length > 0;
        Recent = ordered.Take(5).ToList();

        Expenses = CategoryRows(ordered.Where(row => row.Type == "Expense"), TotalExpenses, colours);
        Income = CategoryRows(ordered.Where(row => row.Type == "Income"), TotalIncome, colours);
        var flow = TotalIncome + TotalExpenses;
        IncomeRing = HomeDashboardRings.Create(flow > 0 ? TotalIncome / flow * 100 : 0);
        ExpenseRing = HomeDashboardRings.Create(flow > 0 ? TotalExpenses / flow * 100 : 0);
    }

    private static List<MemberCategoryRow> CategoryRows(IEnumerable<TransactionResponse> transactions,
        decimal total, IReadOnlyDictionary<int, string> colours) => transactions
        .GroupBy(row => new { row.CategoryId, row.CategoryName })
        .Select(group => new { group.Key.CategoryId, group.Key.CategoryName, Total = group.Sum(row => row.AmountBase), Count = group.Count() })
        .OrderByDescending(group => group.Total)
        .Select(group => new MemberCategoryRow(group.CategoryId, group.CategoryName, group.Total,
            group.Count, total > 0 ? group.Total / total * 100 : 0,
            colours.GetValueOrDefault(group.CategoryId, "#6747c7")))
        .ToList();
}

public static class HomeDashboardRings
{
    public static MemberRing Create(decimal share)
    {
        const double radius = 26;
        var circumference = 2 * Math.PI * radius;
        var clamped = Math.Min(Math.Max((double)share, 0), 100);
        var offset = circumference * (1 - clamped / 100);
        return new MemberRing(share, circumference.ToString("0.###", CultureInfo.InvariantCulture),
            offset.ToString("0.###", CultureInfo.InvariantCulture),
            Math.Round(share, 1, MidpointRounding.AwayFromZero).ToString("0.0", CultureInfo.InvariantCulture) + "%");
    }
}
