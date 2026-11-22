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

        var controller = new HomeController();
        if (controller.Index() is not ViewResult)
            throw new InvalidOperationException("Member home must render a view.");

        Console.WriteLine("PASS: Member area skeleton is authorized and renders.");
    }
}
