using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.Authentication;

public sealed class AdminCookieEvents(AuthenticationCoordinator coordinator) : CookieAuthenticationEvents
{
    public const string FailureItem = "owli:auth-failure";

    public override async Task ValidatePrincipal(CookieValidatePrincipalContext context)
    {
        // Logout must reach the coordinator even during an outage, without refresh or /me first.
        var endpoint = context.HttpContext.GetEndpoint();
        if (endpoint?.Metadata.GetMetadata<LocalLogoutAttribute>() is not null) return;
        var failure = await coordinator.ValidateAsync(context.Principal?.FindFirstValue(AdminSessionStore.SessionClaim),
            context.HttpContext.RequestAborted);
        if (failure == ApiFailure.None) return;
        context.HttpContext.Items[FailureItem] = failure;
        context.RejectPrincipal();
        // The coordinator removes invalid sessions. A transient validation failure leaves the ticket available.
        if (failure is not (ApiFailure.RateLimited or ApiFailure.Unavailable))
            await context.HttpContext.SignOutAsync(AdminSessionStore.Scheme);
    }

    public override Task RedirectToLogin(RedirectContext<CookieAuthenticationOptions> context)
    {
        if (context.HttpContext.Items[FailureItem] is ApiFailure.RateLimited)
        {
            context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
            context.Response.Headers.RetryAfter = "60";
            return context.Response.WriteAsync("OWLI is receiving too many requests. Wait a minute, then navigate back or reload the page.");
        }
        if (context.HttpContext.Items[FailureItem] is ApiFailure.Unavailable)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return context.Response.WriteAsync("OWLI could not verify your session. Try opening the page again shortly.");
        }
        if (context.HttpContext.Items[FailureItem] is ApiFailure.Forbidden)
            context.Response.Redirect("/Account/AccessDenied");
        else if (context.HttpContext.Items[FailureItem] is ApiFailure failure
            && failure is not ApiFailure.Unauthorized)
            context.Response.Redirect("/Account/Login?unavailable=true");
        else context.Response.Redirect(context.RedirectUri);
        return Task.CompletedTask;
    }
}

[AttributeUsage(AttributeTargets.Method)]
public sealed class LocalLogoutAttribute : Attribute { }
