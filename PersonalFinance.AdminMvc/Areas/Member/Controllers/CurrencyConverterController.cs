using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
public sealed class CurrencyConverterController(ExchangeRateApiClient quotes, AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public IActionResult Index([FromQuery] string? from = null, [FromQuery] string? to = null, [FromQuery] string? amount = null) => View(new MemberCurrencyConverterViewModel
    { From = MemberCurrencyPreferences.IsSupported(from ?? "") ? from! : "USD", To = MemberCurrencyPreferences.IsSupported(to ?? "") ? to! : "PHP", Amount = amount ?? "1" });

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Quote([FromForm] MemberCurrencyConverterViewModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == StatusCodes.Status401Unauthorized) return RedirectToAction("Login", "Account", new { area = "" });

        model.From = model.From?.ToUpperInvariant() ?? "";
        model.To = model.To?.ToUpperInvariant() ?? "";
        if (!MemberCurrencyPreferences.IsSupported(model.From)) ModelState.AddModelError(nameof(model.From), "Choose a currency supported by OWLI.");
        if (!MemberCurrencyPreferences.IsSupported(model.To)) ModelState.AddModelError(nameof(model.To), "Choose a currency supported by OWLI.");
        // Same exact reading as every amount field; never round (10.555 was sent as 10.56 and then reported as an outage).
        if (!string.IsNullOrWhiteSpace(model.Amount) && MoneyInput.Problem(model.Amount, 1_000_000_000m) is { } problem)
            ModelState.AddModelError(nameof(model.Amount), problem);
        MoneyInput.TryParse(model.Amount, out var amount);
        if (!ModelState.IsValid)
        {
            Response.StatusCode = StatusCodes.Status400BadRequest;
            model.Error = "Check the amount and currency selections.";
            return View(nameof(Index), model);
        }

        var result = await quotes.QuoteAsync(model.From, model.To, amount, token!, ct);
        if (result.Status == StatusCodes.Status401Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (result.Quote is not null) model.Quote = result.Quote;
        else
        {
            Response.StatusCode = result.Status is 400 or 429 ? result.Status : StatusCodes.Status503ServiceUnavailable;
            model.Error = result.Status switch
            {
                400 => "The selected currency pair or amount was rejected. Review your selections.",
                429 => "Too many exchange-rate requests. Wait a moment and try again.",
                _ => "The reference-rate service is unavailable. Try again later."
            };
        }
        return View(nameof(Index), model);
    }
}
