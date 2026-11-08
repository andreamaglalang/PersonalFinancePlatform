using System.Security.Cryptography;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

/// <summary>Provides a real Identity hash verification for unknown-email/locked login attempts.</summary>
public sealed class DummyPasswordVerifier
{
    private readonly ApplicationUser user = new();
    private readonly PasswordHasher<ApplicationUser> hasher = new();
    private readonly string hash;

    public DummyPasswordVerifier() =>
        hash = hasher.HashPassword(user, Convert.ToBase64String(RandomNumberGenerator.GetBytes(64)));

    public void Verify(string password) => hasher.VerifyHashedPassword(user, hash, password);
}

