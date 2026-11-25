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
    [Required, StringLength(100, MinimumLength = 1)]
    public string FullName { get; set; } = string.Empty;
}

public sealed class MemberPasswordInput
{
    [Required, StringLength(128)]
    public string CurrentPassword { get; set; } = string.Empty;
    [Required, StringLength(128, MinimumLength = 12)]
    public string NewPassword { get; set; } = string.Empty;
    [Required, Compare(nameof(NewPassword))]
    public string ConfirmPassword { get; set; } = string.Empty;
}
