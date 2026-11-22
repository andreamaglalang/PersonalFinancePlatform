using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
public sealed class HomeController : Controller
{
    [HttpGet]
    public IActionResult Index() => View();
}
