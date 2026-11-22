using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.Authentication;

public static class PersonalSession
{
    public const string Scheme = "PersonalCookie";
    public const string Policy = "RegularUser";
    public static readonly object ProfileItem = new();

    public static ClaimsPrincipal Principal(AdminSession session) => new(new ClaimsIdentity(
        [
            new(ClaimTypes.NameIdentifier, session.User.Id.ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new(ClaimTypes.Name, session.User.FullName),
            new(ClaimTypes.Role, "User"),
            new(AdminSessionStore.SessionClaim, session.Id)
        ], Scheme));
}

public sealed class PersonalCookieEvents(
    [FromKeyedServices(PersonalSession.Scheme)] AuthenticationCoordinator coordinator) : CookieAuthenticationEvents
{
    private static readonly object FailureItem = new();

    public override async Task ValidatePrincipal(CookieValidatePrincipalContext context)
    {
        if ((await context.HttpContext.AuthenticateAsync(AdminSessionStore.Scheme)).Succeeded)
        {
            context.RejectPrincipal();
            context.HttpContext.Items[FailureItem] = ApiFailure.Forbidden;
            return;
        }
        if (context.HttpContext.GetEndpoint()?.Metadata.GetMetadata<PersonalLogoutAttribute>() is not null)
            return;

        var failure = await coordinator.ValidateAsync(
            context.Principal?.FindFirstValue(AdminSessionStore.SessionClaim),
            context.HttpContext.RequestAborted,
            profile => context.HttpContext.Items[PersonalSession.ProfileItem] = profile);
        if (failure == ApiFailure.None) return;

        context.RejectPrincipal();
        context.HttpContext.Items[FailureItem] = failure;
        if (failure is not (ApiFailure.RateLimited or ApiFailure.Unavailable))
            await context.HttpContext.SignOutAsync(PersonalSession.Scheme);
    }

    public override Task RedirectToLogin(RedirectContext<CookieAuthenticationOptions> context)
    {
        if (context.HttpContext.Items[FailureItem] is ApiFailure.Forbidden)
        {
            context.Response.Redirect("/Personal/AccessDenied");
            return Task.CompletedTask;
        }
        if (context.HttpContext.Items[FailureItem] is ApiFailure.RateLimited or ApiFailure.Unavailable)
        {
            var limited = Equals(context.HttpContext.Items[FailureItem], ApiFailure.RateLimited);
            context.Response.StatusCode = limited ? StatusCodes.Status429TooManyRequests : StatusCodes.Status503ServiceUnavailable;
            if (limited) context.Response.Headers.RetryAfter = "60";
            return context.Response.WriteAsync(limited ? "Too many requests. Wait a minute and try again."
                : "OWLI could not verify your session. Try again shortly.");
        }
        return base.RedirectToLogin(context);
    }
}

[AttributeUsage(AttributeTargets.Method)]
public sealed class PersonalLogoutAttribute : Attribute { }
