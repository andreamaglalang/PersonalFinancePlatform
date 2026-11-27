using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.Infrastructure.Authentication;

/// <summary>
/// The Identity password options (DependencyInjection) described rule by rule, so every client can say exactly which
/// requirements a password misses. Character classes are ASCII, exactly as Identity's PasswordValidator counts them:
/// any other character (including accented letters) counts as a special character.
/// </summary>
public static class PasswordPolicy
{
    public const int MinimumLength = 12, MaximumLength = 128, MinimumDistinct = 4;

    public static IReadOnlyList<string> Unmet(string? password)
    {
        password ??= string.Empty;
        var unmet = new List<string>();
        if (password.Length < MinimumLength) unmet.Add("at least 12 characters");
        if (password.Length > MaximumLength) unmet.Add("no more than 128 characters");
        if (!password.Any(c => c is >= 'A' and <= 'Z')) unmet.Add("an uppercase letter");
        if (!password.Any(c => c is >= 'a' and <= 'z')) unmet.Add("a lowercase letter");
        if (!password.Any(c => c is >= '0' and <= '9')) unmet.Add("a number");
        if (password.All(c => c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9'))
            unmet.Add("a special character, such as ! or #");
        if (password.Distinct().Count() < MinimumDistinct) unmet.Add("at least 4 different characters");
        return unmet;
    }

    /// <summary>One sentence naming every unmet requirement, or null when the password meets the policy.</summary>
    public static string? Describe(string? password)
    {
        var unmet = Unmet(password);
        if (unmet.Count == 0) return null;
        var list = unmet.Count == 1 ? unmet[0] : string.Join(", ", unmet.Take(unmet.Count - 1)) + " and " + unmet[^1];
        return "Password must contain " + list + ".";
    }
}

/// <summary>Validates a password against <see cref="PasswordPolicy"/> and reports every unmet requirement.</summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class PasswordRequirementsAttribute : ValidationAttribute
{
    protected override ValidationResult? IsValid(object? value, ValidationContext context) =>
        value is null || PasswordPolicy.Describe(value as string) is not { } message
            ? ValidationResult.Success
            : new ValidationResult(message, context.MemberName is { } member ? [member] : null);
}

public static class PasswordMessages
{
    public const string CurrentIncorrect = "Your current password is incorrect.";
    public const string SameAsCurrent = "Choose a new password that is different from your current password.";
}
