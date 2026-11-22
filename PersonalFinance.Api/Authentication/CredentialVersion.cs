using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Api.Authentication;

internal static class CredentialVersion
{
    public const string Claim = "credential_version";
    public const string Provider = "OWLI";
    public const string Marker = "PasswordChanged";

    public static string Encode(ApplicationUser user) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(user.SecurityStamp ?? "")));

    public static async Task<bool> AllowsAsync(UserManager<ApplicationUser> users, ApplicationUser user, string? version) =>
        version is not null
            ? string.Equals(version, Encode(user), StringComparison.Ordinal)
            : !users.SupportsUserAuthenticationTokens || await users.GetAuthenticationTokenAsync(user, Provider, Marker) is null;
}
