using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class MemberRenewalChecks
{
    public static async Task RunAsync()
    {
        var checks = 0;
        foreach (var role in new[] { "User", "Administrator" })
        {
            using var store = new AdminSessionStore();
            var api = new Api(role);
            var session = store.Create(api.Tokens("old", 30), api.User)!;
            var coordinator = new AuthenticationCoordinator(store, api);
            var results = await Task.WhenAll(Enumerable.Range(0, 3).Select(_ => coordinator.ValidateAsync(session.Id, default)));
            Check(results.All(r => r == ApiFailure.None) && api.Refreshes == 1 && store.Find(session.Id) == session,
                role + " renews once under concurrent navigation"); checks++;
            Check(AuthenticationCoordinator.Principal(session).IsInRole("Administrator") == (role == "Administrator"),
                "refresh never promotes a member"); checks++;
            await coordinator.LogoutAsync(session.Id);
            Check(store.Find(session.Id) == null && api.Logouts == 1, "logout revokes renewed session"); checks++;
        }
        foreach (var scenario in new[] { "owner", "roles", "same-token", "expired", "ambiguous", "rate-limit" })
        {
            using var store = new AdminSessionStore();
            var api = new Api("User") { Scenario = scenario };
            var session = store.Create(api.Tokens("old", 30), api.User)!;
            var result = await new AuthenticationCoordinator(store, api).ValidateAsync(session.Id, default);
            Check(scenario == "rate-limit"
                ? result == ApiFailure.RateLimited && store.Find(session.Id) != null
                : result != ApiFailure.None && store.Find(session.Id) == null, scenario + " fails closed"); checks++;
            Check(api.Refreshes == 1, "ambiguous refresh is never retried"); checks++;
        }
        Console.WriteLine($"PASS: {checks} member/admin renewal, isolation, concurrent rotation and failure checks.");
    }
    static void Check(bool value, string reason) { if (!value) throw new InvalidOperationException(reason); }
    sealed class Api(string role) : IAuthenticationApiClient
    {
        public string Scenario = "";
        public int Refreshes, Logouts;
        public CurrentUserResponse User { get; } = new(5, "Member", "member@example.test", "PHP", [role]);
        public AuthenticationResponse Tokens(string marker, int seconds) => new()
        {
            AccessToken = marker, AccessTokenExpiresAtUtc = DateTime.UtcNow.AddSeconds(seconds),
            RefreshToken = new string(marker == "old" ? 'a' : 'b', 86), RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddDays(7),
            User = Scenario == "owner" && marker != "old" ? User with { Id = 99 }
                : Scenario == "roles" && marker != "old" ? User with { Roles = [] } : User
        };
        public Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct)
        {
            Refreshes++;
            return Task.FromResult(Scenario is "ambiguous" or "rate-limit"
                ? ApiResult<AuthenticationResponse>.Fail(Scenario == "ambiguous" ? ApiFailure.Unavailable : ApiFailure.RateLimited)
                : ApiResult<AuthenticationResponse>.Success(Tokens(Scenario == "same-token" ? "old" : "new", Scenario == "expired" ? -1 : 900)));
        }
        public Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string token, CancellationToken ct) => Task.FromResult(ApiResult<CurrentUserResponse>.Success(User));
        public Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct) { Logouts++; return Task.FromResult(ApiResult<bool>.Success(true)); }
        public Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<CurrentUserResponse>> UpdateProfileAsync(UpdateProfileRequest request, string token, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<bool>> ChangePasswordAsync(ChangePasswordRequest request, string token, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct) => throw new NotSupportedException();
    }
}
