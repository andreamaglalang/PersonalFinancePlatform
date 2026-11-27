using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

public enum ApiFailure { None, BadRequest, Unauthorized, Forbidden, Conflict, RateLimited, Unavailable, InvalidResponse }

// Never carry upstream bodies, credentials or exception messages in error results.
public sealed class ApiResult<T>
{
    public T? Value { get; init; }
    public ApiFailure Failure { get; init; }
    /// <summary>Reviewed field messages from a rejected request (ApiMessages); "" holds form-level messages.</summary>
    public IReadOnlyDictionary<string, string[]> Fields { get; init; } = new Dictionary<string, string[]>();
    public bool Succeeded => Failure == ApiFailure.None;
    public static ApiResult<T> Fail(ApiFailure failure) => new() { Failure = failure };
    public static ApiResult<T> Success(T value) => new() { Value = value };
}

public interface IAuthenticationApiClient
{
    Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct);
    Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct);
    Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string accessToken, CancellationToken ct);
    Task<ApiResult<CurrentUserResponse>> UpdateProfileAsync(UpdateProfileRequest request, string accessToken, CancellationToken ct);
    Task<ApiResult<bool>> ChangePasswordAsync(ChangePasswordRequest request, string accessToken, CancellationToken ct);
    Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct);
    Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct);
}
