using System.Security.Cryptography;
using System.Text;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

/// <summary>JWT carries a one-way digest, never the raw Identity stamp. Password changes invalidate all
/// previously issued access tokens without adding columns or persisting access tokens.</summary>
public static class AccessTokenSecurityStamp
{
    public static string Digest(ApplicationUser user)
    {
        if (string.IsNullOrWhiteSpace(user.SecurityStamp)) throw new InvalidOperationException("Account security state unavailable.");
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(user.SecurityStamp)));
    }
    public static bool Matches(ApplicationUser user, string? claim)
    {
        if (string.IsNullOrWhiteSpace(user.SecurityStamp) || claim is null || claim.Length != 64) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Digest(user)), Encoding.ASCII.GetBytes(claim));
    }
}
