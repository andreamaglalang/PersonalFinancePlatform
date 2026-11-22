using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

public sealed class AuthenticationApiClient(HttpClient http) : IAuthenticationApiClient
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public Task<ApiResult<CurrentUserResponse>> RegisterAsync(RegisterRequest request, CancellationToken ct) =>
        SendAsync<CurrentUserResponse>(HttpMethod.Post, "api/auth/register", request, null, ct, HttpStatusCode.Created);

    public Task<ApiResult<AuthenticationResponse>> LoginAsync(LoginRequest request, CancellationToken ct) =>
        SendAsync<AuthenticationResponse>(HttpMethod.Post, "api/auth/login", request, null, ct);
    public Task<ApiResult<AuthenticationResponse>> RefreshAsync(RefreshRequest request, CancellationToken ct) =>
        SendAsync<AuthenticationResponse>(HttpMethod.Post, "api/auth/refresh", request, null, ct);
    public Task<ApiResult<CurrentUserResponse>> GetCurrentUserAsync(string accessToken, CancellationToken ct) =>
        SendAsync<CurrentUserResponse>(HttpMethod.Get, "api/auth/me", null, accessToken, ct);
    public Task<ApiResult<bool>> LogoutAsync(RefreshRequest request, CancellationToken ct) =>
        SendAsync<bool>(HttpMethod.Post, "api/auth/logout", request, null, ct);

    private async Task<ApiResult<T>> SendAsync<T>(HttpMethod method, string route, object? body,
        string? bearer, CancellationToken ct, HttpStatusCode expected = HttpStatusCode.OK)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            if (body is not null) request.Content = JsonContent.Create(body, body.GetType(), options: Json);
            if (bearer is not null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            // ResponseContentRead keeps the HttpClient timeout applicable to bounded body buffering.
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            if (!response.IsSuccessStatusCode)
                return ApiResult<T>.Fail(response.StatusCode switch
                {
                    HttpStatusCode.BadRequest => ApiFailure.BadRequest,
                    HttpStatusCode.Unauthorized => ApiFailure.Unauthorized,
                    HttpStatusCode.Forbidden => ApiFailure.Forbidden,
                    HttpStatusCode.Conflict => ApiFailure.Conflict,
                    HttpStatusCode.TooManyRequests => ApiFailure.RateLimited,
                    _ => ApiFailure.Unavailable
                });
            if (typeof(T) == typeof(bool))
                return response.StatusCode == HttpStatusCode.NoContent
                    ? ApiResult<T>.Success((T)(object)true) : ApiResult<T>.Fail(ApiFailure.InvalidResponse);
            if (response.StatusCode != expected
                || response.Content.Headers.ContentType?.MediaType != "application/json")
                return ApiResult<T>.Fail(ApiFailure.InvalidResponse);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            if (value is AuthenticationResponse tokens && ContractValidation.Tokens(tokens)
                || value is CurrentUserResponse user && ContractValidation.User(user))
                return ApiResult<T>.Success(value!);
            return ApiResult<T>.Fail(ApiFailure.InvalidResponse);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return ApiResult<T>.Fail(ApiFailure.Unavailable);
        }
    }
}
