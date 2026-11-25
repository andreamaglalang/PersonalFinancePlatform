using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace PersonalFinance.AdminMvc.Services;

public sealed record ReportCategoryTotal(int CategoryId, string CategoryName, decimal Total, int TransactionCount);

public sealed record MonthlyReportResponse(DateOnly Month, string BaseCurrency, decimal TotalIncome, decimal TotalExpenses,
    decimal NetCashFlow, int TransactionCount, decimal SavingsContributed,
    IReadOnlyList<ReportCategoryTotal> ExpenseByCategory, IReadOnlyList<ReportCategoryTotal> IncomeByCategory,
    IReadOnlyList<BudgetResponse> Budgets);

public sealed record MonthlyReportResult(int Status, MonthlyReportResponse? Report = null);

public sealed class ReportsApiClient(HttpClient http)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public async Task<MonthlyReportResult> GetMonthlyAsync(DateOnly month, string bearer, CancellationToken ct)
    {
        var query = month.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, "api/reports/monthly?month=" + query);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 429) return new(status);
            if (status != 200 || response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);

            var report = await response.Content.ReadFromJsonAsync<MonthlyReportResponse>(Json, ct);
            return IsValid(report, month) ? new(200, report) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException or JsonException
            or NotSupportedException or InvalidOperationException or FormatException or OverflowException)
        {
            return new(503);
        }
    }

    private static bool IsValid(MonthlyReportResponse? report, DateOnly month) =>
        report is { BaseCurrency.Length: 3, TransactionCount: >= 0, ExpenseByCategory: not null,
            IncomeByCategory: not null, Budgets: not null }
        && report.TotalIncome >= 0 && report.TotalExpenses >= 0 && report.SavingsContributed >= 0
        && report.NetCashFlow == report.TotalIncome - report.TotalExpenses
        && report.Month == month
        && report.BaseCurrency.All(c => c is >= 'A' and <= 'Z')
        && report.ExpenseByCategory.All(IsValidCategory)
        && report.IncomeByCategory.All(IsValidCategory)
        && report.ExpenseByCategory.Sum(row => row.Total) == report.TotalExpenses
        && report.IncomeByCategory.Sum(row => row.Total) == report.TotalIncome
        && report.ExpenseByCategory.Sum(row => (long)row.TransactionCount) + report.IncomeByCategory.Sum(row => (long)row.TransactionCount) == report.TransactionCount
        && report.ExpenseByCategory.Select(row => row.CategoryId).Distinct().Count() == report.ExpenseByCategory.Count
        && report.IncomeByCategory.Select(row => row.CategoryId).Distinct().Count() == report.IncomeByCategory.Count
        && report.Budgets.All(budget => BudgetApiClient.Valid(budget) && budget.PeriodMonth == month
            && budget.BaseCurrency == report.BaseCurrency);

    private static bool IsValidCategory(ReportCategoryTotal? row) =>
        row is { CategoryId: > 0, TransactionCount: > 0, Total: >= 0 } && !string.IsNullOrWhiteSpace(row.CategoryName);
}
