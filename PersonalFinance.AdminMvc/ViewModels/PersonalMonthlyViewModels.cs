using System.Globalization;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.ViewModels;

public static class PersonalMonth
{
    public const int FirstReportYear = 2000;
    public const int LastReportYear = 9998;
    public static DateOnly Current
    {
        get
        {
            var today = DateTime.Today;
            return new(today.Year, today.Month, 1);
        }
    }

    public static bool TryParse(string? value, out DateOnly month)
    {
        if (value?.Length == 7) value += "-01";
        return DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out month)
            && month.Day == 1;
    }

    public static bool IsReportMonth(DateOnly month) => month.Year is >= FirstReportYear and <= LastReportYear;
    public static string Format(DateOnly month) => month.ToString("yyyy-MM", CultureInfo.InvariantCulture);
}

public sealed record PersonalMonthlyView(DateOnly? Month, MonthlyReportResponse? Report,
    string? ReportError = null, IReadOnlyList<PersonalTransaction>? Transactions = null, string? TransactionError = null, PersonalOverview? Overview = null, bool AllTime = false)
{
    public DateOnly? Previous => Month is { } month && PersonalMonth.IsReportMonth(month.AddMonths(-1))
        ? month.AddMonths(-1) : null;
    public DateOnly? Next => Month is { } month && PersonalMonth.IsReportMonth(month.AddMonths(1))
        ? month.AddMonths(1) : null;
}

public sealed record PersonalBudgetList(string? Month, int? CategoryId, int Page, BudgetPage? Results,
    IReadOnlyList<CategoryResponse> Categories, string? Error = null, bool CategoriesAvailable = true, IReadOnlyList<BudgetResponse>? MonthlySummary = null);
public sealed record PersonalBudgetDetails(BudgetResponse? Budget, string? Error = null);
