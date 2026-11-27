using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
public sealed class AccountController(AdminSessionStore sessions, IAuthenticationApiClient api) : Controller
{
    [HttpGet]
    public Task<IActionResult> Index(CancellationToken ct) => OverviewAsync("Index", ct);

    [HttpGet]
    public Task<IActionResult> Profile(CancellationToken ct) => OverviewAsync("Profile", ct);

    [HttpGet]
    public Task<IActionResult> Password(CancellationToken ct) => OverviewAsync("Password", ct);

    [HttpGet]
    public IActionResult Appearance() => View();

    [HttpGet]
    public IActionResult About() => View();

    private async Task<IActionResult> OverviewAsync(string view, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == StatusCodes.Status401Unauthorized) return RedirectToAction("Login", "Account", new { area = "" });
        var result = await api.GetCurrentUserAsync(token!, ct);
        if (result.Failure == ApiFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        return View(view, new MemberAccountViewModel
        {
            DisplayName = result.Value?.FullName ?? User.Identity?.Name ?? "OWLI member",
            Email = result.Value?.Email ?? User.FindFirstValue(ClaimTypes.Email) ?? "Email unavailable",
            AccountCurrency = result.Value?.BaseCurrency ?? MemberSessionGate.BaseCurrency(User, sessions),
            Notice = TempData["AccountNotice"] as string,
            Error = result.Succeeded ? null : "Your profile could not be refreshed. Try again.",
            Profile = new MemberProfileInput { FullName = result.Value?.FullName ?? User.Identity?.Name ?? string.Empty }
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> SaveProfile([FromForm, Microsoft.AspNetCore.Mvc.Bind(Prefix = "Profile")] MemberProfileInput input, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(input.FullName))
        {
            Response.StatusCode = 400;
            ModelState.AddModelError("Profile.FullName", "Enter a name up to 100 characters.");
            return await AccountViewAsync("Profile", input.FullName, null, token!, ct);
        }
        var result = await api.UpdateProfileAsync(new() { FullName = input.FullName.Trim() }, token!, ct);
        if (result.Failure == ApiFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!result.Succeeded)
        {
            Response.StatusCode = result.Failure == ApiFailure.Conflict ? 409 : 400;
            var reason = result.Fields.Values.SelectMany(m => m).FirstOrDefault();
            return await AccountViewAsync("Profile", input.FullName, reason ?? (result.Failure == ApiFailure.BadRequest
                ? "Your profile could not be saved. Check the name and try again."
                : "Your profile change could not be confirmed. Reload your account to check it."), token!, ct);
        }
        var session = sessions.Find(User.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is not null)
        {
            var current = false;
            await session.Gate.WaitAsync(ct);
            try
            {
                current = sessions.IsCurrent(session);
                if (current) session.User = result.Value!;
            }
            finally { session.Gate.Release(); }
            if (!current)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account", new { area = "" });
            }
            await HttpContext.SignInAsync(AdminSessionStore.Scheme, AuthenticationCoordinator.Principal(session), AuthenticationCoordinator.Properties(session));
        }
        TempData["AccountNotice"] = "Profile updated.";
        return RedirectToAction(nameof(Index));
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> ChangePassword([FromForm, Microsoft.AspNetCore.Mvc.Bind(Prefix = "Password")] MemberPasswordInput input, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            // Field messages appear beside each field; no extra summary.
            return await AccountViewAsync("Password", null, null, token!, ct);
        }
        var result = await api.ChangePasswordAsync(new() { CurrentPassword = input.CurrentPassword, NewPassword = input.NewPassword }, token!, ct);
        input.CurrentPassword = input.NewPassword = input.ConfirmPassword = string.Empty;
        if (result.Failure == ApiFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!result.Succeeded)
        {
            Response.StatusCode = result.Failure == ApiFailure.Conflict ? 409 : result.Failure == ApiFailure.BadRequest ? 400 : 503;
            string? summary = null;
            foreach (var (field, messages) in result.Fields)
            {
                var key = PasswordFields.FirstOrDefault(f => string.Equals(f, field, StringComparison.OrdinalIgnoreCase));
                foreach (var message in messages)
                    if (key is null) summary = summary is null ? message : summary + " " + message;
                    else ModelState.AddModelError("Password." + key, message);
            }
            if (result.Fields.Count == 0)
                summary = result.Failure switch
                {
                    ApiFailure.BadRequest => "The password could not be changed. Check each field and try again.",
                    ApiFailure.RateLimited => "Too many attempts. Please wait a minute before trying again.",
                    ApiFailure.Conflict => "Your account changed at the same time. Try again.",
                    _ => "The password change could not be confirmed. Sign in again with your current password to check before retrying."
                };
            return await AccountViewAsync("Password", null, summary, token!, ct);
        }
        await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
        TempData["PasswordChangedNotice"] = "Password changed. Sign in again with your new password.";
        return RedirectToAction("Login", "Account", new { area = "" });
    }

    private static readonly string[] PasswordFields =
        [nameof(MemberPasswordInput.CurrentPassword), nameof(MemberPasswordInput.NewPassword), nameof(MemberPasswordInput.ConfirmPassword)];

    private async Task<IActionResult> AccountViewAsync(string view, string? name, string? error, string token, CancellationToken ct)
    {
        var result = await api.GetCurrentUserAsync(token, ct);
        if (result.Failure == ApiFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        return View(view, new MemberAccountViewModel
        {
            DisplayName = name ?? result.Value?.FullName ?? User.Identity?.Name ?? "OWLI member",
            Email = result.Value?.Email ?? User.FindFirstValue(ClaimTypes.Email) ?? "Email unavailable",
            AccountCurrency = result.Value?.BaseCurrency ?? MemberSessionGate.BaseCurrency(User, sessions),
            Error = error,
            Profile = new MemberProfileInput { FullName = name ?? result.Value?.FullName ?? User.Identity?.Name ?? string.Empty }
        });
    }
}
