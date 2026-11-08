using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.ApiSecurityTests;

// This controller is compiled ONLY into the test executable, never the API.
[ApiController, Route("test/authorization")]
public sealed class TestAuthorizationController : ControllerBase
{
    [HttpGet("user"), Authorize(Policy = AuthenticationConfiguration.UserPolicy)]
    public IActionResult UserOnly() => Ok(new { allowed = true });

    [HttpGet("administrator"), Authorize(Roles = ApplicationRoles.Administrator)]
    public IActionResult AdministratorOnly() => Ok(new { allowed = true });
}

