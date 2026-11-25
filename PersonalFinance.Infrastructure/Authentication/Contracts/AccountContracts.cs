using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Authentication.Contracts;

public sealed record AccountProfileResponse(int Id, string FullName, string Email, string BaseCurrency, string Version);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class AccountUpdateProfileRequest
{
    [Required, StringLength(100)] public string FullName { get; set; } = string.Empty;
    [Required, RegularExpression("^[A-F0-9]{64}$")] public string Version { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class AccountChangePasswordRequest
{
    [Required, StringLength(128)] public string CurrentPassword { get; set; } = string.Empty;
    [Required, StringLength(128, MinimumLength = 12)] public string NewPassword { get; set; } = string.Empty;
    [Required, Compare(nameof(NewPassword))] public string ConfirmPassword { get; set; } = string.Empty;
}
