using System.Globalization;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public sealed class ActivityController(AdminApiClient api) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var result = await api.GetActivityAsync(User, ct);
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();
        if (result.Data?.Events is null) Response.StatusCode = 503;
        return View(result.Data?.Events is null ? null : result.Data);
    }
}

[Authorize(Policy = "Administrator")]
public sealed class AuditLogsController(AdminApiClient api) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] string? search, [FromQuery] int page, CancellationToken ct)
    {
        search = search?.Trim() ?? string.Empty;
        if (!ModelState.IsValid || search.Length > 100 || page < 0)
        {
            Response.StatusCode = 400;
            return View(new AuditLogViewModel(search, null, "Enter up to 100 characters and a valid page number."));
        }

        var result = await api.GetAuditAsync(User, search, Math.Max(1, page), ct);
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();
        if (result.Data is { Items: not null } data) return View(new AuditLogViewModel(search, data));

        Response.StatusCode = result.Status is 400 or 429 ? result.Status : 503;
        return View(new AuditLogViewModel(search, null, "Audit logs are temporarily unavailable."));
    }
}

[Authorize(Policy = "Administrator")]
public sealed class SystemHealthController(AdminApiClient api) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var result = await api.GetHealthAsync(User, ct);
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();

        const string key = "LastHealthySystemCheck";
        if (result.Data is { DatabaseHealthy: true } healthy)
            TempData[key] = healthy.CheckedAtUtc.ToString("O", CultureInfo.InvariantCulture);
        DateTimeOffset? lastSuccess = DateTimeOffset.TryParseExact(TempData.Peek(key) as string, "O",
            CultureInfo.InvariantCulture, DateTimeStyles.None, out var timestamp) ? timestamp : null;

        if (result.Data is not { DatabaseHealthy: true }) Response.StatusCode = 503;
        return View(new SystemHealthViewModel(result.Data, lastSuccess));
    }
}

public sealed record AuditLogViewModel(string Search, AuditLogPage? Page, string? Error = null);
public sealed record SystemHealthViewModel(SystemHealth? Health, DateTimeOffset? LastSuccessfulRefresh);
