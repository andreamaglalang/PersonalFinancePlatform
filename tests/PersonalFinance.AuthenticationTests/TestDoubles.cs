using Microsoft.AspNetCore.DataProtection;
using System.Collections.Concurrent;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.AuthenticationTests;

internal sealed class TestClock : TimeProvider
{
    public DateTimeOffset Now { get; set; } = DateTimeOffset.UtcNow;
    public override DateTimeOffset GetUtcNow() => Now;
}

internal sealed class FakeIdentityStore : IUserPasswordStore<ApplicationUser>, IUserEmailStore<ApplicationUser>,
    IUserRoleStore<ApplicationUser>, IUserLockoutStore<ApplicationUser>, IUserSecurityStampStore<ApplicationUser>
{
    public Dictionary<int, ApplicationUser> Users { get; } = [];
    public Dictionary<int, HashSet<string>> Membership { get; } = [];
    public void Dispose() { }
    public Task<IdentityResult> CreateAsync(ApplicationUser user, CancellationToken ct)
    {
        if (Users.Values.Any(u => u.NormalizedEmail == user.NormalizedEmail)) return Task.FromResult(IdentityResult.Failed(new IdentityError { Code = "DuplicateEmail" }));
        user.Id = Users.Count + 1; Users.Add(user.Id, user); Membership[user.Id] = [];
        return Task.FromResult(IdentityResult.Success);
    }
    public Task<IdentityResult> UpdateAsync(ApplicationUser user, CancellationToken ct) { Users[user.Id] = user; return Task.FromResult(IdentityResult.Success); }
    public Task<IdentityResult> DeleteAsync(ApplicationUser user, CancellationToken ct) => throw new NotSupportedException();
    public Task<string> GetUserIdAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.Id.ToString());
    public Task<string?> GetUserNameAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.UserName);
    public Task SetUserNameAsync(ApplicationUser user, string? value, CancellationToken ct) { user.UserName = value; return Task.CompletedTask; }
    public Task<string?> GetNormalizedUserNameAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.NormalizedUserName);
    public Task SetNormalizedUserNameAsync(ApplicationUser user, string? value, CancellationToken ct) { user.NormalizedUserName = value; return Task.CompletedTask; }
    public Task<ApplicationUser?> FindByIdAsync(string id, CancellationToken ct) => Task.FromResult(Users.GetValueOrDefault(int.Parse(id)));
    public Task<ApplicationUser?> FindByNameAsync(string name, CancellationToken ct) => Task.FromResult(Users.Values.SingleOrDefault(u => u.NormalizedUserName == name));
    public Task SetPasswordHashAsync(ApplicationUser user, string? hash, CancellationToken ct) { user.PasswordHash = hash; return Task.CompletedTask; }
    public Task<string?> GetPasswordHashAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.PasswordHash);
    public Task<bool> HasPasswordAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.PasswordHash is not null);
    public Task SetEmailAsync(ApplicationUser user, string? email, CancellationToken ct) { user.Email = email; return Task.CompletedTask; }
    public Task<string?> GetEmailAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.Email);
    public Task<bool> GetEmailConfirmedAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.EmailConfirmed);
    public Task SetEmailConfirmedAsync(ApplicationUser user, bool value, CancellationToken ct) { user.EmailConfirmed = value; return Task.CompletedTask; }
    public Task<ApplicationUser?> FindByEmailAsync(string email, CancellationToken ct) => Task.FromResult(Users.Values.SingleOrDefault(u => u.NormalizedEmail == email));
    public Task<string?> GetNormalizedEmailAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.NormalizedEmail);
    public Task SetNormalizedEmailAsync(ApplicationUser user, string? email, CancellationToken ct) { user.NormalizedEmail = email; return Task.CompletedTask; }
    public Task AddToRoleAsync(ApplicationUser user, string role, CancellationToken ct) { Membership[user.Id].Add(role); return Task.CompletedTask; }
    public Task RemoveFromRoleAsync(ApplicationUser user, string role, CancellationToken ct) { Membership[user.Id].Remove(role); return Task.CompletedTask; }
    public Task<IList<string>> GetRolesAsync(ApplicationUser user, CancellationToken ct) =>
        Task.FromResult<IList<string>>(Membership[user.Id].Select(r => r == "USER" ? "User" : "Administrator").ToList());
    public Task<bool> IsInRoleAsync(ApplicationUser user, string role, CancellationToken ct) => Task.FromResult(Membership[user.Id].Contains(role));
    public Task<IList<ApplicationUser>> GetUsersInRoleAsync(string role, CancellationToken ct) =>
        Task.FromResult<IList<ApplicationUser>>(Users.Values.Where(u => Membership[u.Id].Contains(role)).ToList());
    public Task<DateTimeOffset?> GetLockoutEndDateAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.LockoutEnd);
    public Task SetLockoutEndDateAsync(ApplicationUser user, DateTimeOffset? value, CancellationToken ct) { user.LockoutEnd = value; return Task.CompletedTask; }
    public Task<int> IncrementAccessFailedCountAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(++user.AccessFailedCount);
    public Task ResetAccessFailedCountAsync(ApplicationUser user, CancellationToken ct) { user.AccessFailedCount = 0; return Task.CompletedTask; }
    public Task<int> GetAccessFailedCountAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.AccessFailedCount);
    public Task<bool> GetLockoutEnabledAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.LockoutEnabled);
    public Task SetLockoutEnabledAsync(ApplicationUser user, bool value, CancellationToken ct) { user.LockoutEnabled = value; return Task.CompletedTask; }
    public Task SetSecurityStampAsync(ApplicationUser user, string stamp, CancellationToken ct) { user.SecurityStamp = stamp; return Task.CompletedTask; }
    public Task<string?> GetSecurityStampAsync(ApplicationUser user, CancellationToken ct) => Task.FromResult(user.SecurityStamp);
}

internal sealed class FakeRoleStore : IRoleStore<IdentityRole<int>>
{
    private readonly IdentityRole<int>[] roles =
    [
        new() { Id = 1, Name = "User", NormalizedName = "USER" },
        new() { Id = 2, Name = "Administrator", NormalizedName = "ADMINISTRATOR" }
    ];
    public void Dispose() { }
    public Task<IdentityResult> CreateAsync(IdentityRole<int> role, CancellationToken ct) => throw new NotSupportedException();
    public Task<IdentityResult> UpdateAsync(IdentityRole<int> role, CancellationToken ct) => throw new NotSupportedException();
    public Task<IdentityResult> DeleteAsync(IdentityRole<int> role, CancellationToken ct) => throw new NotSupportedException();
    public Task<string> GetRoleIdAsync(IdentityRole<int> role, CancellationToken ct) => Task.FromResult(role.Id.ToString());
    public Task<string?> GetRoleNameAsync(IdentityRole<int> role, CancellationToken ct) => Task.FromResult(role.Name);
    public Task SetRoleNameAsync(IdentityRole<int> role, string? name, CancellationToken ct) { role.Name = name; return Task.CompletedTask; }
    public Task<string?> GetNormalizedRoleNameAsync(IdentityRole<int> role, CancellationToken ct) => Task.FromResult(role.NormalizedName);
    public Task SetNormalizedRoleNameAsync(IdentityRole<int> role, string? name, CancellationToken ct) { role.NormalizedName = name; return Task.CompletedTask; }
    public Task<IdentityRole<int>?> FindByIdAsync(string id, CancellationToken ct) => Task.FromResult(roles.SingleOrDefault(r => r.Id.ToString() == id));
    public Task<IdentityRole<int>?> FindByNameAsync(string name, CancellationToken ct) => Task.FromResult(roles.SingleOrDefault(r => r.NormalizedName == name));
}

/// <summary>Service-test double only. Does not emulate SQL Server transactions or sp_getapplock.</summary>
internal sealed class FakeSessionStore : IAuthSessionStore
{
    private readonly SemaphoreSlim gate = new(1, 1);
    public ConcurrentDictionary<string, RefreshToken> Tokens { get; } = new();
    public async Task<T> ExecuteAsync<T>(string resource, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
    {
        await Task.Yield();
        await gate.WaitAsync(ct);
        try { return await operation(ct); } finally { gate.Release(); }
    }
    public Task<RefreshToken?> FindAsync(string hash, CancellationToken ct) => Task.FromResult(Tokens.GetValueOrDefault(hash));
    public Task ReloadUserAsync(ApplicationUser user, CancellationToken ct) => Task.CompletedTask;
    public Task AddAsync(RefreshToken token, CancellationToken ct)
    {
        if (!Tokens.TryAdd(token.TokenHash, token)) throw new InvalidOperationException("Duplicate token hash.");
        return Task.CompletedTask;
    }
    public Task<bool> ConsumeAsync(string hash, string replacement, DateTime now, CancellationToken ct)
    {
        var token = Tokens.GetValueOrDefault(hash);
        if (token is null || token.RevokedAtUtc is not null || token.ExpiresAtUtc <= now) return Task.FromResult(false);
        token.RevokedAtUtc = now; token.ReplacedByTokenHash = replacement;
        return Task.FromResult(true);
    }
    public Task<bool> FamilyRevokedAsync(int userId, Guid family, CancellationToken ct) =>
        Task.FromResult(Tokens.Values.Any(t => t.UserId == userId && t.TokenFamilyId == family && t.RevokedAtUtc is not null && t.ReplacedByTokenHash is null));
    public Task RevokeFamilyAsync(int userId, Guid family, DateTime now, CancellationToken ct)
    {
        foreach (var token in Tokens.Values.Where(t => t.UserId == userId && t.TokenFamilyId == family && t.RevokedAtUtc is null)) token.RevokedAtUtc = now;
        return Task.CompletedTask;
    }
    public Task RevokeAllAsync(int userId, DateTime now, CancellationToken ct)
    {
        foreach (var token in Tokens.Values.Where(t => t.UserId == userId && t.RevokedAtUtc is null)) token.RevokedAtUtc = now;
        return Task.CompletedTask;
    }
}

internal sealed class FakeAccessTokenIssuer(TestClock clock) : IAccessTokenIssuer
{
    public AccessToken Issue(ApplicationUser user, IReadOnlyList<string> roles) =>
        new("test-only-access-" + Guid.NewGuid().ToString("N"), clock.GetUtcNow().UtcDateTime.AddMinutes(15));
}

internal sealed class Fixture : IDisposable
{
    public const string Password = "TestingOnly!123a";
    private readonly ServiceProvider provider;
    private readonly IServiceScope scope;
    public FakeIdentityStore Users { get; } = new();
    public FakeSessionStore Store { get; } = new();
    public TestClock Clock { get; } = new();
    public IAuthenticationService Auth { get; }
    public IRefreshTokenService Sessions { get; }
    public Fixture()
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddDataProtection().UseEphemeralDataProtectionProvider();
        services.AddInfrastructure(new ConfigurationBuilder().Build());
        services.AddSingleton<IUserStore<ApplicationUser>>(Users);
        services.AddSingleton<IRoleStore<IdentityRole<int>>>(new FakeRoleStore());
        services.AddSingleton<IAuthSessionStore>(Store);
        services.AddSingleton<TimeProvider>(Clock);
        services.AddSingleton<IAccessTokenIssuer>(new FakeAccessTokenIssuer(Clock));
        provider = services.BuildServiceProvider();
        scope = provider.CreateScope();
        Auth = scope.ServiceProvider.GetRequiredService<IAuthenticationService>();
        Sessions = scope.ServiceProvider.GetRequiredService<IRefreshTokenService>();
    }
    public RegisterRequest Registration(string email = "person@example.test") =>
        new() { FullName = "Test Person", Email = email, Password = Password, ConfirmPassword = Password };
    public async Task<AuthenticationResponse> LoginAsync(string email = "person@example.test")
    {
        await Auth.RegisterAsync(Registration(email), default);
        var result = await Auth.LoginAsync(new() { Email = email, Password = Password }, default);
        return result.Value ?? throw new InvalidOperationException("Fixture login failed: " + result.Error);
    }
    public void Dispose() { scope.Dispose(); provider.Dispose(); }
}

