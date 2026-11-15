using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

public sealed class AccountController(AuthenticationCoordinator coordinator) : Controller
{
    [AllowAnonymous, HttpGet]
    public IActionResult Login(string? returnUrl, bool unavailable = false) => View(new LoginViewModel
    {
        ReturnUrl = Url.IsLocalUrl(returnUrl) ? returnUrl : null,
        Message = unavailable ? "Authentication is temporarily unavailable. Please sign in again later." : null
    });

    [AllowAnonymous, HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Login([FromForm] LoginViewModel model, CancellationToken ct)
    {
        var request = new LoginRequest { Email = model.Email, Password = model.Password };
        model.Password = string.Empty;
        model.Message = null;
        model.ReturnUrl = Url.IsLocalUrl(model.ReturnUrl) ? model.ReturnUrl : null;
        // ModelState retains attempted passwords unless explicitly removed.
        var valid = ModelState.IsValid;
        ModelState.Clear();
        if (!valid)
        {
            request.Password = string.Empty;
            model.Message = "Enter a valid email address and password.";
            return View(model);
        }
        ApiResult<AdminSession> result;
        try { result = await coordinator.LoginAsync(request, ct); }
        finally { request.Password = string.Empty; }
        if (!result.Succeeded)
        {
            model.Message = result.Failure switch
            {
                ApiFailure.RateLimited => "Too many sign-in attempts. Please wait before trying again.",
                ApiFailure.Unavailable or ApiFailure.InvalidResponse => "Authentication is temporarily unavailable. Please try again later.",
                _ => "Unable to sign in to the administrator application with these credentials."
            };
            return View(model);
        }
        var session = result.Value!;
        try
        {
            await coordinator.LogoutAsync(User.FindFirstValue(AdminSessionStore.SessionClaim));
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            await HttpContext.SignInAsync(AdminSessionStore.Scheme,
                AuthenticationCoordinator.Principal(session), AuthenticationCoordinator.Properties(session));
        }
        catch
        {
            await coordinator.LogoutAsync(session.Id);
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            model.Message = "Authentication is temporarily unavailable. Please try again later.";
            return View(model);
        }
        return model.ReturnUrl is not null ? LocalRedirect(model.ReturnUrl) : RedirectToAction("Index", "Dashboard");
    }

    [HttpPost, ValidateAntiForgeryToken, LocalLogout]
    public async Task<IActionResult> Logout()
    {
        try { await coordinator.LogoutAsync(User.FindFirstValue(AdminSessionStore.SessionClaim)); }
        finally { await HttpContext.SignOutAsync(AdminSessionStore.Scheme); }
        // No assertion that remote revocation succeeded when the backend is unavailable.
        return RedirectToAction(nameof(Login));
    }

    [AllowAnonymous, HttpGet]
    public IActionResult AccessDenied()
    {
        Response.StatusCode = StatusCodes.Status403Forbidden;
        return View();
    }

    [AllowAnonymous, IgnoreAntiforgeryToken]
    public IActionResult Error()
    {
        Response.StatusCode = StatusCodes.Status500InternalServerError;
        return View("~/Views/Shared/Error.cshtml");
    }
}
