using System.Reflection;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Areas.Member;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Controllers;

internal static class OwliLandingChecks
{
    public static void Run()
    {
        var anonymous = OwliLanding.For(new ClaimsPrincipal());
        if (anonymous != new OwliLanding("Login", "Account", null))
            throw new InvalidOperationException("Anonymous visitors must be sent to Account/Login.");

        var member = OwliLanding.For(new ClaimsPrincipal(new ClaimsIdentity(
            new[] { new Claim(ClaimTypes.Role, OwliRoles.User) }, AdminSessionStore.Scheme)));
        if (member != new OwliLanding("Index", "Home", MemberArea.Name))
            throw new InvalidOperationException("Members must be sent to Member/Home.");

        var administrator = OwliLanding.For(new ClaimsPrincipal(new ClaimsIdentity(
            new[] { new Claim(ClaimTypes.Role, OwliRoles.Administrator) }, AdminSessionStore.Scheme)));
        if (administrator != new OwliLanding("Index", "Dashboard", null))
            throw new InvalidOperationException("Administrators must be sent to the admin dashboard.");

        if (typeof(EntryController).GetCustomAttribute<AreaAttribute>() is not null)
            throw new InvalidOperationException("EntryController must stay in the root namespace.");
        if (typeof(EntryController).GetMethod("Index")?.GetCustomAttribute<AllowAnonymousAttribute>() is null)
            throw new InvalidOperationException("Entry must be reachable without a session.");

        Console.WriteLine("PASS: anonymous, member and administrator landing decisions are correct.");
    }
}
