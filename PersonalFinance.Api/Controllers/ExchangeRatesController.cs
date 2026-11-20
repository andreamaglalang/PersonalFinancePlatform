using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

/// <summary>Read-only exchange-rate quotes for signed-in users. Never creates or changes transactions.</summary>
[ApiController, Authorize, Route("api/exchange-rates")]
public sealed class ExchangeRatesController(IExchangeRateQuoteService quotes) : ControllerBase
{
    /// <summary>GET api/exchange-rates/quote?from=USD&amp;to=PHP&amp;amount=100.00[&amp;date=2026-11-20]</summary>
    [HttpGet("quote"), ProducesResponseType<ExchangeRateQuoteResponse>(200)]
    public async Task<IActionResult> Quote([FromQuery] ExchangeRateQuoteQuery query, CancellationToken ct)
    {
        var result = await quotes.QuoteAsync(query, ct);
        return result.Succeeded ? Ok(result.Value) : Problem(statusCode: result.StatusCode, title: result.Error);
    }
}
