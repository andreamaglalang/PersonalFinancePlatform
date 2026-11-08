namespace PersonalFinance.Api.Authentication;

public sealed class FinanceTransportMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context)
    {
        if (context.Request.Path.StartsWithSegments("/api/transactions") || context.Request.Path.StartsWithSegments("/api/categories") || context.Request.Path.StartsWithSegments("/api/budgets") || context.Request.Path.StartsWithSegments("/api/savings-goals"))
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

