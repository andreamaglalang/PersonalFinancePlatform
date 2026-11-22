using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public sealed class DashboardController(AdminApiClient api) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var result = await api.GetStatisticsAsync(User, ct);
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();
        return View(new DashboardViewModel
        {
            AdministratorDisplayName = User.Identity?.Name ?? "OWLI administrator",
            Statistics = result.Data
        });
    }

    [HttpGet]
    public async Task<IActionResult> Statistics(CancellationToken ct)
    {
        var result = await api.GetStatisticsAsync(User, ct);
        if (result.Status == 401) await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
        return result.Data is { } data ? Json(data) : StatusCode(result.Status);
    }
}
