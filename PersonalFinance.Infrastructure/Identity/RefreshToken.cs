namespace PersonalFinance.Infrastructure.Identity;

/// <summary>Persistence only. Hash values are uppercase SHA-256 hexadecimal (64 characters).
/// Revoked predecessors are retained for reuse detection; RefreshTokenService coordinates
/// atomic rotation and family-wide replay revocation through IAuthSessionStore.</summary>
public sealed class RefreshToken
{
    public int Id { get; set; }
    public int UserId { get; set; }
    public ApplicationUser User { get; set; } = null!;
    public string TokenHash { get; set; } = string.Empty;
    public Guid TokenFamilyId { get; set; }
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime ExpiresAtUtc { get; set; }
    public DateTime? RevokedAtUtc { get; set; }
    public string? ReplacedByTokenHash { get; set; }
}

