using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

public sealed class AccountController(AuthenticationCoordinator coordinator, IAuthenticationApiClient api) : Controller
{
    [HttpGet, Authorize(Policy = AuthorizationConfiguration.AdministratorPolicy)]
    public IActionResult Index() => View();

    [AllowAnonymous, HttpGet]
    public IActionResult Register() => View(new RegisterViewModel());

    [AllowAnonymous, HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Register([FromForm] RegisterViewModel model, CancellationToken ct)
    {
        if (!ModelState.IsValid) { Response.StatusCode = StatusCodes.Status400BadRequest; return View(model); }
        var request = new RegisterRequest
        {
            FullName = model.FullName, Email = model.Email,
            Password = model.Password, ConfirmPassword = model.ConfirmPassword
        };
        var result = await api.RegisterAsync(request, ct);
        if (result.Succeeded)
        {
            TempData["RegistrationNotice"] = "Your OWLI account is ready. Sign in with your email and password.";
            return RedirectToAction(nameof(Login));
        }
        // Never re-render a rejected password.
        model.Password = string.Empty;
        model.ConfirmPassword = string.Empty;
        ModelState.Clear();
        // Show the API's reviewed field messages beside their fields (ApiMessages); otherwise a safe summary.
        var mapped = false;
        if (result.Failure == ApiFailure.BadRequest)
            foreach (var (field, messages) in result.Fields)
            {
                var key = RegisterFields.FirstOrDefault(f => string.Equals(f, field, StringComparison.OrdinalIgnoreCase)) ?? string.Empty;
                foreach (var message in messages) ModelState.AddModelError(key, message);
                mapped = true;
            }
        if (!mapped)
            ModelState.AddModelError(string.Empty, result.Failure switch
            {
                ApiFailure.Conflict => "That email is already registered. Sign in instead.",
                ApiFailure.RateLimited => "Too many attempts. Please wait a minute before trying again.",
                ApiFailure.BadRequest => "Your account could not be created with these details. Check each field and try again.",
                _ => "Registration is temporarily unavailable. Please try again later."
            });
        Response.StatusCode = StatusCodes.Status400BadRequest;
        return View(model);
    }

    private static readonly string[] RegisterFields =
        [nameof(RegisterViewModel.FullName), nameof(RegisterViewModel.Email), nameof(RegisterViewModel.Password), nameof(RegisterViewModel.ConfirmPassword)];

    [AllowAnonymous, HttpGet]
    public IActionResult Login(string? returnUrl, bool unavailable = false, bool expired = false) =>
        AuthenticatedLoginResult() ?? View(new LoginViewModel
        {
            ReturnUrl = Url.IsLocalUrl(returnUrl) ? returnUrl : null,
            Message = unavailable ? "Authentication is temporarily unavailable. Please sign in again later."
                : expired ? MemberSessionGate.ExpiredNotice : null
        });

    [AllowAnonymous, HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Login([FromForm] LoginViewModel model, CancellationToken ct)
    {
        var authenticatedResult = AuthenticatedLoginResult();
        if (authenticatedResult is not null)
        {
            model.Password = string.Empty;
            ModelState.Clear();
            return authenticatedResult;
        }
        var request = new LoginRequest { Email = model.Email, Password = model.Password };
        model.Password = string.Empty;
        model.Message = null;
        model.ReturnUrl = Url.IsLocalUrl(model.ReturnUrl) ? model.ReturnUrl : null;
        // ModelState retains attempted passwords unless explicitly removed.
        var valid = ModelState.IsValid;
        var problems = new List<string>();
        if (string.IsNullOrWhiteSpace(model.Email)) problems.Add("Enter your email address.");
        else if (ModelState.TryGetValue(nameof(LoginViewModel.Email), out var emailState) && emailState.Errors.Count > 0)
            problems.Add("Enter a valid email address.");
        if (string.IsNullOrEmpty(request.Password)) problems.Add("Enter your password.");
        else if (ModelState.TryGetValue(nameof(LoginViewModel.Password), out var passwordState) && passwordState.Errors.Count > 0)
            problems.Add("Passwords can be up to 128 characters.");
        ModelState.Clear();
        if (!valid)
        {
            request.Password = string.Empty;
            model.Message = problems.Count > 0 ? string.Join(" ", problems) : "Enter a valid email address and password.";
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
                _ => "We couldn't sign you in. Check your email and password, then try again."
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
        return model.ReturnUrl is not null ? LocalRedirect(model.ReturnUrl) : RedirectToAction("Index", "Entry");
    }

    private IActionResult? AuthenticatedLoginResult()
    {
        // The cookie middleware validates the server-side session and the backend role before either
        // action, including AllowAnonymous actions. Send every authenticated visitor through the
        // role-based landing decision; account switching still requires the POST logout followed by a
        // fresh login.
        return User.Identity?.IsAuthenticated == true ? RedirectToAction("Index", "Entry") : null;
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
