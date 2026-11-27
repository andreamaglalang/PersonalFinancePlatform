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
Console.WriteLine("SQL Server transaction/lock/concurrency integration: NOT RUN (no isolated SQL test database configured).");
await Check("Profile update changes only the authenticated name and detects stale versions", async () =>
{
    using var f = new Fixture(); var login = await f.LoginAsync();
    var before = (await f.Account.GetProfileAsync(login.User.Id, default)).Value!;
    var changed = await f.Account.UpdateProfileAsync(login.User.Id, new() { FullName = " Updated Name ", Version = before.Version }, default);
    Require(changed.Succeeded && changed.Value!.FullName == "Updated Name" && changed.Value.Email == before.Email && changed.Value.BaseCurrency == before.BaseCurrency);
    Require((await f.Account.UpdateProfileAsync(login.User.Id, new() { FullName = "Stale", Version = before.Version }, default)).StatusCode == 409);
    Require((await f.Account.GetProfileAsync(login.User.Id, default)).Value!.FullName == "Updated Name");
});
await Check("Password change verifies old password, follows policy and revokes every refresh family", async () =>
{
    using var f = new Fixture(); var login = await f.LoginAsync();
    var user = f.Users.Users[login.User.Id]; var oldStamp = user.SecurityStamp;
    Require((await f.Account.ChangePasswordAsync(user.Id, new() { CurrentPassword = "WrongPassword!123", NewPassword = "DifferentValid!234", ConfirmPassword = "DifferentValid!234" }, default)).StatusCode == 400);
    Require(user.SecurityStamp == oldStamp && f.Store.Tokens.Values.All(t => t.RevokedAtUtc is null));
    Require((await f.Account.ChangePasswordAsync(user.Id, new() { CurrentPassword = Fixture.Password, NewPassword = "aaaaaaaaaaaa", ConfirmPassword = "aaaaaaaaaaaa" }, default)).StatusCode == 400);
    var result = await f.Account.ChangePasswordAsync(user.Id, new() { CurrentPassword = Fixture.Password, NewPassword = "DifferentValid!234", ConfirmPassword = "DifferentValid!234" }, default);
    Require(result.Succeeded && result.StatusCode == 204 && user.SecurityStamp != oldStamp);
    Require(f.Store.Tokens.Values.All(t => t.RevokedAtUtc is not null));
    Require((await f.Sessions.RotateAsync(new() { RefreshToken = login.RefreshToken }, default)).StatusCode == 401);
    Require((await f.Auth.LoginAsync(new() { Email = login.User.Email, Password = Fixture.Password }, default)).StatusCode == 401);
    Require((await f.Auth.LoginAsync(new() { Email = login.User.Email, Password = "DifferentValid!234" }, default)).Succeeded);
});
await Check("Profile/password request DTOs reject role, email, currency and identity overposting", () =>
{
    foreach (var extra in new[] { "\"roles\":[\"Administrator\"]", "\"email\":\"other@example.test\"", "\"id\":2", "\"baseCurrency\":\"USD\"" })
    {
        var rejected = false;
        try { JsonSerializer.Deserialize<AccountUpdateProfileRequest>("{\"fullName\":\"Person\",\"version\":\"" + new string('A', 64) + "\"," + extra + "}", new JsonSerializerOptions(JsonSerializerDefaults.Web)); }
        catch (JsonException) { rejected = true; }
        Require(rejected);
    }
    return Task.CompletedTask;
});
// Validation feedback: every rejection names the actual unmet requirement, field by field.
string? Field<T>(AuthResult<T> result, string field) => result.Errors is { } e && e.TryGetValue(field, out var m) ? string.Join(" ", m) : null;
await Check("Reported defect: a 12-character matching password names the missing uppercase letter and special character", async () =>
{
    using var f = new Fixture();
    var request = f.Registration(); request.Password = request.ConfirmPassword = "abcdefghijk1";
    var result = await f.Auth.RegisterAsync(request, default);
    Require(result.StatusCode == 400 && f.Users.Users.Count == 0);
    Require(Field(result, "Password") == "Password must contain an uppercase letter and a special character, such as ! or #.", Field(result, "Password") ?? "none");
});
await Check("Every invalid registration field is reported at once with plain wording", async () =>
{
    using var f = new Fixture();
    var request = f.Registration(); request.FullName = ""; request.Email = "invalid"; request.Password = "abc"; request.ConfirmPassword = "abd";
    var result = await f.Auth.RegisterAsync(request, default);
    Require(Field(result, "FullName") == "Enter your full name.");
    Require(Field(result, "Email") == "Enter a valid email address.");
    Require(Field(result, "ConfirmPassword") == "Passwords do not match.");
    Require(Field(result, "Password") == "Password must contain at least 12 characters, an uppercase letter, a number, a special character, such as ! or # and at least 4 different characters.", Field(result, "Password") ?? "none");
});
await Check("Password policy describer mirrors the Identity options exactly", async () =>
{
    Require(PasswordPolicy.Describe("Synthetic!Pass123") is null);
    Require(PasswordPolicy.Unmet("").Count == 6);
    Require(PasswordPolicy.Describe("Aa1!" + new string('x', 125)) == "Password must contain no more than 128 characters.");
    Require(PasswordPolicy.Describe("Éabcdefghij1") == "Password must contain an uppercase letter.", "accented capitals are special characters for Identity");
    // Identity agrees with the describer for each sample.
    using var f = new Fixture();
    foreach (var sample in new[] { "Synthetic!Pass123", "abcdefghijk1", "ABCDEFGHIJK1!", "Aa1!Aa1!Aa1!", "Éabcdefghij1", "Short1!a" })
    {
        var request = f.Registration($"p{Math.Abs(sample.GetHashCode())}@example.test"); request.Password = request.ConfirmPassword = sample;
        var accepted = (await f.Auth.RegisterAsync(request, default)).Succeeded;
        Require(accepted == (PasswordPolicy.Describe(sample) is null), $"policy and Identity disagree for {sample}");
    }
});
await Check("Change password names a wrong current password, a reused password and each unmet rule", async () =>
{
    using var f = new Fixture(); var login = await f.LoginAsync(); var id = login.User.Id;
    var wrong = await f.Account.ChangePasswordAsync(id, new() { CurrentPassword = "WrongPassword!123", NewPassword = "DifferentValid!234", ConfirmPassword = "DifferentValid!234" }, default);
    Require(Field(wrong, "CurrentPassword") == PasswordMessages.CurrentIncorrect);
    var same = await f.Account.ChangePasswordAsync(id, new() { CurrentPassword = Fixture.Password, NewPassword = Fixture.Password, ConfirmPassword = Fixture.Password }, default);
    Require(Field(same, "NewPassword") == PasswordMessages.SameAsCurrent);
    var weak = await f.Account.ChangePasswordAsync(id, new() { CurrentPassword = Fixture.Password, NewPassword = "abcdefghijk1", ConfirmPassword = "abcdefghijk1x" }, default);
    Require(Field(weak, "NewPassword") == "Password must contain an uppercase letter and a special character, such as ! or #.");
    Require(Field(weak, "ConfirmPassword") == "Passwords do not match.");
    var authWrong = await f.Auth.ChangePasswordAsync(id, new() { CurrentPassword = "WrongPassword!123", NewPassword = "DifferentValid!234" }, default);
    Require(Field(authWrong, "CurrentPassword") == PasswordMessages.CurrentIncorrect);
});
Console.WriteLine("JWT cryptography and HTTP middleware are covered by the separate API security suite.");
Console.WriteLine($"Authentication service checks: {passed} passed, {failed} failed.");
return failed == 0 ? 0 : 1;

