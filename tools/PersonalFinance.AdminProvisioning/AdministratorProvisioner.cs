using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.AdminProvisioning;

public sealed class ProvisioningException(string message) : Exception(message);
public interface IProvisioningDatabase
{
    Task VerifyAsync(CancellationToken ct);
    Task<T> ExecuteAsync<T>(Func<Task<T>> action, CancellationToken ct);
}

public static class ProvisioningSafety
{
    public const string Confirmation = "CREATE DEVELOPMENT ADMINISTRATOR IN PersonalFinanceDb";
    public static void RequireDevelopment(string? environment, string? aspnetEnvironment)
    {
        if (environment != "Development" ||
            (!string.IsNullOrEmpty(aspnetEnvironment) && aspnetEnvironment != "Development"))
            throw new ProvisioningException("Set DOTNET_ENVIRONMENT=Development; conflicting ASPNETCORE_ENVIRONMENT values are not permitted.");
    }

    public static void RequireTarget(string connectionString)
    {
        var target = new Microsoft.Data.SqlClient.SqlConnectionStringBuilder(connectionString);
        if (!string.Equals(target.DataSource, @"(localdb)\MSSQLLocalDB", StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(target.InitialCatalog, "PersonalFinanceDb", StringComparison.Ordinal) ||
            !target.IntegratedSecurity || !string.IsNullOrEmpty(target.AttachDBFilename) ||
            !string.IsNullOrEmpty(target.UserID) || !string.IsNullOrEmpty(target.Password))
            throw new ProvisioningException("Only Windows Authentication to (localdb)\\MSSQLLocalDB / PersonalFinanceDb is permitted.");
    }
}

public sealed class AdministratorProvisioner(
    UserManager<ApplicationUser> users, RoleManager<IdentityRole<int>> roles, IProvisioningDatabase database)
{
    public async Task<int> CreateAsync(string environment, string? aspnetEnvironment, string confirmation,
        string email, string fullName, string password, string confirmPassword, CancellationToken ct = default)
    {
        ProvisioningSafety.RequireDevelopment(environment, aspnetEnvironment);
        if (!string.Equals(confirmation, ProvisioningSafety.Confirmation, StringComparison.Ordinal))
            throw new ProvisioningException("Confirmation did not match. No account was created.");
        email = email.Trim(); fullName = fullName.Trim();
        if (email.Length is 0 or > 256 || !new EmailAddressAttribute().IsValid(email) ||
            fullName.Length is 0 or > 100 || password.Length is < 12 or > 128 || password != confirmPassword)
            throw new ProvisioningException("Invalid email, name, password length, or password confirmation.");

        await database.VerifyAsync(ct);
        return await database.ExecuteAsync(async () =>
        {
            ct.ThrowIfCancellationRequested();
            if (await users.FindByEmailAsync(email) is not null || await users.FindByNameAsync(email) is not null)
                throw new ProvisioningException("An account already exists. Existing accounts are never changed.");
            if (!await roles.RoleExistsAsync(ApplicationRoles.User) || !await roles.RoleExistsAsync(ApplicationRoles.Administrator))
                throw new ProvisioningException("Required roles are missing. No roles or migrations will be created.");

            var user = new ApplicationUser { Email = email, UserName = email, FullName = fullName, LockoutEnabled = true };
            var created = await users.CreateAsync(user, password);
            if (!created.Succeeded)
                throw new ProvisioningException("Identity rejected account creation. Check email uniqueness and the existing password policy.");
            var assigned = await users.AddToRolesAsync(user, [ApplicationRoles.User, ApplicationRoles.Administrator]);
            if (!assigned.Succeeded)
                throw new ProvisioningException("Role assignment failed. Account creation was rolled back.");
            ct.ThrowIfCancellationRequested();
            return user.Id;
        }, ct);
    }
}
