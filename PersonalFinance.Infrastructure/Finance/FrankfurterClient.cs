using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance;

/// <summary>Fixed HTTPS provider only; no caller-controlled endpoint or guessed rates.</summary>
public sealed class FrankfurterClient(HttpClient http, TimeProvider clock) : IFrankfurterClient, IDisposable
{
    private readonly SemaphoreSlim catalogGate = new(1, 1);
    private HashSet<string>? currencies;
    private DateTimeOffset catalogExpires;

    public async Task<bool> SupportsAsync(string currency, CancellationToken ct)
    {
        await catalogGate.WaitAsync(ct);
        try
        {
            if (currencies is null || catalogExpires <= clock.GetUtcNow())
            {
                var rows = await GetAsync<CurrencyItem[]>("currencies", ct);
                if (rows.Any(r => r is null)) throw Unavailable();
                var codes = rows.Select(r => r.Code).ToArray();
                if (codes.Length == 0 || codes.Any(c => c is null || !FinanceValidation.CurrencyCode(c)))
                    throw Unavailable();
                currencies = codes.ToHashSet(StringComparer.Ordinal)!;
                catalogExpires = clock.GetUtcNow().AddHours(1);
            }
            return currencies.Contains(currency);
        }
        finally { catalogGate.Release(); }
    }

    public async Task<FxQuote> GetRateAsync(string source, string target, DateOnly date, CancellationToken ct)
    {
        if (!FinanceValidation.CurrencyCode(source) || !FinanceValidation.CurrencyCode(target)) throw Unavailable();
        var row = await GetAsync<RateItem>($"rate/{source}/{target}?date={date.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)}", ct);
        if (row.Base != source || row.Quote != target || row.Date is null || row.Rate is null)
            throw Unavailable();
        return new FxQuote(row.Base, row.Quote, row.Date.Value, row.Rate.Value);
    }

    private async Task<T> GetAsync<T>(string path, CancellationToken ct)
    {
        // Initial request plus at most three retries. No retry for 400/404/422 or bad JSON.
        for (var attempt = 0; attempt <= 3; attempt++)
        {
            try
            {
                using var response = await http.GetAsync(path, ct);
                if (response.StatusCode != HttpStatusCode.OK)
                {
                    if (response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.NotFound or HttpStatusCode.UnprocessableEntity)
                        throw new FinanceException(400, "The requested currency or historical rate is unavailable from the provider.");
                    if ((int)response.StatusCode >= 500 && attempt < 3)
                    {
                        await Task.Delay(TimeSpan.FromMilliseconds(100 * (1 << attempt)), ct);
                        continue;
                    }
                    throw Unavailable();
                }
                return await response.Content.ReadFromJsonAsync<T>(cancellationToken: ct) ?? throw Unavailable();
            }
            catch (HttpRequestException) when (attempt < 3)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(100 * (1 << attempt)), ct);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested && attempt < 3)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(100 * (1 << attempt)), ct);
            }
            catch (HttpRequestException) { throw Unavailable(); }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw Unavailable(); }
            catch (JsonException) { throw Unavailable(); }
        }
        throw Unavailable();
    }

    private static FinanceException Unavailable() => new(503, "Exchange-rate data is temporarily unavailable or not applicable.");
    public void Dispose() { http.Dispose(); catalogGate.Dispose(); }

    private sealed class CurrencyItem
    {
        [JsonPropertyName("iso_code")] public string Code { get; set; } = string.Empty;
    }
    private sealed class RateItem
    {
        public string Base { get; set; } = string.Empty;
        public string Quote { get; set; } = string.Empty;
        public DateOnly? Date { get; set; }
        public decimal? Rate { get; set; }
    }
}

