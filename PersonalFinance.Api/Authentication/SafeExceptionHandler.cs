using Microsoft.AspNetCore.Diagnostics;

namespace PersonalFinance.Api.Authentication;

public sealed class SafeExceptionHandler(ILogger<SafeExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext context, Exception exception, CancellationToken ct)
    {
        // Kestrel's own request errors (for example a body over the endpoint's size limit) are client errors.
        if (exception is BadHttpRequestException bad)
        {
            context.Response.StatusCode = bad.StatusCode;
            await Results.Problem(statusCode: bad.StatusCode, title: bad.StatusCode == StatusCodes.Status413PayloadTooLarge
                ? "The request is too large." : "The request could not be read.").ExecuteAsync(context);
            return true;
        }
        // Never log request bodies, credentials, tokens, SQL parameter values, or raw exception messages.
        logger.LogError("Request failed with error type {ErrorType}.", exception.GetType().Name);
        context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        await Results.Problem(statusCode: 500, title: "The request could not be completed.").ExecuteAsync(context);
        return true;
    }
}

