using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class AuthenticationPreservationChecks
{
    public static void Run()
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddScoped<IAuthenticationApiClient, AuthenticationApiClientFixture>();
        services.AddOwliAuthentication();
        using var provider = services.BuildServiceProvider();

        var options = provider.GetRequiredService<IOptionsMonitor<CookieAuthenticationOptions>>()
            .Get(AdminSessionStore.Scheme);
        if (options.Cookie.Name != "__Host-OWLI.Admin") throw new InvalidOperationException("Cookie name changed.");
        if (options.Cookie.SecurePolicy != CookieSecurePolicy.Always) throw new InvalidOperationException("Cookie must stay SecurePolicy.Always.");
        if (options.Cookie.HttpOnly is false) throw new InvalidOperationException("Cookie must stay HttpOnly.");
        if (options.Cookie.SameSite != SameSiteMode.Lax) throw new InvalidOperationException("SameSite changed.");
        if (options.Cookie.Path != "/") throw new InvalidOperationException("Cookie path changed.");
        if (options.LoginPath != "/Account/Login") throw new InvalidOperationException("LoginPath changed.");
        if (options.AccessDeniedPath != "/Account/AccessDenied") throw new InvalidOperationException("AccessDeniedPath changed.");
        if (options.SlidingExpiration) throw new InvalidOperationException("Sliding expiration must stay off.");
        if (options.ExpireTimeSpan != TimeSpan.FromHours(8)) throw new InvalidOperationException("ExpireTimeSpan changed.");
        if (options.SessionStore is not AdminSessionStore) throw new InvalidOperationException("Server-side ticket store was removed.");
        if (options.EventsType != typeof(AdminCookieEvents)) throw new InvalidOperationException("Cookie events changed.");
        if (provider.GetRequiredService<IOptions<AuthenticationOptions>>().Value.DefaultScheme != AdminSessionStore.Scheme)
            throw new InvalidOperationException("Default authentication scheme changed.");
        if (provider.GetRequiredService<AdminSessionStore>() is not AdminSessionStore)
            throw new InvalidOperationException("AdminSessionStore registration changed.");
        if (provider.GetRequiredService<AuthenticationCoordinator>() is not AuthenticationCoordinator)
            throw new InvalidOperationException("AuthenticationCoordinator registration changed.");

        Console.WriteLine("PASS: administrator cookie and session protections are unchanged.");
    }
}

internal sealed class AuthenticationApiClientFixture : IAuthenticationApiClient
{
    public Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string accessToken, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<CurrentUserResponse>> UpdateProfileAsync(UpdateProfileRequest request, string accessToken, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<bool>> ChangePasswordAsync(ChangePasswordRequest request, string accessToken, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct) => throw new NotSupportedException();
    public Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct) => throw new NotSupportedException();
}
