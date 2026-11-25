using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class HomeController(ReportsApiClient reports, TransactionApiClient transactions,
    ICategoryApiClient categories, AdminSessionStore sessions) : Controller
{
    private const string FallbackColour = "#6747c7";

    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] string? month, [FromQuery] string? period, CancellationToken ct)
    {
        var monthDate = ParseMonth(month) ?? new DateOnly(DateTime.Today.Year, DateTime.Today.Month, 1);
        if (!string.IsNullOrWhiteSpace(period)
            && !string.Equals(period, "month", StringComparison.OrdinalIgnoreCase)
            && !string.Equals(period, "all-time", StringComparison.OrdinalIgnoreCase))
            return BadRequest();
        var isAllTime = string.Equals(period, "all-time", StringComparison.OrdinalIgnoreCase)
            || (string.IsNullOrWhiteSpace(period) && string.IsNullOrWhiteSpace(month));
        var model = new MemberDashboardViewModel
        {
            Month = monthDate.ToString("yyyy-MM", CultureInfo.InvariantCulture),
            MonthDate = monthDate,
            IsAllTime = isAllTime
        };

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        if (isAllTime)
        {
            var all = await transactions.ListAllAsync(token!, ct);
            if (all.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account");
            }
            if (!all.Succeeded || all.Value is null)
            {
                Response.StatusCode = all.Status;
                model.Error = MemberSessionGate.Message(all.Status);
                return View(model);
            }

            var colours = await LoadColoursAsync(token!, ct);
            model.ApplyAllTime(all.Value, MemberSessionGate.BaseCurrency(User, sessions), colours);
            return View(model);
        }

        var result = await reports.GetMonthlyAsync(monthDate, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 200 && result.Report is not null)
        {
            var report = result.Report;
            model.BaseCurrency = report.BaseCurrency;
            model.TotalIncome = report.TotalIncome;
            model.TotalExpenses = report.TotalExpenses;
            model.NetCashFlow = report.NetCashFlow;
            model.TransactionCount = report.TransactionCount;
            model.SavingsContributed = report.SavingsContributed;
            model.HasData = report.TransactionCount > 0 || report.TotalIncome != 0 || report.TotalExpenses != 0;

            var colours = await LoadColoursAsync(token!, ct);
            model.Expenses = Rows(report.ExpenseByCategory, report.TotalExpenses, colours);
            model.Income = Rows(report.IncomeByCategory, report.TotalIncome, colours);
            var flow = report.TotalIncome + report.TotalExpenses;
            model.IncomeRing = HomeDashboardRings.Create(flow > 0 ? report.TotalIncome / flow * 100 : 0);
            model.ExpenseRing = HomeDashboardRings.Create(flow > 0 ? report.TotalExpenses / flow * 100 : 0);

            var recent = await transactions.ListAsync(null, null, monthDate, MonthEnd(monthDate), 1, token!, ct);
            if (recent.Status == 200 && recent.Value is not null)
                model.Recent = recent.Value.Items.Take(5).ToList();
        }
        else
        {
            Response.StatusCode = result.Status;
            model.Error = MemberSessionGate.Message(result.Status);
        }
        return View(model);
    }

    private async Task<Dictionary<int, string>> LoadColoursAsync(string bearer, CancellationToken ct)
    {
        var page = await categories.ListAsync(null, 1, 100, bearer, ct);
        return page.Succeeded
            ? page.Value!.Items.Where(item => item.IsActive && item.ColorHEX is not null)
                .ToDictionary(item => item.CategoryId, item => item.ColorHEX!)
            : [];
    }

    private static List<MemberCategoryRow> Rows(IReadOnlyList<ReportCategoryTotal> rows, decimal total,
        Dictionary<int, string> colours)
    {
        var ordered = rows.OrderByDescending(row => row.Total).ToList();
        return ordered.Select(row => new MemberCategoryRow(row.CategoryId, row.CategoryName, row.Total,
            row.TransactionCount, total > 0 ? row.Total / total * 100 : 0,
            colours.GetValueOrDefault(row.CategoryId) ?? FallbackColour)).ToList();
    }

    internal static DateOnly? ParseMonth(string? month) =>
        DateOnly.TryParseExact(month + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var value) ? value : null;

    private static DateOnly MonthEnd(DateOnly month) =>
        new(month.Year, month.Month, DateTime.DaysInMonth(month.Year, month.Month));
}
