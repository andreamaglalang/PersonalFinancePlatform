using System.Net;
using System.Net.Http.Json;
using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Routing;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class PersonalAuthenticationChecks
{
    private static readonly CurrentUserResponse User = new(11, "Test User", "user@example.test", "PHP", ["User"]);
    private static readonly CurrentUserResponse Admin = new(12, "Test Admin", "admin@example.test", "PHP", ["Administrator"]);

    public static async Task RunAsync()
    {
        Check(typeof(PersonalController).GetCustomAttribute<AuthorizeAttribute>()?.Policy == PersonalSession.Policy);
        var writes = typeof(PersonalController).GetMethods().Where(m => m.IsDefined(typeof(HttpPostAttribute))).ToArray();
        var expectedWrites = new HashSet<string>(StringComparer.Ordinal)
        {
            nameof(PersonalController.Login), nameof(PersonalController.Register), nameof(PersonalController.Logout),
            nameof(PersonalController.Account), nameof(PersonalController.Currency), nameof(PersonalController.Password)
        };
        Check(expectedWrites.SetEquals(writes.Select(action => action.Name)));
        Check(writes.All(action => action.IsDefined(typeof(ValidateAntiForgeryTokenAttribute))));
        foreach (var name in new[] { nameof(PersonalController.Index), nameof(PersonalController.Account),
                     nameof(PersonalController.Logout), nameof(PersonalController.Currency), nameof(PersonalController.Password) })
        {
            var actions = typeof(PersonalController).GetMethods().Where(action => action.Name == name).ToArray();
            Check(actions.Length > 0 && actions.All(action => !action.IsDefined(typeof(AllowAnonymousAttribute))));
        }

        using var handler = new AuthHandler();
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://localhost") };
        var api = new AuthenticationApiClient(http);
        var registered = await api.RegisterAsync(new RegisterRequest
        {
            FullName = User.FullName, Email = User.Email, Password = "Test-only!12345", ConfirmPassword = "Test-only!12345"
        }, default);
        Check(registered.Succeeded && registered.Value?.Id == User.Id && handler.RegistrationChecked);
        handler.RegistrationStatus = HttpStatusCode.Conflict;
        Check((await api.RegisterAsync(new RegisterRequest(), default)).Failure == ApiFailure.Conflict);
        handler.RegistrationStatus = HttpStatusCode.OK;
        Check((await api.RegisterAsync(new RegisterRequest(), default)).Failure == ApiFailure.InvalidResponse);

        using var personalStore = new AdminSessionStore();
        using var adminStore = new AdminSessionStore();
        var personal = new AuthenticationCoordinator(personalStore, api, ContractValidation.RegularUser);
        var admin = new AuthenticationCoordinator(adminStore, api);
        var login = new LoginRequest();
        var personalLogin = await personal.LoginAsync(login, default);
        Check(personalLogin.Value is { } && !((await admin.LoginAsync(login, default)).Succeeded));
        var session = personalLogin.Value!;
        Check(adminStore.Find(session.Id) is null);
        Check(PersonalSession.Principal(session).Identity?.AuthenticationType == PersonalSession.Scheme);
        Check(PersonalSession.Principal(session).IsInRole("User") && !PersonalSession.Principal(session).IsInRole("Administrator"));

        // Force the normal refresh path to run, then confirm the backend profile is supplied under the session lock.
        CurrentUserResponse? validated = null;
        Check(await personal.ValidateAsync(session.Id, default, profile => validated = profile) == ApiFailure.None);
        Check(validated?.Id == User.Id && handler.RefreshCalls == 1);
        handler.CurrentUserStatus = HttpStatusCode.Unauthorized;
        Check(await personal.ValidateAsync(session.Id, default) == ApiFailure.Unauthorized && personalStore.Find(session.Id) is null);

        handler.CurrentUserStatus = HttpStatusCode.OK;
        handler.LoginUser = Admin;
        handler.ProfileUser = Admin;
        Check(!(await personal.LoginAsync(login, default)).Succeeded);
        var adminLogin = await admin.LoginAsync(login, default);
        Check(adminLogin.Succeeded && personalStore.Find(adminLogin.Value!.Id) is null);

        handler.LoginUser = User with { Roles = ["User", "Administrator"] };
        handler.ProfileUser = handler.LoginUser;
        Check(!(await personal.LoginAsync(login, default)).Succeeded);
        handler.LoginUser = User;
        handler.ProfileUser = User;
        var next = (await personal.LoginAsync(login, default)).Value!;
        handler.RefreshUser = User with { Roles = ["User", "Administrator"] };
        Check(await personal.ValidateAsync(next.Id, default) == ApiFailure.Unauthorized && personalStore.Find(next.Id) is null);

        handler.RefreshUser = User;
        var signedOut = (await personal.LoginAsync(login, default)).Value!;
        var revocations = handler.LogoutCalls;
        Check(await personal.LogoutAsync(signedOut.Id));
        Check(handler.LogoutCalls == revocations + 1 && personalStore.Find(signedOut.Id) is null);
        Console.WriteLine("PASS: registration contract, antiforgery, role-specific sessions, refresh role checks, locked-out session rejection, and logout.");
    }

    private static void Check(bool condition, [System.Runtime.CompilerServices.CallerArgumentExpression(nameof(condition))] string? description = null)
    {
        if (!condition) throw new InvalidOperationException(description);
    }

    private sealed class AuthHandler : HttpMessageHandler
    {
        public CurrentUserResponse LoginUser { get; set; } = User;
        public CurrentUserResponse ProfileUser { get; set; } = User;
        public CurrentUserResponse RefreshUser { get; set; } = User;
        public HttpStatusCode CurrentUserStatus { get; set; } = HttpStatusCode.OK;
        public HttpStatusCode RegistrationStatus { get; set; } = HttpStatusCode.Created;
        public int LogoutCalls { get; private set; }
        public int RefreshCalls { get; private set; }
        public bool RegistrationChecked { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            switch (request.RequestUri!.AbsolutePath)
            {
                case "/api/auth/register":
                    Check(request.Method == HttpMethod.Post && request.Headers.Authorization is null);
                    if (RegistrationStatus == HttpStatusCode.Created)
                    {
                        var payload = await request.Content!.ReadFromJsonAsync<RegisterRequest>(cancellationToken: ct);
                        Check(payload?.FullName == User.FullName && payload.Password == payload.ConfirmPassword);
                        RegistrationChecked = true;
                    }
                    return Json(RegistrationStatus, User);
                case "/api/auth/login":
                    return Json(HttpStatusCode.OK, Tokens(LoginUser, 'L', 30));
                case "/api/auth/refresh":
                    RefreshCalls++;
                    return Json(HttpStatusCode.OK, Tokens(RefreshUser, 'R', 900));
                case "/api/auth/me":
                    Check(request.Headers.Authorization?.Scheme == "Bearer");
                    return Json(CurrentUserStatus, ProfileUser);
                case "/api/auth/logout":
                    LogoutCalls++;
                    return new HttpResponseMessage(HttpStatusCode.NoContent);
                default:
                    throw new InvalidOperationException("Unexpected authentication route.");
            }
        }

        private static AuthenticationResponse Tokens(CurrentUserResponse user, char seed, int expiresInSeconds) => new()
        {
            User = user, AccessToken = "test-access-token",
            AccessTokenExpiresAtUtc = DateTime.UtcNow.AddSeconds(expiresInSeconds),
            RefreshToken = new string(seed, 86), RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddDays(1)
        };

        private static HttpResponseMessage Json<T>(HttpStatusCode status, T body) =>
            new(status) { Content = JsonContent.Create(body) };
    }
}
