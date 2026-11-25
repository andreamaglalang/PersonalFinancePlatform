using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;

namespace PersonalFinance.Api.Controllers;

[ApiController]
[Route("api/auth")]
[EnableRateLimiting(AuthenticationConfiguration.AuthRateLimit)]
[RequestSizeLimit(16 * 1024)]
public sealed class AuthController(
    IAuthenticationService authentication,
    IRefreshTokenService sessions,
    ICurrentUser currentUser) : ControllerBase
{
    [HttpPost("register"), AllowAnonymous]
    [ProducesResponseType<CurrentUserResponse>(StatusCodes.Status201Created)]
    public async Task<IActionResult> Register([FromBody] RegisterRequest request, CancellationToken ct) =>
        Respond(await authentication.RegisterAsync(request, ct));

    [HttpPost("login"), AllowAnonymous]
    [ProducesResponseType<AuthenticationResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> Login([FromBody] LoginRequest request, CancellationToken ct) =>
        Respond(await authentication.LoginAsync(request, ct));

    [HttpPost("refresh"), AllowAnonymous]
    [ProducesResponseType<AuthenticationResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> Refresh([FromBody] RefreshRequest request, CancellationToken ct) =>
        Respond(await sessions.RotateAsync(request, ct));

    [HttpPost("logout"), AllowAnonymous]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Logout([FromBody] RefreshRequest request, CancellationToken ct)
    {
        await sessions.LogoutAsync(request, ct);
        return NoContent();
    }

    [HttpPost("logout-all"), Authorize]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> LogoutAll(CancellationToken ct)
    {
        if (currentUser.UserId is not int userId) return Unauthorized();
        await sessions.LogoutAllAsync(userId, ct);
        return NoContent();
    }

    [HttpGet("me"), Authorize]
    [ProducesResponseType<CurrentUserResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> Me(CancellationToken ct)
    {
        if (currentUser.UserId is not int userId) return Unauthorized();
        var user = await authentication.GetCurrentUserAsync(userId, ct);
        return user is null ? Unauthorized() : Ok(user);
    }

    [HttpPut("me"), Authorize]
    [ProducesResponseType<CurrentUserResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> UpdateMe([FromBody] UpdateProfileRequest request, CancellationToken ct)
    {
        if (currentUser.UserId is not int userId) return Unauthorized();
        var result = await authentication.UpdateProfileAsync(userId, request, ct);
        return result.Succeeded ? Ok(result.Value) : Problem(statusCode: result.StatusCode, title: result.Error);
    }

    [HttpPost("change-password"), Authorize]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> ChangePassword([FromBody] ChangePasswordRequest request, CancellationToken ct)
    {
        if (currentUser.UserId is not int userId) return Unauthorized();
        var result = await authentication.ChangePasswordAsync(userId, request, ct);
        return result.Succeeded ? NoContent() : Problem(statusCode: result.StatusCode, title: result.Error);
    }

    private IActionResult Respond<T>(AuthResult<T> result) =>
        result.Succeeded
            ? StatusCode(result.StatusCode, result.Value)
            : Problem(statusCode: result.StatusCode, title: result.Error);
}

