using System.Security.Cryptography;
using System.Text;

namespace PersonalFinance.Infrastructure.Identity;

public static class RefreshTokenHash
{
    /// <summary>Hashes a high-entropy token before persistence. Plaintext refresh tokens must never be stored.</summary>
    public static string Compute(string token)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(token);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
    }
}

