using System.ComponentModel.DataAnnotations;
using Microsoft.IdentityModel.Tokens;

namespace PersonalFinance.Api.Authentication;

public sealed class JwtSettings
{
    public const string SectionName = "Jwt";
    [Required, StringLength(200)]
    public string Issuer { get; set; } = string.Empty;
    [Required, StringLength(200)]
    public string Audience { get; set; } = string.Empty;
    // Supply Jwt__SigningKeyBase64 through environment configuration. Never commit a key.
    public string SigningKeyBase64 { get; set; } = string.Empty;

    public static bool HasStrongKey(JwtSettings settings)
    {
        try { return Convert.FromBase64String(settings.SigningKeyBase64).Length >= 32; }
        catch (FormatException) { return false; }
    }

    public TokenValidationParameters ValidationParameters() => new()
    {
        ValidateIssuer = true, ValidIssuer = Issuer,
        ValidateAudience = true, ValidAudience = Audience,
        ValidateIssuerSigningKey = true,
        IssuerSigningKey = new SymmetricSecurityKey(Convert.FromBase64String(SigningKeyBase64)),
        RequireSignedTokens = true, RequireExpirationTime = true, ValidateLifetime = true,
        ClockSkew = TimeSpan.Zero,
        ValidAlgorithms = [SecurityAlgorithms.HmacSha256],
        NameClaimType = "sub", RoleClaimType = "role",
        ValidTypes = ["JWT"]
    };
}

