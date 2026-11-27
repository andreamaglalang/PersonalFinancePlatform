using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Authentication;

public sealed class AuthenticationService(
    UserManager<ApplicationUser> users,
    RoleManager<IdentityRole<int>> roles,
    IAuthSessionStore store,
    IRefreshTokenService sessions,
    DummyPasswordVerifier dummy, TimeProvider clock) : IAuthenticationService
{
    public async Task<AuthResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct)
    {
        if (AuthRules.Errors(request) is { } errors)
            return AuthResult<CurrentUserResponse>.Invalid(errors);

        var email = request.Email.Trim();
        var normalized = users.NormalizeEmail(email);
        var resource = "finance:auth:register:" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalized)));
        try
        {
            return await store.ExecuteAsync(resource, async token =>
            {
                if (await users.FindByEmailAsync(email) is not null)
                    return AuthResult<CurrentUserResponse>.Failure(409, "An account with this email already exists.");
                // Role definitions are idempotently supplied by the existing applied Identity migration.
                // Missing schema/roles must not cause automatic migrations, seeding, or administrator creation.
                if (!await roles.RoleExistsAsync(ApplicationRoles.User))
                    return AuthResult<CurrentUserResponse>.Failure(503, "Authentication is not configured.");

                var user = new ApplicationUser
                {
                    FullName = request.FullName.Trim(), Email = email, UserName = email,
                    LockoutEnabled = true
                };
                var created = await users.CreateAsync(user, request.Password);
                if (!created.Succeeded)
                    return created.Errors.Any(e => e.Code.StartsWith("Password", StringComparison.Ordinal))
                        ? AuthResult<CurrentUserResponse>.Invalid(nameof(RegisterRequest.Password),
                            PasswordPolicy.Describe(request.Password) ?? "This password does not meet the requirements.")
                        : AuthResult<CurrentUserResponse>.Failure(400, "Registration could not be completed.");
                var assigned = await users.AddToRoleAsync(user, ApplicationRoles.User);
                if (!assigned.Succeeded)
                    throw new InvalidOperationException("Role assignment failed; registration transaction must roll back.");
                return AuthResult<CurrentUserResponse>.Success(await DescribeAsync(user), 201);
            }, ct);
        }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 2601 or 2627 })
        {
            return AuthResult<CurrentUserResponse>.Failure(409, "An account with this email already exists.");
        }
    }

    public async Task<AuthResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct)
    {
        if (AuthRules.Errors(request) is { } errors)
            return AuthResult<AuthenticationResponse>.Invalid(errors);

        var user = await users.FindByEmailAsync(request.Email.Trim());
        if (user is null)
        {
            dummy.Verify(request.Password);
            return InvalidLogin();
        }
        var verified = await store.ExecuteAsync(AuthRules.UserLock(user.Id), async token =>
        {
            await store.ReloadUserAsync(user, token);
            if (await users.IsLockedOutAsync(user)) { dummy.Verify(request.Password); return false; }
            if (!await users.CheckPasswordAsync(user, request.Password))
            {
                await users.AccessFailedAsync(user);
                return false; // Commit the Identity failed-attempt/lockout update.
            }
            var reset = await users.ResetAccessFailedCountAsync(user);
            return reset.Succeeded;
        }, ct);
        if (!verified) return InvalidLogin();
        var session = await sessions.StartSessionAsync(user.Id, ct);
        return session.Succeeded ? session : InvalidLogin();
    }

    public async Task<CurrentUserResponse?> GetCurrentUserAsync(int userId, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        var user = await users.FindByIdAsync(userId.ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (user is null || await users.IsLockedOutAsync(user)) return null;
        return await DescribeAsync(user);
    }

    public async Task<AuthResult<CurrentUserResponse>> UpdateProfileAsync(int userId, UpdateProfileRequest request, CancellationToken ct)
    {
        if (!AuthRules.IsValid(request) || string.IsNullOrWhiteSpace(request.FullName))
            return AuthResult<CurrentUserResponse>.Invalid(nameof(request.FullName), "Enter your full name (up to 100 characters).");
        return await store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            var user = await users.FindByIdAsync(userId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if (user is null) return AuthResult<CurrentUserResponse>.Failure(401, "The account is unavailable.");
            await store.ReloadUserAsync(user, token);
            if (await users.IsLockedOutAsync(user)) return AuthResult<CurrentUserResponse>.Failure(401, "The account is unavailable.");
            user.FullName = request.FullName.Trim();
            var result = await users.UpdateAsync(user);
            if (!result.Succeeded) return AuthResult<CurrentUserResponse>.Failure(result.Errors.Any(e => e.Code == "ConcurrencyFailure") ? 409 : 400, "The profile could not be updated.");
            return AuthResult<CurrentUserResponse>.Success(await DescribeAsync(user));
        }, ct);
    }

    public async Task<AuthResult<bool>> ChangePasswordAsync(int userId, ChangePasswordRequest request, CancellationToken ct)
    {
        if (AuthRules.Errors(request) is { } errors) return AuthResult<bool>.Invalid(errors);
        if (request.CurrentPassword == request.NewPassword)
            return AuthResult<bool>.Invalid(nameof(ChangePasswordRequest.NewPassword), PasswordMessages.SameAsCurrent);
        // Password update, security-stamp rotation and refresh revocation commit together.
        return await store.ExecuteAsync(AuthRules.UserLock(userId), async token =>
        {
            var user = await users.FindByIdAsync(userId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if (user is null) return AuthResult<bool>.Failure(401, "The account is unavailable.");
            await store.ReloadUserAsync(user, token);
            if (await users.IsLockedOutAsync(user)) return AuthResult<bool>.Failure(401, "The account is unavailable.");
            var changed = await users.ChangePasswordAsync(user, request.CurrentPassword, request.NewPassword);
            if (!changed.Succeeded)
            {
                if (changed.Errors.Any(e => e.Code == "PasswordMismatch"))
                {
                    await users.AccessFailedAsync(user);
                    return AuthResult<bool>.Invalid(nameof(ChangePasswordRequest.CurrentPassword), PasswordMessages.CurrentIncorrect);
                }
                if (changed.Errors.Any(e => e.Code == "ConcurrencyFailure"))
                    return AuthResult<bool>.Failure(409, "Your account changed at the same time. Try again.");
                return AuthResult<bool>.Invalid(nameof(ChangePasswordRequest.NewPassword),
                    PasswordPolicy.Describe(request.NewPassword) ?? "This password does not meet the requirements.");
            }
            var reset = await users.ResetAccessFailedCountAsync(user);
            if (!reset.Succeeded) throw new InvalidOperationException("Password update could not reset lockout state.");
            await store.RevokeAllAsync(userId, clock.GetUtcNow().UtcDateTime, token);
            return AuthResult<bool>.Success(true, 204);
        }, ct);
    }

    private async Task<CurrentUserResponse> DescribeAsync(ApplicationUser user) =>
        new(user.Id, user.FullName, user.Email!, user.BaseCurrency, (await users.GetRolesAsync(user)).ToArray());

    private static AuthResult<AuthenticationResponse> InvalidLogin() =>
        AuthResult<AuthenticationResponse>.Failure(401, AuthRules.InvalidCredentials);
}

