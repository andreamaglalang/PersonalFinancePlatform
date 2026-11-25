using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace PersonalFinance.AdminMvc.Services;

public sealed record ExchangeRateQuoteResponse(string From, string To, decimal Amount, decimal Rate,
    DateOnly RateDate, DateOnly RequestedDate, decimal ConvertedAmount, string Source, DateTime RetrievedAtUtc);

public sealed record ExchangeRateResult(int Status, ExchangeRateQuoteResponse? Quote = null);

/// <summary>Read-only GET api/exchange-rates/quote. Never creates or changes a transaction.</summary>
public sealed class ExchangeRateApiClient(HttpClient http)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<ExchangeRateResult> QuoteAsync(string from, string to, decimal amount, string bearer,
        CancellationToken ct) => QuoteAsync(from, to, amount, bearer, ct, null);

    /// <summary>Optional <paramref name="date"/> requests the published rate for that date.</summary>
    public async Task<ExchangeRateResult> QuoteAsync(string from, string to, decimal amount, string bearer,
        CancellationToken ct, DateOnly? date)
    {
        if (!ValidCode(from) || !ValidCode(to) || amount <= 0) return new(400);
        var route = string.Create(CultureInfo.InvariantCulture,
            $"api/exchange-rates/quote?from={from}&to={to}&amount={amount.ToString("0.00", CultureInfo.InvariantCulture)}");
        if (date is { } value) route += "&date=" + value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 429) return new(status);
            if (status != 200 || response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var quote = await response.Content.ReadFromJsonAsync<ExchangeRateQuoteResponse>(Json, ct);
            return Valid(quote, from, to, amount) ? new(200, quote) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return new(503);
        }
    }

    internal static bool ValidCode(string? code) =>
        code is { Length: 3 } && code.All(c => c is >= 'A' and <= 'Z');

    private static bool Valid(ExchangeRateQuoteResponse? quote, string from, string to, decimal amount) =>
        quote is { Rate: > 0, ConvertedAmount: >= 0, RequestedDate.Year: > 1 }
        && quote.From == from && quote.To == to
        && quote.Amount == amount
        && !string.IsNullOrWhiteSpace(quote.Source)
        && ValidCode(quote.From) && ValidCode(quote.To);
}
