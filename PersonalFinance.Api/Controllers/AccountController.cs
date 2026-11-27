using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Authentication.Contracts;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/account"), RequestSizeLimit(16 * 1024)]
[EnableRateLimiting(AuthenticationConfiguration.AuthRateLimit)]
public sealed class AccountController(IAccountService account, ICurrentUser currentUser) : ControllerBase
{
    [HttpGet("profile"), ProducesResponseType<AccountProfileResponse>(200)]
    public async Task<IActionResult> Profile(CancellationToken ct) => currentUser.UserId is int id
        ? Respond(await account.GetProfileAsync(id, ct)) : Unauthorized();

    [HttpPut("profile"), ProducesResponseType<AccountProfileResponse>(200)]
    public async Task<IActionResult> Update([FromBody] AccountUpdateProfileRequest request, CancellationToken ct) => currentUser.UserId is int id
        ? Respond(await account.UpdateProfileAsync(id, request, ct)) : Unauthorized();

    [HttpPost("change-password"), ProducesResponseType(204)]
    public async Task<IActionResult> ChangePassword([FromBody] AccountChangePasswordRequest request, CancellationToken ct) => currentUser.UserId is int id
        ? Respond(await account.ChangePasswordAsync(id, request, ct)) : Unauthorized();

    private IActionResult Respond<T>(AuthResult<T> result) => !result.Succeeded
        ? this.Failure(result)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}
