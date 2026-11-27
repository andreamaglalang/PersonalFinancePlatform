using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;

namespace PersonalFinance.AdminMvc.Authentication;

public static class AuthenticationConfiguration
{
    public static IServiceCollection AddOwliAuthentication(this IServiceCollection services)
    {
        services.AddSingleton<AdminSessionStore>();
        services.AddScoped<AuthenticationCoordinator>();
        services.AddScoped<AdminCookieEvents>();
        services.AddAuthentication(AdminSessionStore.Scheme)
            .AddCookie(AdminSessionStore.Scheme, options =>
            {
                options.Cookie.Name = AdminCookieEvents.SessionCookie;
                options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
                options.Cookie.HttpOnly = true;
                options.Cookie.SameSite = SameSiteMode.Lax;
                options.Cookie.Path = "/";
                options.LoginPath = "/Account/Login";
                options.AccessDeniedPath = "/Account/AccessDenied";
                options.SlidingExpiration = false;
                options.ExpireTimeSpan = TimeSpan.FromHours(8);
                options.EventsType = typeof(AdminCookieEvents);
            });
        services.AddOptions<CookieAuthenticationOptions>(AdminSessionStore.Scheme)
            .Configure<AdminSessionStore>((options, store) => options.SessionStore = store);
        return services;
    }
}
