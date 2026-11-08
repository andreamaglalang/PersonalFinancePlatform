namespace PersonalFinance.Infrastructure.Authentication.Contracts;

public sealed record CurrentUserResponse(int Id, string FullName, string Email, string BaseCurrency, IReadOnlyList<string> Roles);

public sealed class AuthenticationResponse
{
    public required string AccessToken { get; init; }
    public required DateTime AccessTokenExpiresAtUtc { get; init; }
    public required string RefreshToken { get; init; }
    public required DateTime RefreshTokenExpiresAtUtc { get; init; }
    public required CurrentUserResponse User { get; init; }
}

public sealed record AccessToken(string Value, DateTime ExpiresAtUtc);

public sealed record AuthResult<T>(T? Value, int StatusCode, string? Error)
{
    public bool Succeeded => Error is null;
    public static AuthResult<T> Success(T value, int status = 200) => new(value, status, null);
    public static AuthResult<T> Failure(int status, string error) => new(default, status, error);
}

