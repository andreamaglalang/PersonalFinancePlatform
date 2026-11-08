using Microsoft.AspNetCore.Diagnostics;

namespace PersonalFinance.Api.Authentication;

public sealed class SafeExceptionHandler(ILogger<SafeExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext context, Exception exception, CancellationToken ct)
    {
        // Never log request bodies, credentials, tokens, SQL parameter values, or raw exception messages.
        logger.LogError("Request failed with error type {ErrorType}.", exception.GetType().Name);
        context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        await Results.Problem(statusCode: 500, title: "The request could not be completed.").ExecuteAsync(context);
        return true;
    }
}

