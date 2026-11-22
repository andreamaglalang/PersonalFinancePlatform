using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.AdminMvc.Services.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RegisterRequest
{
    [Required, StringLength(100)]
    public string FullName { get; set; } = string.Empty;
    [Required, EmailAddress, StringLength(256)]
    public string Email { get; set; } = string.Empty;
    [Required, StringLength(128, MinimumLength = 12)]
    public string Password { get; set; } = string.Empty;
    [Required, Compare(nameof(Password)), StringLength(128)]
    public string ConfirmPassword { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class LoginRequest
{
    [Required, EmailAddress, StringLength(256)]
    public string Email { get; set; } = string.Empty;
    [Required, StringLength(128)]
    public string Password { get; set; } = string.Empty;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RefreshRequest
{
    [Required, StringLength(86, MinimumLength = 86), RegularExpression("^[A-Za-z0-9_-]{86}$")]
    public string RefreshToken { get; set; } = string.Empty;
}

public sealed class AuthenticationResponse
{
    public required string AccessToken { get; init; }
    public required DateTime AccessTokenExpiresAtUtc { get; init; }
    public required string RefreshToken { get; init; }
    public required DateTime RefreshTokenExpiresAtUtc { get; init; }
    public required CurrentUserResponse User { get; init; }
}

public sealed record CurrentUserResponse(int Id, string FullName, string Email,
    string BaseCurrency, IReadOnlyList<string> Roles);

public static class ContractValidation
{
    public static bool User(CurrentUserResponse? user) => user is { Id: > 0 }
        && !string.IsNullOrWhiteSpace(user.FullName) && !string.IsNullOrWhiteSpace(user.Email)
        && user.BaseCurrency is { Length: 3 } && user.Roles is not null
        && user.Roles.All(role => !string.IsNullOrWhiteSpace(role));

    public static bool Tokens(AuthenticationResponse? value)
    {
        var now = DateTime.UtcNow;
        return value is not null && User(value.User)
            && !string.IsNullOrWhiteSpace(value.AccessToken) && value.AccessToken.Length <= 16384
            && !value.AccessToken.Any(char.IsWhiteSpace)
            && value.RefreshToken is { Length: 86 }
            && value.RefreshToken.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-')
            && value.AccessTokenExpiresAtUtc.Kind == DateTimeKind.Utc
            && value.RefreshTokenExpiresAtUtc.Kind == DateTimeKind.Utc
            && value.AccessTokenExpiresAtUtc > now && value.RefreshTokenExpiresAtUtc > now
            && value.AccessTokenExpiresAtUtc <= value.RefreshTokenExpiresAtUtc;
    }

    public static bool Administrator(CurrentUserResponse user) =>
        User(user) && user.Roles.Contains("Administrator", StringComparer.Ordinal);

    public static bool RegularUser(CurrentUserResponse user) =>
        User(user) && user.Roles.Contains("User", StringComparer.Ordinal)
        && !user.Roles.Contains("Administrator", StringComparer.Ordinal);
}
