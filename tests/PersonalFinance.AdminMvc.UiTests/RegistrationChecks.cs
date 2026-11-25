using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

internal static class RegistrationChecks
{
    public static void Run()
    {
        var type = typeof(AccountController);
        var get = type.GetMethod("Register", Type.EmptyTypes);
        var post = type.GetMethod("Register", new[] { typeof(RegisterViewModel), typeof(CancellationToken) });
        if (get?.GetCustomAttribute<AllowAnonymousAttribute>() is null)
            throw new InvalidOperationException("GET Register must be anonymous.");
        if (post?.GetCustomAttribute<ValidateAntiForgeryTokenAttribute>() is null
            || post.GetCustomAttribute<HttpPostAttribute>() is null)
            throw new InvalidOperationException("POST Register must be an antiforgery-protected POST.");
        if (post.GetCustomAttribute<AuthorizeAttribute>() is not null)
            throw new InvalidOperationException("Register must not require a role.");

        // Registration must never be able to request a privileged account.
        var requestProperties = typeof(RegisterRequest).GetProperties().Select(p => p.Name).ToArray();
        if (requestProperties.Any(name => name.Contains("role", StringComparison.OrdinalIgnoreCase)
            || name.Contains("administrator", StringComparison.OrdinalIgnoreCase)
            || name.Contains("system", StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException("The registration contract must not carry a role or privilege field.");

        var viewModelProperties = typeof(RegisterViewModel).GetProperties().Select(p => p.Name).ToArray();
        if (viewModelProperties.Length != 4
            || new[] { "FullName", "Email", "Password", "ConfirmPassword" }.Except(viewModelProperties).Any())
            throw new InvalidOperationException("RegisterViewModel must expose only name, email and passwords.");

        if (typeof(IAuthenticationApiClient).GetMethod("RegisterAsync") is null)
            throw new InvalidOperationException("IAuthenticationApiClient.RegisterAsync is missing.");

        // No auto sign-in: SignInAsync may appear exactly once in the controller (inside Login).
        var controller = File.ReadAllText(MvcProject.FindFile("Controllers", "AccountController.cs"));
        var signIns = controller.Split("SignInAsync", StringSplitOptions.None).Length - 1;
        if (signIns != 1)
            throw new InvalidOperationException($"SignInAsync must appear exactly once (Login); found {signIns}.");

        Console.WriteLine("PASS: registration metadata, contracts and no-auto-sign-in are correct.");
    }
}
