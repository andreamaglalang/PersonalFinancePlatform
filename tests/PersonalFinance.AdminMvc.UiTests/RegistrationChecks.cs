using System.Net;
using System.Net.Http.Json;
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

    /// <summary>H6: the API returns 201 Created for registration; MVC must treat it as success.</summary>
    public static async Task RunStatusContractAsync()
    {
        var user = new CurrentUserResponse(7, "New Member", "new@example.test", "PHP", ["User"]);
        var register = new RegisterRequest { FullName = "New Member", Email = "new@example.test", Password = "Synthetic!Pass123", ConfirmPassword = "Synthetic!Pass123" };
        async Task<ApiResult<CurrentUserResponse>> Register(HttpStatusCode status) =>
            await new AuthenticationApiClient(new HttpClient(new Reply(status, user)) { BaseAddress = new Uri("https://api.example.test/") })
                .RegisterAsync(register, default);

        var created = await Register(HttpStatusCode.Created);
        if (!created.Succeeded || created.Value?.Id != 7)
            throw new InvalidOperationException("Register must accept 201 Created with a valid user (H6).");
        var ok = await Register(HttpStatusCode.OK);
        if (!ok.Succeeded) throw new InvalidOperationException("Register must still accept 200 OK.");
        var conflict = await Register(HttpStatusCode.Conflict);
        if (conflict.Succeeded || conflict.Failure != ApiFailure.Conflict)
            throw new InvalidOperationException("Register 409 must map to Conflict.");

        // Other JSON operations stay strict: a 201 from login is not a valid contract response.
        var login = await new AuthenticationApiClient(new HttpClient(new Reply(HttpStatusCode.Created, user)) { BaseAddress = new Uri("https://api.example.test/") })
            .GetCurrentUserAsync("token", default);
        if (login.Succeeded) throw new InvalidOperationException("Non-create operations must not accept 201.");
        Console.WriteLine("PASS: registration accepts 201 Created; other operations stay strict.");
    }

    private sealed class Reply(HttpStatusCode status, object body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(new HttpResponseMessage(status) { Content = JsonContent.Create(body) });
    }
}
