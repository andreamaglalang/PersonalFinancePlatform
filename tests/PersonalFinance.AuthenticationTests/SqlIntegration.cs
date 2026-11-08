using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;
using Microsoft.Data.SqlClient;

namespace PersonalFinance.AuthenticationTests;

/// <summary>Opt-in tests against an ALREADY provisioned disposable database. Never creates/applies schema.</summary>
internal static class SqlIntegration
{
    public static async Task<int> RunAsync()
    {
        var value = Environment.GetEnvironmentVariable("FINANCE_AUTH_SQL_TEST_CONNECTION");
        if (string.IsNullOrWhiteSpace(value))
        {
            Console.WriteLine("SKIP SQL integration: FINANCE_AUTH_SQL_TEST_CONNECTION is not configured.");
            return 0;
        }
        var target = new SqlConnectionStringBuilder(value);
        if (!target.InitialCatalog.StartsWith("PersonalFinanceAuthTests_", StringComparison.Ordinal)
            || target.InitialCatalog.Equals("PersonalFinanceDb", StringComparison.OrdinalIgnoreCase)
            || !string.IsNullOrEmpty(target.AttachDBFilename))
            throw new InvalidOperationException("SQL tests require an already-provisioned PersonalFinanceAuthTests_* database and prohibit attached files.");

        var clock = new TestClock();
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddInfrastructure(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ConnectionStrings:DefaultConnection"] = value
        }).Build());
        services.AddSingleton<IAccessTokenIssuer>(new FakeAccessTokenIssuer(clock));
        services.AddSingleton<TimeProvider>(clock);
        await using var provider = services.BuildServiceProvider();
        var email = "auth-test-" + Guid.NewGuid().ToString("N") + "@example.test";
        AuthenticationResponse first;
        using (var scope = provider.CreateScope())
        {
            var auth = scope.ServiceProvider.GetRequiredService<IAuthenticationService>();
            var created = await auth.RegisterAsync(new RegisterRequest
            {
                FullName = "Isolated SQL Test", Email = email, Password = Fixture.Password, ConfirmPassword = Fixture.Password
            }, default);
            if (!created.Succeeded) throw new InvalidOperationException("The isolated schema/approved roles must be provisioned before SQL tests.");
            first = (await auth.LoginAsync(new() { Email = email, Password = Fixture.Password }, default)).Value!;
        }
        var passed = 0;
        async Task Check(string name, Func<Task> test) { await test(); passed++; Console.WriteLine("PASS SQL " + name); }
        void Require(bool condition) { if (!condition) throw new InvalidOperationException("SQL integration assertion failed."); }
        async Task<AuthResult<AuthenticationResponse>> Rotate(string token)
        {
            using var scope = provider.CreateScope();
            return await scope.ServiceProvider.GetRequiredService<IRefreshTokenService>().RotateAsync(new() { RefreshToken = token }, default);
        }
        async Task<AuthenticationResponse> Login()
        {
            using var scope = provider.CreateScope();
            var result = await scope.ServiceProvider.GetRequiredService<IAuthenticationService>()
                .LoginAsync(new() { Email = email, Password = Fixture.Password }, default);
            return result.Value ?? throw new InvalidOperationException("Isolated test login failed.");
        }
        await Check("independent contexts cannot both consume the same token", async () =>
        {
            var results = await Task.WhenAll(Rotate(first.RefreshToken), Rotate(first.RefreshToken));
            Require(results.Count(r => r.Succeeded) == 1 && results.Count(r => r.StatusCode == 401) == 1);
            Require((await Rotate(results.Single(r => r.Succeeded).Value!.RefreshToken)).StatusCode == 401);
        });
        await Check("replay family revocation preserves a different session", async () =>
        {
            var a = await Login(); var b = await Login();
            await Rotate(a.RefreshToken); await Rotate(a.RefreshToken);
            Require((await Rotate(b.RefreshToken)).Succeeded);
        });
        await Check("consume/add writes roll back together on an injected failure", async () =>
        {
            var session = await Login();
            var hash = RefreshTokenHash.Compute(session.RefreshToken);
            var replacement = RefreshTokenHash.Compute(Convert.ToBase64String(System.Security.Cryptography.RandomNumberGenerator.GetBytes(64)));
            using var scope = provider.CreateScope();
            var store = scope.ServiceProvider.GetRequiredService<IAuthSessionStore>();
            var original = (await store.FindAsync(hash, default))!;
            try
            {
                await store.ExecuteAsync<bool>(AuthRules.UserLock(session.User.Id), async ct =>
                {
                    var now = DateTime.UtcNow;
                    Require(await store.ConsumeAsync(hash, replacement, now, ct));
                    await store.AddAsync(new RefreshToken
                    {
                        UserId = session.User.Id, TokenFamilyId = original.TokenFamilyId, TokenHash = replacement,
                        CreatedAtUtc = now, ExpiresAtUtc = now.AddDays(7)
                    }, ct);
                    throw new InjectedFailure();
                }, default);
            }
            catch (InjectedFailure) { }
            var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            db.ChangeTracker.Clear();
            Require((await store.FindAsync(hash, default))!.RevokedAtUtc is null);
            Require(await store.FindAsync(replacement, default) is null);
        });
        await Check("logout-all and refresh serialize across independent contexts", async () =>
        {
            var session = await Login();
            async Task LogoutAll()
            {
                using var scope = provider.CreateScope();
                await scope.ServiceProvider.GetRequiredService<IRefreshTokenService>().LogoutAllAsync(session.User.Id, default);
            }
            await Task.WhenAll(Rotate(session.RefreshToken), LogoutAll());
            using var scope = provider.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            Require(!await db.RefreshTokens.AnyAsync(t => t.UserId == session.User.Id && t.RevokedAtUtc == null));
        });
        Console.WriteLine($"SQL integration: {passed} passed. Isolated test records retained; no schema changes or cleanup deletes.");
        return 0;
    }
    private sealed class InjectedFailure : Exception { }
}

