using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>
/// Mirrors the API's PasswordPolicy (Infrastructure) rule for rule and message for message, so the site can say
/// exactly which requirements a password misses before it is sent. The API stays authoritative; a UI test keeps
/// both copies identical. Character classes are ASCII, as ASP.NET Core Identity counts them.
/// </summary>
public static class PasswordPolicy
{
    /// <summary>Checklist rows: a key for the browser script, the visible label, and the phrase used in messages.</summary>
    public static readonly IReadOnlyList<(string Key, string Label)> Requirements =
    [
        ("length", "12 to 128 characters"),
        ("upper", "An uppercase letter (A–Z)"),
        ("lower", "A lowercase letter (a–z)"),
        ("digit", "A number (0–9)"),
        ("symbol", "A special character, such as ! or #"),
        ("distinct", "At least 4 different characters"),
    ];

    public static IReadOnlyList<string> Unmet(string? password)
    {
        password ??= string.Empty;
        var unmet = new List<string>();
        if (password.Length < 12) unmet.Add("at least 12 characters");
        if (password.Length > 128) unmet.Add("no more than 128 characters");
        if (!password.Any(c => c is >= 'A' and <= 'Z')) unmet.Add("an uppercase letter");
        if (!password.Any(c => c is >= 'a' and <= 'z')) unmet.Add("a lowercase letter");
        if (!password.Any(c => c is >= '0' and <= '9')) unmet.Add("a number");
        if (password.All(c => c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9'))
            unmet.Add("a special character, such as ! or #");
        if (password.Distinct().Count() < 4) unmet.Add("at least 4 different characters");
        return unmet;
    }

    public static string? Describe(string? password)
    {
        var unmet = Unmet(password);
        if (unmet.Count == 0) return null;
        var list = unmet.Count == 1 ? unmet[0] : string.Join(", ", unmet.Take(unmet.Count - 1)) + " and " + unmet[^1];
        return "Password must contain " + list + ".";
    }
}

[AttributeUsage(AttributeTargets.Property)]
public sealed class PasswordRequirementsAttribute : ValidationAttribute
{
    protected override ValidationResult? IsValid(object? value, ValidationContext context) =>
        value is null || PasswordPolicy.Describe(value as string) is not { } message
            ? ValidationResult.Success
            : new ValidationResult(message, context.MemberName is { } member ? [member] : null);
}
