using System.ComponentModel.DataAnnotations;
using System.Globalization;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>One specific message per amount rule, matching the API's AmountMessages wording.</summary>
public static class MoneyInput
{
    public const decimal Maximum = 9999999999999999.99m;

    /// <summary>Why a typed amount (decimal point, no grouping) is not acceptable, or null when it is.</summary>
    public static string? Problem(string? text, decimal maximum = Maximum)
    {
        if (string.IsNullOrWhiteSpace(text)) return "Enter an amount.";
        if (!decimal.TryParse(text.Trim(), NumberStyles.AllowDecimalPoint | NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var amount))
            return "Enter the amount as a number with a decimal point, for example 1250.50.";
        return Problem(amount, maximum);
    }

    public static string? Problem(decimal amount, decimal maximum = Maximum)
    {
        if (amount <= 0) return "Enter an amount greater than zero.";
        if (((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2) return "Amounts can have no more than two decimal places.";
        if (amount > maximum) return maximum == Maximum ? "That amount is too large."
            : $"Enter an amount up to {maximum.ToString("#,0.##", CultureInfo.InvariantCulture)}.";
        return null;
    }
}

/// <summary>Validates a decimal amount with <see cref="MoneyInput"/>; null values are left to [Required].</summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class MoneyAmountAttribute(double maximum) : ValidationAttribute
{
    protected override ValidationResult? IsValid(object? value, ValidationContext context) =>
        value is decimal amount && MoneyInput.Problem(amount, (decimal)maximum) is { } message
            ? new ValidationResult(message, context.MemberName is { } member ? [member] : null)
            : ValidationResult.Success;
}

/// <summary>
/// Validates a typed amount string with <see cref="MoneyInput"/> as a property rule, so it is reported together with
/// every other field error (object-level IValidatableObject rules only run once all property rules pass).
/// Empty values are left to [Required].
/// </summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class MoneyTextAttribute : ValidationAttribute
{
    protected override ValidationResult? IsValid(object? value, ValidationContext context) =>
        value is string text && !string.IsNullOrWhiteSpace(text) && MoneyInput.Problem(text) is { } message
            ? new ValidationResult(message, context.MemberName is { } member ? [member] : null)
            : ValidationResult.Success;
}
