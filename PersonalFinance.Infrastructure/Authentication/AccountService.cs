using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

public interface IAccountService
{
    Task<AuthResult<AccountProfileResponse>> GetProfileAsync(int userId, CancellationToken ct);
    Task<AuthResult<AccountProfileResponse>> UpdateProfileAsync(int userId, AccountUpdateProfileRequest request, CancellationToken ct);
    Task<AuthResult<bool>> ChangePasswordAsync(int userId, AccountChangePasswordRequest request, CancellationToken ct);
}

/// <summary>Only the authenticated subject is accepted by the controller. Identity changes and revocation
/// share the existing per-user transaction lock; no nested transaction or schema change is introduced.</summary>
public sealed class AccountService(UserManager<ApplicationUser> users, IAuthSessionStore store, TimeProvider clock) : IAccountService
{
    public async Task<AuthResult<AccountProfileResponse>> GetProfileAsync(int userId, CancellationToken ct)
    {
        var user = await AvailableAsync(userId, ct);
        return user is null ? Missing<AccountProfileResponse>() : AuthResult<AccountProfileResponse>.Success(Describe(user));
    }

    public Task<AuthResult<AccountProfileResponse>> UpdateProfileAsync(int userId, AccountUpdateProfileRequest request, CancellationToken ct)
    {
        if (!AuthRules.IsValid(request) || string.IsNullOrWhiteSpace(request.FullName) || request.FullName.Any(char.IsControl))
            return Task.FromResult(AuthResult<AccountProfileResponse>.Failure(400, "Enter a name of 1–100 characters."));
        return store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            var user = await AvailableAsync(userId, token);
            if (user is null) return Missing<AccountProfileResponse>();
            if (!string.Equals(request.Version, Version(user), StringComparison.Ordinal))
                return AuthResult<AccountProfileResponse>.Failure(409, "Profile changed. Reload before saving.");
            user.FullName = request.FullName.Trim();
            var result = await users.UpdateAsync(user);
            if (!result.Succeeded) return AuthResult<AccountProfileResponse>.Failure(409, "Profile could not be saved. Reload and retry.");
            return AuthResult<AccountProfileResponse>.Success(Describe(user));
        }, ct);
    }

    public Task<AuthResult<bool>> ChangePasswordAsync(int userId, AccountChangePasswordRequest request, CancellationToken ct)
    {
        if (!AuthRules.IsValid(request) || request.CurrentPassword == request.NewPassword)
            return Task.FromResult(AuthResult<bool>.Failure(400, "Check the current password, new password and confirmation."));
        return store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            var user = await AvailableAsync(userId, token);
            if (user is null) return Missing<bool>();
            var result = await users.ChangePasswordAsync(user, request.CurrentPassword, request.NewPassword);
            if (!result.Succeeded)
            {
                if (result.Errors.Any(e => e.Code == "PasswordMismatch"))
                {
                    await users.AccessFailedAsync(user);
                    return AuthResult<bool>.Failure(400, "Current password is incorrect.");
                }
                return AuthResult<bool>.Failure(400, "Use 12–128 characters with uppercase, lowercase, a digit, a symbol and four distinct characters.");
            }
            // ChangePasswordAsync changes Identity's security stamp. JWT validation checks its digest.
            // Refresh revocation commits atomically with the password update under the same lock.
            await store.RevokeAllAsync(userId, clock.GetUtcNow().UtcDateTime, token);
            return AuthResult<bool>.Success(true, 204);
        }, ct);
    }

    private async Task<ApplicationUser?> AvailableAsync(int id, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        var user = await users.FindByIdAsync(id.ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (user is null) return null;
        await store.ReloadUserAsync(user, ct);
        return await users.IsLockedOutAsync(user) ? null : user;
    }
    private static string Version(ApplicationUser user) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
        (user.ConcurrencyStamp ?? "") + "\n" + user.FullName)));
    private static AccountProfileResponse Describe(ApplicationUser user) => new(user.Id, user.FullName, user.Email!, user.BaseCurrency, Version(user));
    private static AuthResult<T> Missing<T>() => AuthResult<T>.Failure(401, "Account unavailable. Sign in again.");
}
