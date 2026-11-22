using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

public enum ApiFailure { None, BadRequest, Unauthorized, Forbidden, RateLimited, Unavailable, InvalidResponse, Conflict }

// Never carry upstream bodies, credentials or exception messages in error results.
public sealed class ApiResult<T>
{
    public T? Value { get; init; }
    public ApiFailure Failure { get; init; }
    public bool Succeeded => Failure == ApiFailure.None;
    public static ApiResult<T> Fail(ApiFailure failure) => new() { Failure = failure };
    public static ApiResult<T> Success(T value) => new() { Value = value };
}

public interface IAuthenticationApiClient
{
    Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct);
    Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct);
    Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct);
    Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string accessToken, CancellationToken ct);
    Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct);
}
