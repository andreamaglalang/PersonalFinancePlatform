using Microsoft.AspNetCore.Identity;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Api.Authentication;

public static class AuthenticationConfiguration
{
    public const string AuthRateLimit = "authentication";
    public const string UserPolicy = "User";
    public const string AdministratorPolicy = "Administrator";

    public static IServiceCollection AddApiAuthentication(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddOptions<JwtSettings>().Bind(configuration.GetSection(JwtSettings.SectionName))
            .ValidateDataAnnotations()
            .Validate(JwtSettings.HasStrongKey, "Jwt:SigningKeyBase64 must be base64 for at least 32 cryptographically random bytes.")
            .ValidateOnStart();

        services.AddSingleton<TimeProvider>(TimeProvider.System);
        services.AddSingleton<IAccessTokenIssuer, JwtTokenIssuer>();
        services.AddHttpContextAccessor();
        services.AddScoped<ICurrentUser, CurrentUser>();
        services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer();
        services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
            .Configure<IOptions<JwtSettings>>((options, settings) =>
            {
                options.MapInboundClaims = false;
                options.RequireHttpsMetadata = true;
                options.SaveToken = false;
                options.IncludeErrorDetails = false;
                options.TokenValidationParameters = settings.Value.ValidationParameters();
                options.Events = new JwtBearerEvents
                {
                    OnTokenValidated = async context =>
                    {
                        if (CurrentUser.ReadUserId(context.Principal) is not int userId)
                        {
                            context.Fail("Invalid subject.");
                            return;
                        }
                        var users = context.HttpContext.RequestServices.GetRequiredService<UserManager<ApplicationUser>>();
                        var user = await users.FindByIdAsync(userId.ToString(System.Globalization.CultureInfo.InvariantCulture));
                        if (user is null || await users.IsLockedOutAsync(user))
                        {
                            context.Fail("Account unavailable.");
                            return;
                        }
                        var currentRoles = new HashSet<string>(await users.GetRolesAsync(user), StringComparer.Ordinal);
                        if (!currentRoles.SetEquals(context.Principal!.FindAll("role").Select(c => c.Value)))
                            context.Fail("Role membership changed.");
                    }
                };
            });

        services.AddAuthorizationBuilder()
            .AddPolicy(UserPolicy, policy => policy.RequireAuthenticatedUser().RequireRole(ApplicationRoles.User))
            .AddPolicy(AdministratorPolicy, policy => policy.RequireAuthenticatedUser().RequireRole(ApplicationRoles.Administrator));

        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            options.AddPolicy(AuthRateLimit, context => RateLimitPartition.GetFixedWindowLimiter(
                context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
                _ => new FixedWindowRateLimiterOptions
                {
                    PermitLimit = 20, Window = TimeSpan.FromMinutes(1), QueueLimit = 0,
                    AutoReplenishment = true
                }));
        });
        return services;
    }
}

