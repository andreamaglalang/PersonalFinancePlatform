using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Api.Authentication;

public sealed class JwtTokenIssuer(IOptions<JwtSettings> settings, TimeProvider clock) : IAccessTokenIssuer
{
    public AccessToken Issue(ApplicationUser user, IReadOnlyList<string> roles)
    {
        var options = settings.Value;
        var now = clock.GetUtcNow().UtcDateTime;
        var expires = now.AddMinutes(15);
        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id.ToString(CultureInfo.InvariantCulture)),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString("N")),
            new(JwtRegisteredClaimNames.Iat, new DateTimeOffset(now).ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture), ClaimValueTypes.Integer64)
        };
        claims.AddRange(roles.Select(role => new Claim("role", role)));
        var credentials = new SigningCredentials(
            new SymmetricSecurityKey(Convert.FromBase64String(options.SigningKeyBase64)), SecurityAlgorithms.HmacSha256);
        var jwt = new JwtSecurityToken(options.Issuer, options.Audience, claims, now, expires, credentials);
        return new AccessToken(new JwtSecurityTokenHandler().WriteToken(jwt), expires);
    }
}

