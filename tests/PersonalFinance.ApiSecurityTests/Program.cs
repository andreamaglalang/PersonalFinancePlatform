using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using PersonalFinance.Api.Authentication;
using PersonalFinance.ApiSecurityTests;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;

if (args.Contains("--startup-smoke-http", StringComparer.Ordinal)) return await StartupSmoke.RunAsync(useHttps: false);
if (args.Contains("--startup-smoke", StringComparer.Ordinal)) return await StartupSmoke.RunAsync();

var passed = 0; var failed = 0;
void Require(bool value, string message = "Assertion failed") { if (!value) throw new InvalidOperationException(message); }
async Task Check(string name, Func<Task> test)
{
    try { await test(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.Error.WriteLine("FAIL " + name + ": " + ex.GetType().Name + ": " + ex.Message); }
}
await Check("Missing/invalid signing keys fail closed", () =>
{
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddApiAuthentication(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
    {
        ["Jwt:Issuer"] = "test", ["Jwt:Audience"] = "test"
    }).Build());
    using var provider = services.BuildServiceProvider();
    var rejected = false;
    try { _ = provider.GetRequiredService<IOptions<JwtSettings>>().Value; } catch (OptionsValidationException) { rejected = true; }
    Require(rejected);
    return Task.CompletedTask;
});
await Check("Application starts its in-memory pipeline without database writes", async () =>
{
    await using var f = new ApiFixture();
    Require(f.Users.Users.Count == 0 && f.Store.Tokens.Count == 0);
});
await Check("Unauthorized me is rejected", async () =>
{
    await using var f = new ApiFixture();
    Require((await f.SendAsync("GET", "/api/auth/me")).Status == 401);
});
await Check("Valid JWT authenticates me and returns the approved user projection", async () =>
{
    await using var f = new ApiFixture(); var token = await f.LoginAsync();
    var reply = await f.SendAsync("GET", "/api/auth/me", bearer: token);
    Require(reply.Status == 200 && JsonDocument.Parse(reply.Body).RootElement.GetProperty("id").GetInt32() == 1);
    Require(reply.CacheControl == "no-store");
});
await Check("JWT includes stable subject, roles, jti, issuer/audience, and fifteen-minute lifetime", async () =>
{
    await using var f = new ApiFixture(); var raw = await f.LoginAsync();
    var jwt = new JwtSecurityTokenHandler().ReadJwtToken(raw);
    Require(jwt.Subject == "1" && jwt.Claims.Any(c => c.Type == "role" && c.Value == "User"));
    Require(!string.IsNullOrWhiteSpace(jwt.Id) && jwt.Issuer == f.Settings.Issuer && jwt.Audiences.Contains(f.Settings.Audience));
    Require(jwt.ValidTo - jwt.ValidFrom == TimeSpan.FromMinutes(15));
    Require(jwt.Header.Alg == SecurityAlgorithms.HmacSha256);
});
await Check("Expired JWT is rejected with zero clock skew", async () =>
{
    await using var f = new ApiFixture(); var id = await f.RegisterAsync();
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: f.Issue(id, DateTimeOffset.UtcNow.AddMinutes(-16)))).Status == 401);
});
await Check("Wrong signature is rejected", async () =>
{
    await using var f = new ApiFixture(); var id = await f.RegisterAsync();
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: f.Issue(id, signingKey: Convert.ToBase64String(RandomNumberGenerator.GetBytes(64))))).Status == 401);
});
await Check("Wrong issuer and audience are rejected", async () =>
{
    await using var f = new ApiFixture(); var id = await f.RegisterAsync();
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: f.Issue(id, issuer: "wrong"))).Status == 401);
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: f.Issue(id, audience: "wrong"))).Status == 401);
});
await Check("Unsigned JWT is rejected", async () =>
{
    await using var f = new ApiFixture(); await f.RegisterAsync();
    var jwt = new JwtSecurityToken(f.Settings.Issuer, f.Settings.Audience, [new Claim("sub", "1"), new Claim("role", "User")],
        DateTime.UtcNow.AddSeconds(-1), DateTime.UtcNow.AddMinutes(15));
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: new JwtSecurityTokenHandler().WriteToken(jwt))).Status == 401);
});
await Check("A locked-out account is rejected by JWT middleware", async () =>
{
    await using var f = new ApiFixture(); var token = await f.LoginAsync();
    f.Users.Users[1].LockoutEnd = DateTimeOffset.UtcNow.AddMinutes(15);
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: token)).Status == 401);
});
await Check("Stale role claims are rejected after a role change", async () =>
{
    await using var f = new ApiFixture(); var token = await f.LoginAsync();
    f.Users.Membership[1].Add("ADMINISTRATOR");
    Require((await f.SendAsync("GET", "/api/auth/me", bearer: token)).Status == 401);
});
await Check("User policy allows User; Administrator endpoint forbids ordinary users", async () =>
{
    await using var f = new ApiFixture(); var token = await f.LoginAsync();
    Require((await f.SendAsync("GET", "/test/authorization/user", bearer: token)).Status == 200);
    Require((await f.SendAsync("GET", "/test/authorization/administrator", bearer: token)).Status == 403);
});
await Check("Administrator role authorizes only the administrator test operation", async () =>
{
    await using var f = new ApiFixture(); var id = await f.RegisterAsync();
    f.Users.Membership[id].Add("ADMINISTRATOR");
    Require((await f.SendAsync("GET", "/test/authorization/administrator", bearer: f.Issue(id))).Status == 200);
});
await Check("Anonymous administrator operation is rejected", async () =>
{
    await using var f = new ApiFixture();
    Require((await f.SendAsync("GET", "/test/authorization/administrator")).Status == 401);
});
await Check("Ownership uses authenticated subject and gives Administrator no bypass", () =>
{
    var principal = new ClaimsPrincipal(new ClaimsIdentity([new Claim("sub", "1"), new Claim("role", "Administrator")], "test", "sub", "role"));
    var accessor = new HttpContextAccessor { HttpContext = new DefaultHttpContext { User = principal } };
    ICurrentUser current = new CurrentUser(accessor);
    Require(current.UserId == 1 && current.Owns(1) && !current.Owns(2));
    accessor.HttpContext.User = new ClaimsPrincipal(new ClaimsIdentity([new Claim("sub", "1")]));
    Require(current.UserId is null && !current.Owns(1));
    return Task.CompletedTask;
});
await Check("Registration DTO rejects client-supplied Role/UserId at the HTTP boundary", async () =>
{
    await using var f = new ApiFixture();
    var reply = await f.SendAsync("POST", "/api/auth/register", new { fullName = "Person", email = "person@example.test", password = "TestingOnly!123a", confirmPassword = "TestingOnly!123a", role = "Administrator", userId = 5 });
    Require(reply.Status == 400 && f.Users.Users.Count == 0);
});
await Check("Invalid registration data and weak passwords fail safely over HTTP", async () =>
{
    await using var f = new ApiFixture();
    Require((await f.SendAsync("POST", "/api/auth/register", new { fullName = "", email = "not-an-email", password = "short", confirmPassword = "different" })).Status == 400);
    Require((await f.SendAsync("POST", "/api/auth/register", new { fullName = "Person", email = "person@example.test", password = "aaaaaaaaaaaaa", confirmPassword = "aaaaaaaaaaaaa" })).Status == 400);
});
await Check("Auth responses contain no Identity security fields or persisted hash metadata", async () =>
{
    await using var f = new ApiFixture(); await f.RegisterAsync();
    var response = await f.SendAsync("POST", "/api/auth/login", new { email = "person@example.test", password = "TestingOnly!123a" });
    Require(response.Status == 200);
    foreach (var field in new[] { "passwordHash", "securityStamp", "concurrencyStamp", "tokenHash", "replacedByTokenHash" })
        Require(!response.Body.Contains(field, StringComparison.OrdinalIgnoreCase));
});
await Check("HTTP refresh rotates and replay rejects the replacement family", async () =>
{
    await using var f = new ApiFixture(); await f.RegisterAsync();
    var login = await f.SendAsync("POST", "/api/auth/login", new { email = "person@example.test", password = "TestingOnly!123a" });
    var old = JsonDocument.Parse(login.Body).RootElement.GetProperty("refreshToken").GetString()!;
    var refresh = await f.SendAsync("POST", "/api/auth/refresh", new { refreshToken = old });
    Require(refresh.Status == 200);
    var next = JsonDocument.Parse(refresh.Body).RootElement.GetProperty("refreshToken").GetString()!;
    Require((await f.SendAsync("POST", "/api/auth/refresh", new { refreshToken = old })).Status == 401);
    Require((await f.SendAsync("POST", "/api/auth/refresh", new { refreshToken = next })).Status == 401);
});
await Check("Logout-all requires authentication and ignores spoofed ownership in a body", async () =>
{
    await using var f = new ApiFixture(); var token = await f.LoginAsync();
    Require((await f.SendAsync("POST", "/api/auth/logout-all", new { userId = 99 })).Status == 401);
    var reply = await f.SendAsync("POST", "/api/auth/logout-all", new { userId = 99 }, token);
    Require(reply.Status == 204 && f.Store.Tokens.Values.Where(t => t.UserId == 1).All(t => t.RevokedAtUtc is not null));
});
await Check("Unknown logout is idempotent and does not disclose token validity", async () =>
{
    await using var f = new ApiFixture();
    Require((await f.SendAsync("POST", "/api/auth/logout", new { refreshToken = new string('A', 86) })).Status == 204);
    Require((await f.SendAsync("POST", "/api/auth/logout", new { refreshToken = new string('A', 86) })).Status == 204);
});
await Check("Credential endpoints require HTTPS and reject query strings", async () =>
{
    await using var f = new ApiFixture();
    Require((await f.SendAsync("POST", "/api/auth/login", new { email = "a@example.test", password = "test" }, scheme: "http")).Status == 400);
    Require((await f.SendAsync("GET", "/api/auth/me?access_token=test-only-value")).Status == 400);
});
await Check("Authentication endpoint rate limiting returns 429", async () =>
{
    await using var f = new ApiFixture(); var last = 0;
    for (var i = 0; i < 21; i++) last = (await f.SendAsync("GET", "/api/auth/me")).Status;
    Require(last == 429);
});
Console.WriteLine($"API security checks: {passed} passed, {failed} failed. Real JWT/MVC middleware; in-memory persistence; no SQL connections.");
return failed == 0 ? 0 : 1;

