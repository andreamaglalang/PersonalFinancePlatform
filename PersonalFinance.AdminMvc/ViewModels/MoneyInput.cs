using System.ComponentModel.DataAnnotations;
using System.Globalization;
using Microsoft.AspNetCore.Mvc.ModelBinding;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>One specific message per amount rule, matching the API's AmountMessages wording.</summary>
public static class MoneyInput
{
    public const decimal Maximum = 9999999999999999.99m;
    public const string NotANumber = "Enter the amount as a number with a decimal point, for example 1250.50.";

    /// <summary>
    /// Reads a typed amount exactly: digits with an optional decimal point. Grouping separators, currency symbols,
    /// exponents and a leading "+" are refused, never removed or reinterpreted ("10,50" is not 1050).
    /// A leading "-" is read so the message can say the amount must be greater than zero.
    /// </summary>
    public static bool TryParse(string? text, out decimal amount)
    {
        amount = 0;
        var trimmed = text?.Trim();
        return !string.IsNullOrEmpty(trimmed) && trimmed[0] != '+'
            && decimal.TryParse(trimmed, NumberStyles.AllowDecimalPoint | NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out amount);
    }

    /// <summary>Why a typed amount (decimal point, no grouping) is not acceptable, or null when it is.</summary>
    public static string? Problem(string? text, decimal maximum = Maximum)
    {
        if (string.IsNullOrWhiteSpace(text)) return "Enter an amount.";
        return TryParse(text, out var amount) ? Problem(amount, maximum) : NotANumber;
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

/// <summary>
/// Binds a decimal amount with <see cref="MoneyInput.TryParse"/> instead of the framework's culture number parsing,
/// which reads "10,50" as 1050 and "1e5" as 100000. Empty input binds as null and is left to [Required].
/// </summary>
public sealed class MoneyModelBinder : IModelBinder
{
    public Task BindModelAsync(ModelBindingContext context)
    {
        var posted = context.ValueProvider.GetValue(context.ModelName);
        if (posted == ValueProviderResult.None) return Task.CompletedTask;
        context.ModelState.SetModelValue(context.ModelName, posted);
        var text = posted.FirstValue;
        if (string.IsNullOrWhiteSpace(text)) context.Result = ModelBindingResult.Success(null);
        else if (MoneyInput.TryParse(text, out var amount)) context.Result = ModelBindingResult.Success(amount);
        else
        {
            context.ModelState.TryAddModelError(context.ModelName, MoneyInput.NotANumber);
            context.Result = ModelBindingResult.Failed();
        }
        return Task.CompletedTask;
    }
}

/// <summary>Names are single-line labels: no line breaks, tabs or other control characters (mirrors the API rule).</summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class SingleLineTextAttribute : ValidationAttribute
{
    public SingleLineTextAttribute() : base("Use a single line without control characters.") { }
    public override bool IsValid(object? value) => value is not string text || !text.Trim().Any(char.IsControl);
}
