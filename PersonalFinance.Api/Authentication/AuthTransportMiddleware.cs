namespace PersonalFinance.Api.Authentication;

public sealed class AuthTransportMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context)
    {
        if (context.Request.Path.StartsWithSegments("/api/auth"))
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Pragma = "no-cache";
            // Reject rather than redirect credential submissions. Authentication uses JSON bodies/headers only.
            if (!context.Request.IsHttps)
            {
                await Results.Problem(statusCode: 400, title: "HTTPS is required.").ExecuteAsync(context);
                return;
            }
            if (context.Request.QueryString.HasValue)
            {
                await Results.Problem(statusCode: 400, title: "Authentication query strings are not accepted.").ExecuteAsync(context);
                return;
            }
        }
        await next(context);
    }
}

