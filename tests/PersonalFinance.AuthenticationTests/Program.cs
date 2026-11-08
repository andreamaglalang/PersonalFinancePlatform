using System.Text.Json;
using PersonalFinance.AuthenticationTests;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;
using PersonalFinance.Infrastructure.Identity;

if (args.Contains("--sql", StringComparer.Ordinal)) return await SqlIntegration.RunAsync();

var passed = 0; var failed = 0;
void Require(bool value, string message = "Assertion failed") { if (!value) throw new InvalidOperationException(message); }
async Task Check(string name, Func<Task> test)
{
    try { await test(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.Error.WriteLine("FAIL " + name + ": " + ex.GetType().Name + ": " + ex.Message); }
}
await Check("Registration succeeds through real Identity UserManager and assigns only User", async () =>
{
    using var f = new Fixture();
    var result = await f.Auth.RegisterAsync(f.Registration(), default);
    Require(result.Succeeded && result.StatusCode == 201 && result.Value!.Roles.SequenceEqual(new[] { "User" }));
    Require(f.Users.Users.Single().Value.PasswordHash is not null);
    Require(f.Users.Users.Single().Value.PasswordHash != Fixture.Password);
});
await Check("Email is trimmed and normalized consistently", async () =>
{
    using var f = new Fixture();
    var result = await f.Auth.RegisterAsync(f.Registration(" Mixed@Example.Test "), default);
    Require(result.Succeeded && f.Users.Users.Single().Value.NormalizedEmail == "MIXED@EXAMPLE.TEST");
});
await Check("Duplicate normalized email is rejected", async () =>
{
    using var f = new Fixture();
    await f.Auth.RegisterAsync(f.Registration(), default);
    Require((await f.Auth.RegisterAsync(f.Registration("PERSON@EXAMPLE.TEST"), default)).StatusCode == 409);
    Require(f.Users.Users.Count == 1);
});
await Check("Invalid registration fields and password confirmation are rejected", async () =>
{
    using var f = new Fixture();
    var request = f.Registration(); request.Email = "invalid"; request.ConfirmPassword = "different";
    Require((await f.Auth.RegisterAsync(request, default)).StatusCode == 400 && f.Users.Users.Count == 0);
});
await Check("Identity rejects weak passwords even when length is sufficient", async () =>
{
    using var f = new Fixture();
    var request = f.Registration(); request.Password = request.ConfirmPassword = "aaaaaaaaaaaaa";
    Require((await f.Auth.RegisterAsync(request, default)).StatusCode == 400 && f.Users.Users.Count == 0);
});
await Check("Public registration cannot bind Role or UserId", () =>
{
    foreach (var extra in new[] { "\"role\":\"Administrator\"", "\"userId\":2" })
    {
        var rejected = false;
        try { JsonSerializer.Deserialize<RegisterRequest>("{\"fullName\":\"Person\",\"email\":\"a@example.test\",\"password\":\"TestingOnly!123a\",\"confirmPassword\":\"TestingOnly!123a\"," + extra + "}", new JsonSerializerOptions(JsonSerializerDefaults.Web)); }
        catch (JsonException) { rejected = true; }
        Require(rejected);
    }
    return Task.CompletedTask;
});
await Check("Login returns access and high-entropy refresh tokens with approved lifetimes", async () =>
{
    using var f = new Fixture();
    var response = await f.LoginAsync();
    Require(response.RefreshToken.Length == 86);
    Require((response.RefreshTokenExpiresAtUtc - f.Clock.Now.UtcDateTime).TotalDays == 7);
    Require((response.AccessTokenExpiresAtUtc - f.Clock.Now.UtcDateTime).TotalMinutes == 15);
});
await Check("Only SHA-256 hashes are persisted", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync();
    Require(f.Store.Tokens.ContainsKey(RefreshTokenHash.Compute(response.RefreshToken)));
    Require(f.Store.Tokens.Values.All(t => t.TokenHash.Length == 64 && t.TokenHash != response.RefreshToken));
});
await Check("Invalid password and unknown email return identical generic errors", async () =>
{
    using var f = new Fixture(); await f.LoginAsync();
    var wrong = await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = "WrongPassword!123" }, default);
    var missing = await f.Auth.LoginAsync(new() { Email = "missing@example.test", Password = "WrongPassword!123" }, default);
    Require(wrong.StatusCode == 401 && wrong.Error == missing.Error && missing.StatusCode == 401);
});
await Check("Five failed attempts lock the account; correct password cannot bypass lockout", async () =>
{
    using var f = new Fixture(); await f.LoginAsync();
    for (var i = 0; i < 5; i++) await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = "WrongPassword!123" }, default);
    Require((await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = Fixture.Password }, default)).StatusCode == 401);
    Require(f.Users.Users.Single().Value.LockoutEnd > DateTimeOffset.UtcNow);
});
await Check("Successful login resets failed-attempt counts", async () =>
{
    using var f = new Fixture(); await f.LoginAsync();
    await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = "WrongPassword!123" }, default);
    var login = await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = Fixture.Password }, default);
    Require(login.Succeeded && f.Users.Users.Single().Value.AccessFailedCount == 0);
});
await Check("Refresh rotates and records the predecessor/replacement link", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var rotated = await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default);
    Require(rotated.Succeeded && rotated.Value!.RefreshToken != first.RefreshToken);
    var previous = f.Store.Tokens[RefreshTokenHash.Compute(first.RefreshToken)];
    Require(previous.RevokedAtUtc is not null && previous.ReplacedByTokenHash == RefreshTokenHash.Compute(rotated.Value!.RefreshToken));
    Require(previous.TokenFamilyId == f.Store.Tokens[previous.ReplacedByTokenHash!].TokenFamilyId);
});
await Check("Expired refresh token is rejected", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync(); f.Clock.Now = f.Clock.Now.AddDays(8);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = response.RefreshToken }, default)).StatusCode == 401);
});
await Check("Malformed and unknown refresh tokens are rejected", async () =>
{
    using var f = new Fixture();
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = "invalid" }, default)).StatusCode == 401);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = new string('A', 86) }, default)).StatusCode == 401);
});
await Check("Revoked refresh token is rejected", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync();
    await f.Sessions.LogoutAsync(new() { RefreshToken = response.RefreshToken }, default);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = response.RefreshToken }, default)).StatusCode == 401);
});
await Check("Replay revokes the entire affected token family", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var second = (await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default)).Value!;
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default)).StatusCode == 401);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = second.RefreshToken }, default)).StatusCode == 401);
    Require(f.Store.Tokens.Values.All(t => t.RevokedAtUtc is not null));
});
await Check("Replay does not revoke another session family", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var other = (await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = Fixture.Password }, default)).Value!;
    await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default);
    await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = other.RefreshToken }, default)).Succeeded);
});
await Check("Concurrent refresh attempts have at most one successful consumption; replay closes its family", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var results = await Task.WhenAll(
        f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default),
        f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default));
    Require(results.Count(r => r.Succeeded) == 1 && results.Count(r => r.StatusCode == 401) == 1);
    Require(f.Store.Tokens.Values.All(t => t.RevokedAtUtc is not null));
});
await Check("Ordinary logout is idempotent and preserves unrelated sessions", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var other = (await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = Fixture.Password }, default)).Value!;
    await f.Sessions.LogoutAsync(new() { RefreshToken = first.RefreshToken }, default);
    await f.Sessions.LogoutAsync(new() { RefreshToken = first.RefreshToken }, default);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = other.RefreshToken }, default)).Succeeded);
});
await Check("Logout with an old rotated token closes only that session", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    var second = (await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default)).Value!;
    await f.Sessions.LogoutAsync(new() { RefreshToken = first.RefreshToken }, default);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = second.RefreshToken }, default)).StatusCode == 401);
});
await Check("Unknown logout does not reveal token validity", async () =>
{
    using var f = new Fixture();
    await f.Sessions.LogoutAsync(new() { RefreshToken = new string('A', 86) }, default);
    await f.Sessions.LogoutAsync(new() { RefreshToken = "malformed" }, default);
    Require(f.Store.Tokens.Count == 0);
});
await Check("Logout-all revokes all own sessions and no other user's sessions", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    await f.Auth.LoginAsync(new() { Email = "person@example.test", Password = Fixture.Password }, default);
    var other = await f.LoginAsync("other@example.test");
    await f.Sessions.LogoutAllAsync(first.User.Id, default);
    Require(f.Store.Tokens.Values.Where(t => t.UserId == first.User.Id).All(t => t.RevokedAtUtc is not null));
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = other.RefreshToken }, default)).Succeeded);
});
await Check("Locked-out user cannot refresh", async () =>
{
    using var f = new Fixture(); var first = await f.LoginAsync();
    f.Users.Users[first.User.Id].LockoutEnd = DateTimeOffset.UtcNow.AddMinutes(15);
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = first.RefreshToken }, default)).StatusCode == 401);
});
await Check("Current-user response exposes only approved fields", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync();
    var current = await f.Auth.GetCurrentUserAsync(response.User.Id, default);
    var fields = JsonDocument.Parse(JsonSerializer.Serialize(current)).RootElement.EnumerateObject().Select(p => p.Name).Order().ToArray();
    Require(fields.SequenceEqual(new[] { "BaseCurrency", "Email", "FullName", "Id", "Roles" }.Order()));
});
await Check("Current-user rejects missing or locked-out accounts", async () =>
{
    using var f = new Fixture();
    Require(await f.Auth.GetCurrentUserAsync(999, default) is null);
    var response = await f.LoginAsync(); f.Users.Users[response.User.Id].LockoutEnd = DateTimeOffset.UtcNow.AddMinutes(1);
    Require(await f.Auth.GetCurrentUserAsync(response.User.Id, default) is null);
});
await Check("Publicly registered users never receive Administrator membership", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync();
    Require(!response.User.Roles.Contains("Administrator"));
});
await Check("Revoked predecessors remain stored for reuse detection", async () =>
{
    using var f = new Fixture(); var response = await f.LoginAsync();
    await f.Sessions.RotateAsync(new() { RefreshToken = response.RefreshToken }, default);
    await f.Sessions.LogoutAllAsync(response.User.Id, default);
    Require(f.Store.Tokens.Count == 2);
});
Console.WriteLine($"Authentication service checks: {passed} passed, {failed} failed.");
Console.WriteLine("SQL Server transaction/lock/concurrency integration: NOT RUN (no isolated SQL test database configured).");
Console.WriteLine("JWT cryptography and HTTP middleware are covered by the separate API security suite.");
return failed == 0 ? 0 : 1;

