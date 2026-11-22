using System.Security.Claims;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class RoleClaimsChecks
{
    public static void Run()
    {
        var memberUser = new CurrentUserResponse(7, "Member One", "member@example.test", "PHP",
            new[] { "User" });
        var session = new AdminSession
        {
            Id = "fixture-session",
            Tokens = new AuthenticationResponse
            {
                AccessToken = "fixture-access",
                AccessTokenExpiresAtUtc = DateTime.UtcNow.AddMinutes(5),
                RefreshToken = new string('x', 86),
                RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddHours(1),
                User = memberUser
            },
            User = memberUser,
            AbsoluteExpiry = DateTime.UtcNow.AddHours(1)
        };

        var memberRoles = AuthenticationCoordinator.Principal(session)
            .FindAll(ClaimTypes.Role).Select(c => c.Value).ToArray();
        if (memberRoles.SequenceEqual(new[] { "User" }) is false)
            throw new InvalidOperationException("A member principal must carry exactly the API roles.");

        var adminUser = new CurrentUserResponse(1, "Admin One", "admin@example.test", "PHP",
            new[] { "Administrator" });
        var adminSession = new AdminSession
        {
            Id = "fixture-admin",
            Tokens = new AuthenticationResponse
            {
                AccessToken = "fixture-access",
                AccessTokenExpiresAtUtc = DateTime.UtcNow.AddMinutes(5),
                RefreshToken = new string('x', 86),
                RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddHours(1),
                User = adminUser
            },
            User = adminUser,
            AbsoluteExpiry = DateTime.UtcNow.AddHours(1)
        };
        var adminRoles = AuthenticationCoordinator.Principal(adminSession)
            .FindAll(ClaimTypes.Role).Select(c => c.Value).ToArray();
        if (adminRoles.SequenceEqual(new[] { "Administrator" }) is false)
            throw new InvalidOperationException("An administrator principal must not claim the User role.");

        if (ContractValidation.Authenticated(memberUser) is false || ContractValidation.Authenticated(adminUser) is false)
            throw new InvalidOperationException("Authenticated() must accept any user with at least one role.");
        if (ContractValidation.Authenticated(new CurrentUserResponse(2, "No Roles", "n@example.test", "PHP",
            Array.Empty<string>())) is not false)
            throw new InvalidOperationException("Authenticated() must reject a user with no roles.");

        Console.WriteLine("PASS: principals carry the API role set and Authenticated() accepts any role.");
    }
}
