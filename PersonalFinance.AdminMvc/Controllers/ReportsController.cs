using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public sealed class ReportsController(ReportsApiClient reports, AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] string? month, CancellationToken ct)
    {
        var selectedMonth = month ?? DateTime.Today.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        if (!DateOnly.TryParseExact(selectedMonth + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var requestedMonth) || requestedMonth.Year is < 2000 or > 9998)
        {
            Response.StatusCode = StatusCodes.Status400BadRequest;
            return View(new MonthlyReportsViewModel(null, "Enter a valid month in YYYY-MM format (2000–9998)."));
        }

        var result = await LoadAsync(requestedMonth, ct);
        if (result.Report is { } report) return View(new MonthlyReportsViewModel(selectedMonth, Report: report));
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();

        Response.StatusCode = result.Status == 404 ? 503 : result.Status;
        var message = result.Status switch
        {
            400 => "The report service rejected the selected month. Choose a different month.",
            404 => "Monthly reports are unavailable on the configured API.",
            429 => "Too many report requests. Wait before trying again.",
            _ => "Report data is temporarily unavailable. Try again later."
        };
        return View(new MonthlyReportsViewModel(selectedMonth, ReportError: message));
    }

    private async Task<MonthlyReportResult> LoadAsync(DateOnly month, CancellationToken ct)
    {
        var session = sessions.Find(User.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null) return new(401);

        await session.Gate.WaitAsync(ct);
        try
        {
            if (!sessions.IsCurrent(session)) return new(401);
            if (!User.IsInRole("Administrator") || !session.User.Roles.Contains("Administrator", StringComparer.Ordinal)
                || User.FindFirstValue(ClaimTypes.NameIdentifier) != session.User.Id.ToString(CultureInfo.InvariantCulture))
                return new(403);
            var result = session.Tokens.AccessTokenExpiresAtUtc > DateTime.UtcNow
                ? await reports.GetMonthlyAsync(month, session.Tokens.AccessToken, ct) : new(401);
            if (result.Status == 401) sessions.Invalidate(session);
            return result;
        }
        finally { session.Gate.Release(); }
    }
}
