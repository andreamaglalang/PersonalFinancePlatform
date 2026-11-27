using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Authentication.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RegisterRequest
{
    [Required(ErrorMessage = "Enter your full name."), StringLength(100, ErrorMessage = "Full name must be 100 characters or fewer."),
     SingleLineText(ErrorMessage = "Full name can't contain line breaks or control characters.")]
    public string FullName { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter your email address."), EmailAddress(ErrorMessage = "Enter a valid email address."), StringLength(256, ErrorMessage = "Email must be 256 characters or fewer."),
     RegularExpression(@"^\s*\S+\s*$", ErrorMessage = "Enter a valid email address.")]
    public string Email { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter a password."), PasswordRequirements]
    public string Password { get; set; } = string.Empty;
    [Required(ErrorMessage = "Confirm your password."), Compare(nameof(Password), ErrorMessage = "Passwords do not match.")]
    public string ConfirmPassword { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class LoginRequest
{
    [Required(ErrorMessage = "Enter your email address."), EmailAddress(ErrorMessage = "Enter a valid email address."), StringLength(256, ErrorMessage = "Email must be 256 characters or fewer.")]
    public string Email { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter your password."), StringLength(128, ErrorMessage = "Password must be 128 characters or fewer.")]
    public string Password { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class UpdateProfileRequest
{
    [Required(ErrorMessage = "Enter your full name."), StringLength(100, MinimumLength = 1, ErrorMessage = "Full name must be 100 characters or fewer."),
     SingleLineText(ErrorMessage = "Full name can't contain line breaks or control characters.")]
    public string FullName { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class ChangePasswordRequest
{
    [Required(ErrorMessage = "Enter your current password."), StringLength(128, ErrorMessage = "Password must be 128 characters or fewer.")]
    public string CurrentPassword { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter a new password."), PasswordRequirements]
    public string NewPassword { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RefreshRequest
{
    [Required(ErrorMessage = "A refresh token is required."), StringLength(86, MinimumLength = 86, ErrorMessage = "Invalid or expired refresh token."),
     RegularExpression("^[A-Za-z0-9_-]{86}$", ErrorMessage = "Invalid or expired refresh token.")]
    public string RefreshToken { get; set; } = string.Empty;
}

