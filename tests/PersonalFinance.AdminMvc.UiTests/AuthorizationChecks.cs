using System.Reflection;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Controllers;

internal static class AuthorizationChecks
{
    public static async Task Run()
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddOwliAuthorization();
        using var provider = services.BuildServiceProvider();
        var policies = provider.GetRequiredService<IAuthorizationPolicyProvider>();
        var authorization = provider.GetRequiredService<IAuthorizationService>();

        var anonymous = new ClaimsPrincipal();
        var member = new ClaimsPrincipal(new ClaimsIdentity(
            new[] { new Claim(ClaimTypes.Role, OwliRoles.User) }, AdminSessionStore.Scheme));
        var administrator = new ClaimsPrincipal(new ClaimsIdentity(
            new[] { new Claim(ClaimTypes.Role, OwliRoles.Administrator) }, AdminSessionStore.Scheme));

        if ((await authorization.AuthorizeAsync(anonymous, null, AuthorizationConfiguration.UserPolicy)).Succeeded)
            throw new InvalidOperationException("An anonymous visitor must not satisfy the User policy.");
        if ((await authorization.AuthorizeAsync(member, null, AuthorizationConfiguration.UserPolicy)).Succeeded is false)
            throw new InvalidOperationException("A member must satisfy the User policy.");
        if ((await authorization.AuthorizeAsync(member, null, AuthorizationConfiguration.AdministratorPolicy)).Succeeded)
            throw new InvalidOperationException("A member must not satisfy the Administrator policy.");
        if ((await authorization.AuthorizeAsync(administrator, null, AuthorizationConfiguration.AdministratorPolicy)).Succeeded is false)
            throw new InvalidOperationException("An administrator must satisfy the Administrator policy.");
        if ((await authorization.AuthorizeAsync(administrator, null, AuthorizationConfiguration.UserPolicy)).Succeeded is false)
            throw new InvalidOperationException("An administrator is also an authenticated member.");

        var fallback = await policies.GetFallbackPolicyAsync();
        if (fallback is null) throw new InvalidOperationException("Fallback policy is not registered.");
        if ((await authorization.AuthorizeAsync(anonymous, null, fallback!)).Succeeded)
            throw new InvalidOperationException("The fallback policy must deny anonymous visitors.");
        if ((await authorization.AuthorizeAsync(member, null, fallback!)).Succeeded is false)
            throw new InvalidOperationException("The fallback policy must allow a signed-in member.");

        foreach (var controller in new[]
        {
            typeof(DashboardController), typeof(CategoriesController),
            typeof(BudgetsController), typeof(ReportsController)
        })
        {
            var attribute = (AuthorizeAttribute?)Attribute.GetCustomAttribute(controller, typeof(AuthorizeAttribute), inherit: true);
            if (attribute?.Policy != AuthorizationConfiguration.AdministratorPolicy)
                throw new InvalidOperationException($"{controller.Name} must require the Administrator policy at class level.");
        }

        var accountIndex = typeof(AccountController).GetMethod("Index");
        if (accountIndex?.GetCustomAttribute<AuthorizeAttribute>(inherit: true)?.Policy
            != AuthorizationConfiguration.AdministratorPolicy)
            throw new InvalidOperationException("AccountController.Index must require the Administrator policy.");

        Console.WriteLine("PASS: policy evaluation denies anonymous, separates member from administrator, and keeps every admin controller protected.");
    }
}
