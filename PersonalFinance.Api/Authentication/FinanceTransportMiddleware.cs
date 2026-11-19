namespace PersonalFinance.Api.Authentication;

public sealed class FinanceTransportMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context)
    {
        // Development-only demonstration endpoints do not exist in any other environment: answer 404 before
        // authentication so production never reveals them. Fails closed if the environment cannot be resolved.
        if (context.Request.Path.StartsWithSegments("/api/dev")
            && context.RequestServices?.GetService<IHostEnvironment>()?.IsDevelopment() != true)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }
        if (context.Request.Path.StartsWithSegments("/api/transactions") || context.Request.Path.StartsWithSegments("/api/categories") || context.Request.Path.StartsWithSegments("/api/budgets") || context.Request.Path.StartsWithSegments("/api/savings-goals") || context.Request.Path.StartsWithSegments("/api/recurring-bills") || context.Request.Path.StartsWithSegments("/api/reports") || context.Request.Path.StartsWithSegments("/api/notifications") || context.Request.Path.StartsWithSegments("/api/dev"))
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Pragma = "no-cache";
            if (!context.Request.IsHttps)
            {
                await Results.Problem(statusCode: 400, title: "HTTPS is required.").ExecuteAsync(context);
                return;
            }
        }
        await next(context);
    }
}

