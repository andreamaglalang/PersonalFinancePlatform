using Microsoft.AspNetCore.Authorization;
using Microsoft.Extensions.DependencyInjection;

namespace PersonalFinance.AdminMvc.Authentication;

public static class OwliRoles
{
    public const string User = "User";
    public const string Administrator = "Administrator";
}

public static class AuthorizationConfiguration
{
    public const string UserPolicy = "User";
    public const string AdministratorPolicy = "Administrator";

    public static IServiceCollection AddOwliAuthorization(this IServiceCollection services)
    {
        var member = new AuthorizationPolicyBuilder(AdminSessionStore.Scheme)
            .RequireAuthenticatedUser().Build();
        var administrator = new AuthorizationPolicyBuilder(AdminSessionStore.Scheme)
            .RequireAuthenticatedUser().RequireRole(OwliRoles.Administrator).Build();
        return services.AddAuthorization(options =>
        {
            options.AddPolicy(UserPolicy, member);
            options.AddPolicy(AdministratorPolicy, administrator);
            // Fail closed: every endpoint needs a signed-in member unless it is marked [AllowAnonymous].
            options.FallbackPolicy = member;
        });
    }
}
