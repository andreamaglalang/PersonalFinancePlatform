using System.Security.Claims;
using PersonalFinance.AdminMvc.Areas.Member;
using PersonalFinance.AdminMvc.Authentication;

namespace PersonalFinance.AdminMvc.Controllers;

/// <summary>
/// Where a visitor lands. Pure decision with no URL generation, so it can be unit-tested directly.
/// Anonymous visitors go to the login page, administrators to the admin dashboard, every other
/// signed-in member to the Member area.
/// </summary>
public readonly record struct OwliLanding(string ActionName, string ControllerName, string? AreaName)
{
    public static OwliLanding For(ClaimsPrincipal user) =>
        user.Identity?.IsAuthenticated != true ? new("Login", "Account", null)
        : user.IsInRole(OwliRoles.Administrator) ? new("Index", "Dashboard", null)
        : new("Index", "Home", MemberArea.Name);
}
