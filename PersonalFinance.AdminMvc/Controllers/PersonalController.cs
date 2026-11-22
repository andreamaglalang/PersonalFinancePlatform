using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Route("Personal")]
public sealed class PersonalController(
    [FromKeyedServices(PersonalSession.Scheme)] AuthenticationCoordinator coordinator,
    IAuthenticationApiClient api, PersonalFinanceApiClient finance) : PersonalFinanceController
{
    [HttpGet("")]
    public async Task<IActionResult> Index(string? month, CancellationToken ct, string? period = null)
    {
        if (period == "all")
        {
            var overview = await finance.OverviewAsync(User, null, ct);
            if (await AccessFailure(overview.Status) is { } deniedAll) return deniedAll;
            var latest = await finance.TransactionsAsync(User, new TransactionFilters(), ct);
            if (await AccessFailure(latest.Status) is { } deniedLatest) return deniedLatest;
            var lastSelected = PersonalMonth.TryParse(month, out var requested) && PersonalMonth.IsReportMonth(requested)
                ? requested : PersonalMonth.Current;
            return View(new PersonalMonthlyView(lastSelected, null, overview.Value is null ? Error(overview) : null,
                latest.Value?.Items.Take(5).ToArray(), latest.Value is null ? Error(latest) : null, overview.Value, AllTime: true));
        }

        if (!PersonalMonth.TryParse(month ?? PersonalMonth.Format(PersonalMonth.Current), out var selected)
            || !PersonalMonth.IsReportMonth(selected))
        {
            Response.StatusCode = 400;
            return View(new PersonalMonthlyView(null, null, "Choose a valid month from 2000 through 9998."));
        }
        var report = await finance.MonthlyAsync(User, selected, ct);
        if (await AccessFailure(report.Status) is { } denied) return denied;
        var recent = await finance.TransactionsAsync(User, new TransactionFilters
        {
            FromDate = selected,
            ToDate = selected.AddMonths(1).AddDays(-1)
        }, ct);
        if (await AccessFailure(recent.Status) is { } recentDenied) return recentDenied;
        var transactionError = recent.Value is null ? Error(recent) : null;
        var reportError = report.Value is null ? Error(report) : null;
        return View(new PersonalMonthlyView(selected, report.Value, reportError,
            recent.Value?.Items.Take(5).ToArray(), transactionError));
    }

    [HttpGet("Account")]
    public IActionResult Account() => HttpContext.Items[PersonalSession.ProfileItem] is CurrentUserResponse profile
        ? View(profile) : StatusCode(StatusCodes.Status503ServiceUnavailable);

    [HttpPost("Account"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Account([FromForm] PersonalDisplayName form, CancellationToken ct)
    {
        if (HttpContext.Items[PersonalSession.ProfileItem] is not CurrentUserResponse profile)
            return StatusCode(503);
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            ViewData["ProfileError"] = "Enter a name of up to 100 characters.";
            return View(profile);
        }
        var result = await finance.SaveDisplayNameAsync(User, form.FullName, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is null)
        {
            ViewData["ProfileError"] = Error(result);
            return View(profile);
        }
        TempData["PersonalProfileNotice"] = "Display name updated.";
        return RedirectToAction(nameof(Account));
    }

    [HttpGet("Currency")]
    public IActionResult Currency() => View(new PersonalConversion());

    [HttpPost("Currency"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Currency([FromForm] PersonalConversion values, CancellationToken ct)
    {
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View(values);
        }
        var result = await finance.ConvertAsync(User, values, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is null) ViewData["ConversionError"] = Error(result);
        else ViewData["Quote"] = result.Value;
        return View(values);
    }

    [HttpGet("Password")]
    public IActionResult Password() => View();

    [HttpPost("Password"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Password([FromForm] PersonalPassword values, CancellationToken ct)
    {
        var valid = ModelState.IsValid;
        try
        {
            if (!valid)
            {
                Response.StatusCode = 400;
                ViewData["PasswordError"] = "Enter your current password and a matching new password of 12–128 characters.";
                return View();
            }

            var result = await finance.ChangePasswordAsync(User, values, ct);
            if (await AccessFailure(result.Status) is { } denied) return denied;
            if (result.Status == 204)
            {
                await HttpContext.SignOutAsync(PersonalSession.Scheme);
                TempData["PersonalNotice"] = "Password changed and existing sessions signed out. Sign in with your new password.";
                return RedirectToAction(nameof(Login));
            }

            var error = Error(result);
            ViewData["PasswordError"] = result.Status == 400
                ? "Check your current password and choose a different new password that meets the requirements."
                : error;
            return View();
        }
        finally
        {
            values.CurrentPassword = values.NewPassword = values.ConfirmPassword = string.Empty;
            ModelState.Clear();
        }
    }

    [AllowAnonymous, HttpGet("Login")]
    public async Task<IActionResult> Login(string? returnUrl) =>
        (await ExistingSessionAsync()) ?? View(new LoginViewModel { ReturnUrl = PersonalReturnUrl(returnUrl) });

    [AllowAnonymous, HttpPost("Login"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Login([FromForm] LoginViewModel model, CancellationToken ct)
    {
        var password = model.Password;
        model.Password = string.Empty;
        ModelState.Remove(nameof(model.Password));
        var existing = await ExistingSessionAsync();
        if (existing is not null) return existing;

        var returnUrl = PersonalReturnUrl(model.ReturnUrl);
        ModelState.Remove(nameof(model.ReturnUrl));
        model.ReturnUrl = returnUrl;
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(password) || password.Length > 128)
        {
            model.Message = "Enter a valid email address and password.";
            return View(model);
        }

        var request = new LoginRequest { Email = model.Email, Password = password };
        ApiResult<AdminSession> result;
        try { result = await coordinator.LoginAsync(request, ct); }
        finally { request.Password = string.Empty; }

        if (!result.Succeeded)
        {
            model.Message = Message(result.Failure, "Unable to sign in with these credentials.");
            return View(model);
        }

        var session = result.Value!;
        try
        {
            await HttpContext.SignInAsync(PersonalSession.Scheme, PersonalSession.Principal(session),
                AuthenticationCoordinator.Properties(session));
        }
        catch
        {
            await coordinator.LogoutAsync(session.Id);
            throw;
        }
        return returnUrl is not null ? LocalRedirect(returnUrl) : RedirectToAction(nameof(Index));
    }

    [AllowAnonymous, HttpGet("Register")]
    public async Task<IActionResult> Register() =>
        (await ExistingSessionAsync(register: true)) ?? View(new RegisterRequest());

    [AllowAnonymous, HttpPost("Register"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Register([FromForm] RegisterRequest model, CancellationToken ct)
    {
        var valid = ModelState.IsValid;
        var existing = await ExistingSessionAsync(register: true);
        try
        {
            if (existing is not null) return existing;
            if (valid)
            {
                var result = await api.RegisterAsync(model, ct);
                if (result.Succeeded && result.Value is { } user && ContractValidation.RegularUser(user))
                {
                    TempData["PersonalNotice"] = "Account created. Sign in to continue.";
                    return RedirectToAction(nameof(Login));
                }
                ViewData["Message"] = Message(result.Failure,
                    "Check your name and email. Use 12–128 characters with uppercase, lowercase, a digit, a symbol, and at least four distinct characters.");
            }
            else ViewData["Message"] = "Check your account details and confirm that the two passwords match.";
            return View(model);
        }
        finally
        {
            model.Password = string.Empty;
            model.ConfirmPassword = string.Empty;
            ModelState.Remove(nameof(model.Password));
            ModelState.Remove(nameof(model.ConfirmPassword));
        }
    }

    [HttpPost("Logout"), ValidateAntiForgeryToken, PersonalLogout]
    public async Task<IActionResult> Logout()
    {
        try { await coordinator.LogoutAsync(User.FindFirstValue(AdminSessionStore.SessionClaim)); }
        finally { await HttpContext.SignOutAsync(PersonalSession.Scheme); }
        return RedirectToAction(nameof(Login));
    }

    [AllowAnonymous, HttpGet("AccessDenied")]
    public IActionResult AccessDenied()
    {
        Response.StatusCode = StatusCodes.Status403Forbidden;
        return View();
    }

    private async Task<IActionResult?> ExistingSessionAsync(bool register = false)
    {
        if ((await HttpContext.AuthenticateAsync(AdminSessionStore.Scheme)).Succeeded)
            return RedirectToAction(nameof(AccountController.SwitchPortal), "Account",
                new { destination = register ? nameof(Register) : nameof(Login) });
        var personal = await HttpContext.AuthenticateAsync(PersonalSession.Scheme);
        return personal.Succeeded ? RedirectToAction(nameof(Index)) : null;
    }

    private string? PersonalReturnUrl(string? url) =>
        Url.IsLocalUrl(url) && (string.Equals(url, "/Personal", StringComparison.OrdinalIgnoreCase)
            || string.Equals(url, "/Personal/Account", StringComparison.OrdinalIgnoreCase)) ? url : null;

    private static string Message(ApiFailure failure, string fallback) => failure switch
    {
        ApiFailure.Forbidden => "This account cannot sign in to the personal portal. Use the Administrator portal for an Administrator account.",
        ApiFailure.Conflict => "An account already exists for this email address. Sign in instead.",
        ApiFailure.RateLimited => "Too many requests. Wait a minute and try again.",
        ApiFailure.Unavailable or ApiFailure.InvalidResponse => "OWLI is temporarily unavailable. Try again shortly.",
        _ => fallback
    };
}
