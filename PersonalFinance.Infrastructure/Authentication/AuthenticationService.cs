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
    DummyPasswordVerifier dummy) : IAuthenticationService
{
    public async Task<AuthResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct)
    {
        if (!AuthRules.IsValid(request))
            return AuthResult<CurrentUserResponse>.Failure(400, "Invalid registration data.");

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
                    return AuthResult<CurrentUserResponse>.Failure(400,
                        created.Errors.Any(e => e.Code.StartsWith("Password", StringComparison.Ordinal))
                            ? "Password must be 12–128 characters with uppercase, lowercase, a digit, a symbol, and four distinct characters."
                            : "Registration could not be completed.");
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
        if (!AuthRules.IsValid(request))
            return AuthResult<AuthenticationResponse>.Failure(400, "Invalid login data.");

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

    private async Task<CurrentUserResponse> DescribeAsync(ApplicationUser user) =>
        new(user.Id, user.FullName, user.Email!, user.BaseCurrency, (await users.GetRolesAsync(user)).ToArray());

    private static AuthResult<AuthenticationResponse> InvalidLogin() =>
        AuthResult<AuthenticationResponse>.Failure(401, AuthRules.InvalidCredentials);
}

