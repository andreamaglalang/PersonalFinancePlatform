using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace PersonalFinance.AdminMvc.Controllers;

public sealed class EntryController : Controller
{
    [HttpGet, AllowAnonymous]
    public IActionResult Index()
    {
        var landing = OwliLanding.For(User);
        return landing.AreaName is null
            ? RedirectToAction(landing.ActionName, landing.ControllerName)
            : RedirectToAction(landing.ActionName, landing.ControllerName, new { area = landing.AreaName });
    }
}
