using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member Insights: complete monthly reports and a six-month comparison. Same API contracts as the administrator
/// report surface, but authorized with the User policy and scoped to the caller's own session.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
public sealed class ReportsController(ReportsApiClient reports,
    AdminSessionStore sessions, ICategoryApiClient? categories = null) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] string? month, CancellationToken ct)
    {
        var selectedMonth = month ?? InsightsProjection.CurrentMonth.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        if (!DateOnly.TryParseExact(selectedMonth + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var requestedMonth) || requestedMonth.Year is < 2000 or > 9998)
        {
            Response.StatusCode = StatusCodes.Status400BadRequest;
            return View(new MemberInsightsViewModel(selectedMonth)
            {
                MonthError = "Enter a valid month in YYYY-MM format (2000–9998)."
            });
        }

        requestedMonth = InsightsProjection.ClampMonth(requestedMonth, InsightsProjection.CurrentMonth);
        selectedMonth = requestedMonth.ToString("yyyy-MM", CultureInfo.InvariantCulture);

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await reports.GetMonthlyAsync(requestedMonth, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }

        MemberInsightsViewModel model;
        if (result.Report is { } report)
        {
            model = new MemberInsightsViewModel(selectedMonth) { Report = report };
            if (!await LoadHistoryAsync(model, requestedMonth, report, token!, ct)) return RedirectToAction("Login", "Account", new { area = "" });
            if (!await LoadCategoryColorsAsync(model, token!, ct)) return RedirectToAction("Login", "Account", new { area = "" });
        }
        else
        {
            Response.StatusCode = result.Status is 404 or 503 ? 503 : result.Status;
            var message = result.Status switch
            {
                400 => "The report service rejected the selected month. Choose a different month.",
                404 or 503 => "Monthly reports are unavailable on the configured API.",
                429 => "Too many report requests. Wait before trying again.",
                _ => "Report data is temporarily unavailable. Try again later."
            };
            model = new MemberInsightsViewModel(selectedMonth) { ReportError = message };
        }
        return View(model);
    }

    /// <summary>Compatibility route: converter now lives under Account.</summary>
    [HttpPost, ValidateAntiForgeryToken]
    public IActionResult Quote([FromForm] MemberInsightsViewModel model) => RedirectToAction("Index", "CurrencyConverter",
        new { area = "Member", From = model.QuoteFrom, To = model.QuoteTo, Amount = model.QuoteAmount });

    private async Task<bool> LoadHistoryAsync(MemberInsightsViewModel model, DateOnly selectedMonth,
        MonthlyReportResponse current, string bearer, CancellationToken ct)
    {
        var months = Enumerable.Range(0, 6)
            .Select(offset => selectedMonth.AddMonths(offset - 5))
            .ToArray();
        var tasks = months.Select(month => month == selectedMonth
            ? Task.FromResult(new MonthlyReportResult(200, current))
            : reports.GetMonthlyAsync(month, bearer, ct));
        var results = await Task.WhenAll(tasks);
        if (results.Any(result => result.Status == 401))
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return false;
        }
        model.MonthlyHistory = results.Where(result => result.Report is not null && result.Report.BaseCurrency == current.BaseCurrency)
            .Select(result => result.Report!)
            .OrderBy(report => report.Month)
            .Select(report => new MemberInsightsViewModel.MonthPoint(report.Month, report.BaseCurrency,
                report.TotalIncome, report.TotalExpenses, report.NetCashFlow, report.TransactionCount))
            .ToArray();
        model.HistoryPartial = model.MonthlyHistory.Count != months.Length;
        return true;
    }
    private async Task<bool> LoadCategoryColorsAsync(MemberInsightsViewModel model, string bearer, CancellationToken ct)
    {
        if (categories is null) return true;
        var colors = new Dictionary<int, string>();
        const int size = 100;
        for (var page = 1; (long)(page - 1) * size <= int.MaxValue; page++)
        {
            var result = await categories.ListAsync("Expense", page, size, bearer, ct);
            if (result.Failure == PersonalFinance.AdminMvc.Services.Contracts.CategoryFailure.Unauthorized)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return false;
            }
            if (!result.Succeeded) return true; // Historical report rows remain useful with the Android palette.
            foreach (var category in result.Value!.Items)
                colors[category.CategoryId] = InsightsProjection.CategoryColor(category.CategoryId, category.ColorHEX);
            if ((long)page * size >= result.Value.TotalCount) { model.CategoryColors = colors; return true; }
        }
        return true;
    }
}
