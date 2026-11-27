using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Infrastructure;
using Microsoft.AspNetCore.Mvc.ModelBinding;

namespace PersonalFinance.Api.Authentication;

/// <summary>
/// Automatic 400 responses without serializer internals. System.Text.Json errors (keys starting with "$") name .NET
/// types, namespaces and byte positions, so each becomes one plain message on the JSON field it concerns, and the
/// redundant "the request field is required" entry it causes is dropped. Validation-attribute messages are kept.
/// </summary>
public static class SafeValidationResponses
{
    public const string UnreadableValue = "This value is not recognised or is not in the expected format.";
    public const string UnreadableBody = "The request body is not valid JSON for this operation.";

    public static IActionResult Create(ActionContext context)
    {
        var parameters = context.ActionDescriptor.Parameters.Select(p => p.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var json = context.ModelState.Any(e => e.Key.StartsWith('$') && e.Value.Errors.Count > 0);
        var clean = new ModelStateDictionary();
        foreach (var (key, entry) in context.ModelState)
        {
            if (entry.Errors.Count == 0) continue;
            if (key.StartsWith('$'))
            {
                var field = key.TrimStart('$', '.');
                clean.AddModelError(field.Length == 0 ? "body" : field, field.Length == 0 ? UnreadableBody : UnreadableValue);
            }
            else if (!(json && parameters.Contains(key)))
                foreach (var error in entry.Errors)
                    clean.AddModelError(key, string.IsNullOrEmpty(error.ErrorMessage) ? UnreadableValue : error.ErrorMessage);
        }
        if (clean.ErrorCount == 0) clean.AddModelError("body", UnreadableBody);
        var problem = context.HttpContext.RequestServices.GetRequiredService<ProblemDetailsFactory>()
            .CreateValidationProblemDetails(context.HttpContext, clean, StatusCodes.Status400BadRequest);
        return new BadRequestObjectResult(problem) { ContentTypes = { "application/problem+json" } };
    }
}
