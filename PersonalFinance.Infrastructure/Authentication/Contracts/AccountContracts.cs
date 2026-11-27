using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Authentication.Contracts;

public sealed record AccountProfileResponse(int Id, string FullName, string Email, string BaseCurrency, string Version);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class AccountUpdateProfileRequest
{
    [Required(ErrorMessage = "Enter your full name."), StringLength(100, ErrorMessage = "Full name must be 100 characters or fewer.")] public string FullName { get; set; } = string.Empty;
    [Required, RegularExpression("^[A-F0-9]{64}$")] public string Version { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class AccountChangePasswordRequest
{
    [Required(ErrorMessage = "Enter your current password."), StringLength(128, ErrorMessage = "Password must be 128 characters or fewer.")] public string CurrentPassword { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter a new password."), PasswordRequirements] public string NewPassword { get; set; } = string.Empty;
    [Required(ErrorMessage = "Confirm your new password."), Compare(nameof(NewPassword), ErrorMessage = "Passwords do not match.")] public string ConfirmPassword { get; set; } = string.Empty;
}
