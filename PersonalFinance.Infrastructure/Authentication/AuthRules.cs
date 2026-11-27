using System.ComponentModel.DataAnnotations;
using System.Globalization;

namespace PersonalFinance.Infrastructure.Authentication;

public static class AuthRules
{
    public const string InvalidCredentials = "Invalid email or password.";
    public const string InvalidRefresh = "Invalid or expired refresh token.";
    public static string UserLock(int id) => "finance:auth:user:" + id.ToString(CultureInfo.InvariantCulture);
    public static bool IsValid(object request) => Validator.TryValidateObject(request, new ValidationContext(request), [], true);

    /// <summary>Every DataAnnotations message per field (keyed by property name, like ASP.NET Core model validation), or null when valid.</summary>
    public static Dictionary<string, string[]>? Errors(object request)
    {
        var results = new List<ValidationResult>();
        if (Validator.TryValidateObject(request, new ValidationContext(request), results, true)) return null;
        return results
            .SelectMany(r => (r.MemberNames.Any() ? r.MemberNames : [""]).Select(m => (Field: m, r.ErrorMessage)))
            .GroupBy(e => e.Field, e => e.ErrorMessage ?? "This value is not valid.")
            .ToDictionary(g => g.Key, g => g.Distinct().ToArray());
    }
}

