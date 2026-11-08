using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

public interface IAuthenticationService
{
    Task<AuthResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct);
    Task<AuthResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct);
    Task<CurrentUserResponse?> GetCurrentUserAsync(int userId, CancellationToken ct);
}

public interface IRefreshTokenService
{
    Task<AuthResult<AuthenticationResponse>> StartSessionAsync(int userId, CancellationToken ct);
    Task<AuthResult<AuthenticationResponse>> RotateAsync(RefreshRequest request, CancellationToken ct);
    Task LogoutAsync(RefreshRequest request, CancellationToken ct);
    Task LogoutAllAsync(int userId, CancellationToken ct);
}

public interface IAccessTokenIssuer
{
    AccessToken Issue(ApplicationUser user, IReadOnlyList<string> roles);
}

/// <summary>Each transaction callback runs with an exclusive, transaction-owned logical lock.
/// All session mutations for one user must use the same user resource.</summary>
public interface IAuthSessionStore
{
    Task<T> ExecuteAsync<T>(string resource, Func<CancellationToken, Task<T>> operation, CancellationToken ct);
    Task<RefreshToken?> FindAsync(string hash, CancellationToken ct);
    Task ReloadUserAsync(ApplicationUser user, CancellationToken ct);
    Task AddAsync(RefreshToken token, CancellationToken ct);
    Task<bool> ConsumeAsync(string hash, string replacementHash, DateTime now, CancellationToken ct);
    Task<bool> FamilyRevokedAsync(int userId, Guid familyId, CancellationToken ct);
    Task RevokeFamilyAsync(int userId, Guid familyId, DateTime now, CancellationToken ct);
    Task RevokeAllAsync(int userId, DateTime now, CancellationToken ct);
}

public interface ICurrentUser
{
    int? UserId { get; }
    bool Owns(int resourceOwnerId);
    bool IsAdministrator { get; }
}

