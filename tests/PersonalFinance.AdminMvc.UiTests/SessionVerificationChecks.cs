using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

/// <summary>MVC reuses a successful session check briefly instead of calling GET api/auth/me on every page view.</summary>
internal static class SessionVerificationChecks
{
    public static async Task RunAsync()
    {
        using var store = new AdminSessionStore();
        var user = new CurrentUserResponse(5, "Member", "member@example.test", "PHP", ["User"]);
        var session = store.Create(new AuthenticationResponse
        {
            AccessToken = "access", AccessTokenExpiresAtUtc = DateTime.UtcNow.AddMinutes(10),
            RefreshToken = "refresh", RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddDays(7), User = user
        }, user)!;
        var api = new CountingApi(user);
        var coordinator = new AuthenticationCoordinator(store, api);
        for (var i = 0; i < 10; i++)
            Check(await coordinator.ValidateAsync(session.Id, default) == ApiFailure.None, "valid session");
        Check(api.MeCalls == 1, $"ten page views within the reuse window make one API check (made {api.MeCalls})");
        session.LastVerifiedAt = DateTimeOffset.UtcNow - AuthenticationCoordinator.VerificationReuse - TimeSpan.FromSeconds(1);
        Check(await coordinator.ValidateAsync(session.Id, default) == ApiFailure.None && api.MeCalls == 2, "the check is repeated after the window");
        api.Fail = ApiFailure.Unauthorized; session.LastVerifiedAt = null;
        Check(await coordinator.ValidateAsync(session.Id, default) == ApiFailure.Unauthorized, "a rejected check still signs the member out");
        Console.WriteLine("PASS: MVC reuses a successful session check for 30 seconds, then re-verifies; rejections still apply.");
    }

    private static void Check(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }

    private sealed class CountingApi(CurrentUserResponse user) : IAuthenticationApiClient
    {
        public int MeCalls; public ApiFailure Fail = ApiFailure.None;
        public Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string accessToken, CancellationToken ct)
        {
            MeCalls++;
            return Task.FromResult(Fail == ApiFailure.None ? ApiResult<CurrentUserResponse>.Success(user) : ApiResult<CurrentUserResponse>.Fail(Fail));
        }
        public Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct) => Task.FromResult(ApiResult<bool>.Success(true));
        public Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<CurrentUserResponse>> UpdateProfileAsync(UpdateProfileRequest request, string accessToken, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<bool>> ChangePasswordAsync(ChangePasswordRequest request, string accessToken, CancellationToken ct) => throw new NotSupportedException();
        public Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct) => throw new NotSupportedException();
    }
}
