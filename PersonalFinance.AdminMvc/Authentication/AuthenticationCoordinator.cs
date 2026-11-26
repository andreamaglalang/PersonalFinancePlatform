using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Authentication;

public sealed class AuthenticationCoordinator(AdminSessionStore store, IAuthenticationApiClient api)
{
    /// <summary>A successful API session check is reused this long, so page views don't each call GET api/auth/me.
    /// The API still authorizes every finance call itself (JWT + security stamp).</summary>
    public static readonly TimeSpan VerificationReuse = TimeSpan.FromSeconds(30);

    public async Task<ApiResult<AdminSession>> LoginAsync(LoginRequest request, CancellationToken ct)
    {
        var login = await api.LoginAsync(request, ct);
        if (!login.Succeeded) return ApiResult<AdminSession>.Fail(login.Failure);
        var tokens = login.Value!;
        var me = await api.GetCurrentUserAsync(tokens.AccessToken, ct);
        if (!me.Succeeded || me.Value!.Id != tokens.User.Id
            || !ContractValidation.Authenticated(tokens.User) || !ContractValidation.Authenticated(me.Value))
        {
            await RevokeAsync(tokens.RefreshToken);
            return ApiResult<AdminSession>.Fail(me.Succeeded ? ApiFailure.Forbidden : me.Failure);
        }
        var session = store.Create(tokens, me.Value);
        if (session is not null) return ApiResult<AdminSession>.Success(session);
        await RevokeAsync(tokens.RefreshToken);
        return ApiResult<AdminSession>.Fail(ApiFailure.Unavailable);
    }

    public async Task<ApiFailure> ValidateAsync(string? id, CancellationToken ct)
    {
        var session = store.Find(id);
        if (session is null) return ApiFailure.Unauthorized;
        await session.Gate.WaitAsync(ct);
        try
        {
            if (!store.IsCurrent(session)) return Fail(session, ApiFailure.Unauthorized);
            var now = DateTimeOffset.UtcNow;
            if (session.Tokens.AccessTokenExpiresAtUtc > DateTime.UtcNow.AddSeconds(60)
                && session.LastVerifiedAt is { } verified && now - verified < VerificationReuse)
            {
                session.LastActivity = now;
                return ApiFailure.None;
            }
            if (session.Tokens.AccessTokenExpiresAtUtc <= DateTime.UtcNow.AddSeconds(60))
            {
                // Held across the request AND atomic replacement. Never retry an ambiguous rotation.
                var oldRefresh = session.Tokens.RefreshToken;
                var refresh = await api.RefreshAsync(new RefreshRequest { RefreshToken = oldRefresh }, ct);
                // The limiter rejects this request before rotation. Other refresh failures may be ambiguous.
                if (refresh.Failure == ApiFailure.RateLimited) return ApiFailure.RateLimited;
                if (!refresh.Succeeded) return Fail(session, refresh.Failure);
                var replacement = refresh.Value!;
                if (!store.IsCurrent(session) || replacement.User.Id != session.User.Id
                    || replacement.RefreshToken == oldRefresh || !ContractValidation.Administrator(replacement.User))
                {
                    await RevokeAsync(replacement.RefreshToken);
                    return Fail(session, ApiFailure.Unauthorized);
                }
                session.Tokens = replacement;
            }
            var me = await api.GetCurrentUserAsync(session.Tokens.AccessToken, ct);
            // A failed read cannot authorize this request, but does not revoke the session.
            if (me.Failure is ApiFailure.RateLimited or ApiFailure.Unavailable) return me.Failure;
            if (!me.Succeeded) return Fail(session, me.Failure);
            if (!store.IsCurrent(session) || me.Value!.Id != session.User.Id
                || !ContractValidation.Authenticated(me.Value))
            {
                await RevokeAsync(session.Tokens.RefreshToken);
                return Fail(session, ApiFailure.Forbidden);
            }
            session.User = me.Value;
            session.LastActivity = DateTimeOffset.UtcNow;
            session.LastVerifiedAt = session.LastActivity;
            return ApiFailure.None;
        }
        catch
        {
            store.Invalidate(session);
            return ApiFailure.Unavailable;
        }
        finally { session.Gate.Release(); }
    }

    public async Task<bool> LogoutAsync(string? id)
    {
        var session = store.Find(id);
        if (session is null) return false;
        // Logout still cleans up when the browser disconnects. HttpClient supplies the network timeout.
        await session.Gate.WaitAsync();
        try
        {
            if (session.Invalidated) return false;
            return await RevokeAsync(session.Tokens.RefreshToken);
        }
        finally { store.Invalidate(session); session.Gate.Release(); }
    }

    public static ClaimsPrincipal Principal(AdminSession session)
    {
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier,
                session.User.Id.ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new(ClaimTypes.Name, session.User.FullName),
            new(AdminSessionStore.SessionClaim, session.Id)
        };
        claims.AddRange(session.User.Roles.Select(role => new Claim(ClaimTypes.Role, role)));
        return new ClaimsPrincipal(new ClaimsIdentity(claims, AdminSessionStore.Scheme));
    }

    public static AuthenticationProperties Properties(AdminSession session) => new()
    {
        IsPersistent = false, AllowRefresh = false, IssuedUtc = DateTimeOffset.UtcNow,
        ExpiresUtc = session.AbsoluteExpiry
    };

    private ApiFailure Fail(AdminSession session, ApiFailure failure)
    {
        store.Invalidate(session);
        return failure;
    }

    private async Task<bool> RevokeAsync(string refreshToken)
    {
        try { return (await api.LogoutAsync(new RefreshRequest { RefreshToken = refreshToken }, CancellationToken.None)).Succeeded; }
        catch { return false; }
    }
}
