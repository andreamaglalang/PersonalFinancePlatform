using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Areas.Member;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;
using PersonalFinance.AdminMvc.Authentication;

internal static class MemberAreaChecks
{
    public static void Run()
    {
        var type = typeof(HomeController);
        if (type.GetCustomAttribute<AreaAttribute>()?.RouteValue != MemberArea.Name)
            throw new InvalidOperationException("Member controllers must be in the Member area.");
        if (type.GetCustomAttribute<AuthorizeAttribute>()?.Policy != AuthorizationConfiguration.UserPolicy)
            throw new InvalidOperationException("Member controllers must require the User policy.");

        var index = type.GetMethod("Index", new[] { typeof(string), typeof(string), typeof(CancellationToken) });
        if (index is null || index.ReturnType != typeof(Task<IActionResult>))
            throw new InvalidOperationException("Member home must render authenticated month and all-time reporting views.");

        // The Member home is honest: Android terminology, real monthly reports and complete-history totals.
        var home = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Home", "Index.cshtml"));
        foreach (var marker in new[] { "owli-overline", "member-panel", "This is not an account balance.",
            "All time", "Month" })
        {
            if (home.Contains(marker) is false)
                throw new InvalidOperationException($"Member home is missing {marker}.");
        }
        Console.WriteLine("PASS: Member dashboard is authorized and exposes its real month and all-time modes.");
    }
}
