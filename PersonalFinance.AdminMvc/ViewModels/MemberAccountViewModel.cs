using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class MemberAccountViewModel
{
    public string DisplayName { get; init; } = "OWLI member";
    public string Initials => string.Concat(DisplayName.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).Take(2).Select(word => System.Globalization.StringInfo.GetNextTextElement(word))).ToUpperInvariant();
    public string Email { get; init; } = "Email unavailable";
    public string AccountCurrency { get; init; } = "PHP";
    public string? Notice { get; init; }
    public string? Error { get; init; }
    public MemberProfileInput Profile { get; init; } = new();
    public MemberPasswordInput Password { get; init; } = new();
}

public sealed class MemberProfileInput
{
    [Required(ErrorMessage = "Enter your full name."), StringLength(100, MinimumLength = 1, ErrorMessage = "Full name must be 100 characters or fewer.")]
    public string FullName { get; set; } = string.Empty;
}

public sealed class MemberPasswordInput
{
    [Required(ErrorMessage = "Enter your current password."), StringLength(128, ErrorMessage = "Password must be 128 characters or fewer.")]
    public string CurrentPassword { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter a new password."), PasswordRequirements]
    public string NewPassword { get; set; } = string.Empty;
    [Required(ErrorMessage = "Confirm your new password."), Compare(nameof(NewPassword), ErrorMessage = "Passwords do not match.")]
    public string ConfirmPassword { get; set; } = string.Empty;
}
