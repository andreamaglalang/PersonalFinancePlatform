using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member currency preferences. Browser-local and account-scoped: the choice lives in an encrypted cookie that records
/// the account which wrote it, so switching accounts never inherits another account's settings. No backend contract,
/// no database write and no device synchronisation.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(8 * 1024)]
public sealed class CurrencyController(AdminSessionStore sessions, IDataProtectionProvider protection) : Controller
{
    [HttpGet]
    public IActionResult Index()
    {
        var userId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        var account = MemberSessionGate.BaseCurrency(User, sessions);
        var preferences = MemberCurrencyPreferences.Read(HttpContext, protection, userId);
        return View(new MemberCurrencyPreferencesViewModel
        {
            AccountCurrency = account,
            DefaultCurrency = MemberCurrencyPreferences.Normalise(preferences.DefaultCurrency),
            ShowOriginalAmounts = preferences.ShowOriginalAmounts,
            ReviewRateBeforeSaving = preferences.ReviewRateBeforeSaving,
            Notice = TempData["CurrencyNotice"] as string
        });
    }

    [HttpPost, ActionName(nameof(Index)), ValidateAntiForgeryToken]
    public IActionResult Save([FromForm] MemberCurrencyPreferencesViewModel model)
    {
        var userId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        var account = MemberSessionGate.BaseCurrency(User, sessions);
        model.AccountCurrency = account;
        model.Currencies = MemberCurrencyPreferences.Supported;

        if (!MemberCurrencyPreferences.IsSupported(model.DefaultCurrency))
        {
            Response.StatusCode = 400;
            ModelState.AddModelError(nameof(model.DefaultCurrency), "Choose one of the supported currencies.");
            return View(nameof(Index), model);
        }
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View(nameof(Index), model);
        }

        MemberCurrencyPreferences.Write(HttpContext, protection, userId, new(
            model.DefaultCurrency, model.ShowOriginalAmounts, model.ReviewRateBeforeSaving));
        TempData["CurrencyNotice"] = "Currency preferences saved on this browser.";
        return RedirectToAction(nameof(Index));
    }
}
