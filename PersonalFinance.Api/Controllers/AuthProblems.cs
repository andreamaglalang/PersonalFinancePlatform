using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.Infrastructure.Authentication.Contracts;

namespace PersonalFinance.Api.Controllers;

internal static class AuthProblems
{
    /// <summary>Field errors become ValidationProblemDetails (the same shape as automatic model validation).</summary>
    public static IActionResult Failure<T>(this ControllerBase controller, AuthResult<T> result)
    {
        if (result.Errors is null) return controller.Problem(statusCode: result.StatusCode, title: result.Error);
        var state = new ModelStateDictionary();
        foreach (var (field, messages) in result.Errors)
            foreach (var message in messages) state.AddModelError(field, message);
        return controller.ValidationProblem(state);
    }
}
