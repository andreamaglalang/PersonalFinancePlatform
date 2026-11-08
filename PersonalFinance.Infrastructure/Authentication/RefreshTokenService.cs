using System.Security.Cryptography;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

public sealed class RefreshTokenService(
    UserManager<ApplicationUser> users,
    IAuthSessionStore store,
    IAccessTokenIssuer issuer,
    TimeProvider clock) : IRefreshTokenService
{
    public Task<AuthResult<AuthenticationResponse>> StartSessionAsync(int userId, CancellationToken ct) =>
        store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            var user = await LoadAvailableUserAsync(userId, token);
            if (user is null) return Invalid();
            return await IssueAsync(user, Guid.NewGuid(), null, token);
        }, ct);

    public async Task<AuthResult<AuthenticationResponse>> RotateAsync(RefreshRequest request, CancellationToken ct)
    {
        if (!AuthRules.IsValid(request)) return Invalid();
        var hash = RefreshTokenHash.Compute(request.RefreshToken);
        var candidate = await store.FindAsync(hash, ct);
        if (candidate is null) return Invalid();

        return await store.ExecuteAsync(AuthRules.UserLock(candidate.UserId), async token =>
        {
            // Re-read after acquiring the user lock: the initial lookup grants no authority.
            var previous = await store.FindAsync(hash, token);
            if (previous is null) return Invalid();
            var now = clock.GetUtcNow().UtcDateTime;
            if (previous.RevokedAtUtc is not null)
            {
                // Commit this revocation even though the request returns 401.
                await store.RevokeFamilyAsync(previous.UserId, previous.TokenFamilyId, now, token);
                return Invalid();
            }
            if (previous.ExpiresAtUtc <= now || await store.FamilyRevokedAsync(previous.UserId, previous.TokenFamilyId, token))
                return Invalid();

            var user = await LoadAvailableUserAsync(previous.UserId, token);
            if (user is null)
            {
                await store.RevokeFamilyAsync(previous.UserId, previous.TokenFamilyId, now, token);
                return Invalid();
            }
            return await IssueAsync(user, previous.TokenFamilyId, hash, token);
        }, ct);
    }

    public async Task LogoutAsync(RefreshRequest request, CancellationToken ct)
    {
        // Possession of a refresh token authorizes closing only its session family.
        if (!AuthRules.IsValid(request)) return;
        var candidate = await store.FindAsync(RefreshTokenHash.Compute(request.RefreshToken), ct);
        if (candidate is null) return;
        await store.ExecuteAsync(AuthRules.UserLock(candidate.UserId), async token =>
        {
            await store.RevokeFamilyAsync(candidate.UserId, candidate.TokenFamilyId, clock.GetUtcNow().UtcDateTime, token);
            return true;
        }, ct);
    }

    public async Task LogoutAllAsync(int userId, CancellationToken ct) =>
        await store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            await store.RevokeAllAsync(userId, clock.GetUtcNow().UtcDateTime, token);
            return true;
        }, ct);

    private async Task<ApplicationUser?> LoadAvailableUserAsync(int userId, CancellationToken ct)
    {
        var user = await users.FindByIdAsync(userId.ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (user is null) return null;
        await store.ReloadUserAsync(user, ct);
        // ApplicationUser has no inactive-account flag. Identity lockout is enforced.
        return await users.IsLockedOutAsync(user) ? null : user;
    }

    private async Task<AuthResult<AuthenticationResponse>> IssueAsync(ApplicationUser user, Guid familyId, string? previousHash, CancellationToken ct)
    {
        var now = clock.GetUtcNow().UtcDateTime;
        var raw = Convert.ToBase64String(RandomNumberGenerator.GetBytes(64)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var hash = RefreshTokenHash.Compute(raw);
        var roles = (await users.GetRolesAsync(user)).ToArray();
        var access = issuer.Issue(user, roles);
        if (previousHash is not null && !await store.ConsumeAsync(previousHash, hash, now, ct))
        {
            await store.RevokeFamilyAsync(user.Id, familyId, now, ct);
            return Invalid();
        }
        var expires = now.AddDays(7);
        await store.AddAsync(new RefreshToken
        {
            UserId = user.Id, TokenFamilyId = familyId, TokenHash = hash,
            CreatedAtUtc = now, ExpiresAtUtc = expires
        }, ct);
        return AuthResult<AuthenticationResponse>.Success(new AuthenticationResponse
        {
            AccessToken = access.Value, AccessTokenExpiresAtUtc = access.ExpiresAtUtc,
            RefreshToken = raw, RefreshTokenExpiresAtUtc = expires,
            User = new CurrentUserResponse(user.Id, user.FullName, user.Email!, user.BaseCurrency, roles)
        });
    }

    private static AuthResult<AuthenticationResponse> Invalid() =>
        AuthResult<AuthenticationResponse>.Failure(401, AuthRules.InvalidRefresh);
}

