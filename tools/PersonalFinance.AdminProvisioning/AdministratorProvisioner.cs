using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.AdminProvisioning;

public sealed class ProvisioningException(string message) : Exception(message);
public interface IProvisioningDatabase
{
    /// <summary>The approved catalog name, resolved without connecting.</summary>
    string Catalog { get; }
    Task VerifyAsync(CancellationToken ct);
    Task<T> ExecuteAsync<T>(Func<Task<T>> action, CancellationToken ct);
}

public static class ProvisioningSafety
{
    public const string Confirmation = "CREATE DEVELOPMENT ADMINISTRATOR IN PersonalFinanceDb";
    public const string RuntimeCatalogPrefix = "OWLI_Runtime_";
    public static string ConfirmationFor(string catalog) => "CREATE DEVELOPMENT ADMINISTRATOR IN " + catalog;
    public static void RequireDevelopment(string? environment, string? aspnetEnvironment)
    {
        if (environment != "Development" ||
            (!string.IsNullOrEmpty(aspnetEnvironment) && aspnetEnvironment != "Development"))
            throw new ProvisioningException("Set DOTNET_ENVIRONMENT=Development; conflicting ASPNETCORE_ENVIRONMENT values are not permitted.");
    }

    /// <summary>Returns the approved catalog: Windows LocalDB PersonalFinanceDb, or a disposable
    /// OWLI_Runtime_* catalog on any server (SQL or Windows authentication).</summary>
    public static string RequireTarget(string connectionString)
    {
        Microsoft.Data.SqlClient.SqlConnectionStringBuilder target;
        try { target = new Microsoft.Data.SqlClient.SqlConnectionStringBuilder(connectionString); }
        catch (Exception ex) when (ex is ArgumentException or FormatException or KeyNotFoundException)
        { throw new ProvisioningException("The configured connection is not a valid SQL connection string."); }
        if (!string.IsNullOrEmpty(target.AttachDBFilename))
            throw new ProvisioningException("AttachDbFilename is not permitted.");
        if (IsRuntimeCatalog(target.InitialCatalog)) return target.InitialCatalog;
        if (!string.Equals(target.DataSource, @"(localdb)\MSSQLLocalDB", StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(target.InitialCatalog, "PersonalFinanceDb", StringComparison.Ordinal) ||
            !target.IntegratedSecurity ||
            !string.IsNullOrEmpty(target.UserID) || !string.IsNullOrEmpty(target.Password))
            throw new ProvisioningException("Only Windows Authentication to (localdb)\\MSSQLLocalDB / PersonalFinanceDb or a disposable OWLI_Runtime_* catalog is permitted.");
        return target.InitialCatalog;
    }

    public static bool IsRuntimeCatalog(string? catalog) =>
        catalog is not null && catalog.Length > RuntimeCatalogPrefix.Length &&
        catalog.StartsWith(RuntimeCatalogPrefix, StringComparison.Ordinal) &&
        catalog[RuntimeCatalogPrefix.Length..].All(c => char.IsAsciiLetterOrDigit(c) || c == '_');
}

public sealed class AdministratorProvisioner(
    UserManager<ApplicationUser> users, RoleManager<IdentityRole<int>> roles, IProvisioningDatabase database)
{
    public async Task<int> CreateAsync(string environment, string? aspnetEnvironment, string confirmation,
        string email, string userName, string fullName, string password, string confirmPassword, CancellationToken ct = default)
    {
        ProvisioningSafety.RequireDevelopment(environment, aspnetEnvironment);
        if (!string.Equals(confirmation, ProvisioningSafety.ConfirmationFor(database.Catalog), StringComparison.Ordinal))
            throw new ProvisioningException("Confirmation did not match. No account was created.");
        email = email.Trim(); userName = userName.Trim(); fullName = fullName.Trim();
        var allowedUserNameCharacters = users.Options.User.AllowedUserNameCharacters;
        if (email.Length is 0 or > 256 || !new EmailAddressAttribute().IsValid(email) ||
            userName.Length is 0 or > 256 ||
            (!string.IsNullOrEmpty(allowedUserNameCharacters) && userName.Any(c => !allowedUserNameCharacters.Contains(c))) ||
            fullName.Length is 0 or > 100 || password.Length is < 12 or > 128 || password != confirmPassword)
            throw new ProvisioningException("Invalid email, username, name, password length, or password confirmation.");

        await database.VerifyAsync(ct);
        return await database.ExecuteAsync(async () =>
        {
            ct.ThrowIfCancellationRequested();
            if (await users.FindByEmailAsync(email) is not null || await users.FindByNameAsync(userName) is not null)
                throw new ProvisioningException("An account already exists. Existing accounts are never changed.");
            if (!await roles.RoleExistsAsync(ApplicationRoles.User) || !await roles.RoleExistsAsync(ApplicationRoles.Administrator))
                throw new ProvisioningException("Required roles are missing. No roles or migrations will be created.");

            var user = new ApplicationUser { Email = email, UserName = userName, FullName = fullName, LockoutEnabled = true };
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
