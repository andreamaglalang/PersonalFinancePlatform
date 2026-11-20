using System.Text.Json;
using Microsoft.EntityFrameworkCore;

namespace PersonalFinance.FinanceTests;

/// <summary>Read-only exchange-rate quote endpoint (in-memory EF, real JWT/MVC pipeline, fake provider).</summary>
internal static class ExchangeRateQuoteTests
{
    private static void Assert(bool condition, string detail = "Assertion failed") { if (!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
    private static Task<ApiReply> Quote(FinanceFixture f, string query, int? user = 1, string scheme = "https") =>
        f.SendAsync("GET", "/api/exchange-rates/quote?" + query, bearer: user is int id ? f.Issue(id) : null, scheme: scheme);

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("FX quote uses the transaction conversion policy and saves nothing", async f =>
        {
            var before = 0;
            await f.DbAsync(async db => before = await db.Transactions.CountAsync());
            var r = await Quote(f, "from=USD&to=PHP&amount=10.25");
            Assert(r.Status == 200 && r.CacheControl == "no-store", r.Body);
            var j = Json(r);
            // Provider rate 56.123456785 -> 8 decimals away from zero; amount × rate -> 2 decimals away from zero.
            Assert(j.GetProperty("rate").GetDecimal() == 56.12345679m, j.GetProperty("rate").ToString());
            Assert(j.GetProperty("convertedAmount").GetDecimal() == 575.27m, j.GetProperty("convertedAmount").ToString());
            Assert(j.GetProperty("amount").GetDecimal() == 10.25m && j.GetProperty("from").GetString() == "USD" && j.GetProperty("to").GetString() == "PHP");
            Assert(j.GetProperty("source").GetString() == "Frankfurter");
            Assert(j.GetProperty("rateDate").GetString() == j.GetProperty("requestedDate").GetString());
            Assert(j.TryGetProperty("retrievedAtUtc", out _));
            await f.DbAsync(async db => Assert(await db.Transactions.CountAsync() == before, "a quote must never create a transaction"));
        });
        await check("FX quote reverse direction and cache reuse", async f =>
        {
            Assert((await Quote(f, "from=PHP&to=USD&amount=100")).Status == 200);
            var calls = f.Fx.Calls;
            Assert((await Quote(f, "from=PHP&to=USD&amount=250.50")).Status == 200);
            Assert(f.Fx.Calls == calls, "the cached rate for the same pair and date is reused");
        });
        await check("FX quote same currency uses rate 1 without the provider", async f =>
        {
            var r = await Quote(f, "from=PHP&to=PHP&amount=99.99");
            Assert(r.Status == 200, r.Body);
            var j = Json(r);
            Assert(j.GetProperty("rate").GetDecimal() == 1m && j.GetProperty("convertedAmount").GetDecimal() == 99.99m);
            Assert(j.GetProperty("source").GetString() == "Same currency" && f.Fx.Calls == 0);
        });
        await check("FX quote rejects invalid input", async f =>
        {
            foreach (var q in new[] { "from=usd&to=PHP&amount=1", "from=USD&to=PHP", "from=USD&to=PHP&amount=1.234", "from=USD&to=PHP&amount=0",
                "from=USD&to=PHP&amount=-5", "to=PHP&amount=1", "from=USD&to=PHP&amount=1&date=2099-01-01", "from=USD&to=PHP&amount=1&date=1990-01-01",
                "from=GBP&to=PHP&amount=1" })
            {
                var r = await Quote(f, q);
                Assert(r.Status == 400, q + " -> " + r.Status);
                Assert(!r.Body.Contains("SYNTHETIC", StringComparison.Ordinal));
            }
        });
        await check("FX quote reports provider outages and stale rates as unavailable", async f =>
        {
            f.Fx.Unavailable = true;
            Assert((await Quote(f, "from=USD&to=PHP&amount=1")).Status == 503);
            f.Fx.Unavailable = false; f.Fx.Age = 8; // older than the 7-day policy
            Assert((await Quote(f, "from=EUR&to=PHP&amount=1")).Status == 503);
        });
        await check("FX quote requires authentication and HTTPS", async f =>
        {
            Assert((await Quote(f, "from=USD&to=PHP&amount=1", user: null)).Status == 401);
            Assert((await Quote(f, "from=USD&to=PHP&amount=1", scheme: "http")).Status == 400);
            Assert(f.Fx.Calls == 0, "rejected requests never reach the provider");
        });
    }
}
