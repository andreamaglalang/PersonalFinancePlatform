using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = AuthorizationConfiguration.AdministratorPolicy)]
public sealed class DashboardController : Controller
{
    [HttpGet]
    public IActionResult Index()
    {
        var displayName = User.Identity?.Name;
        return View(new DashboardViewModel
        {
            AdministratorDisplayName = string.IsNullOrWhiteSpace(displayName)
                ? "OWLI administrator" : displayName
        });
    }
}
