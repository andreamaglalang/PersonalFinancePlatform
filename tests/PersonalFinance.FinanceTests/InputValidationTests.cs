using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.FinanceTests;

/// <summary>Input-validation audit regressions: manipulated requests are rejected plainly and change nothing.</summary>
internal static class InputValidationTests
{
    private static void Assert(bool condition, string detail = "Assertion failed") { if (!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
    private static string[] Errors(ApiReply r, string field) =>
        Json(r).GetProperty("errors").EnumerateObject().Where(p => p.Name.Equals(field, StringComparison.OrdinalIgnoreCase))
            .SelectMany(p => p.Value.EnumerateArray().Select(v => v.GetString()!)).ToArray();
    private static int Count(FinanceFixture f, Func<ApplicationDbContext, int> count)
    { using var scope = f.Services.CreateScope(); return count(scope.ServiceProvider.GetRequiredService<ApplicationDbContext>()); }
    private static object Bill(string frequency = "Monthly", string currency = "PHP", string name = "Internet") =>
        new { name, categoryId = 2, amount = 999m, currencyCode = currency, reminderDaysBefore = 3, frequency, firstDueDate = "2026-11-10" };
    private static string Tx(string amountJson) =>
        $$"""{"clientId":"{{Guid.NewGuid()}}","type":"Expense","categoryId":2,"amount":{{amountJson}},"currencyCode":"PHP","transactionDate":"2026-10-10"}""";

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Bill frequency must be a name: numeric strings are rejected and nothing is stored", async f =>
        {
            foreach (var frequency in new[] { "1", "0", "4", " Monthly" })
            {
                var r = await f.SendAsync("POST", "/api/recurring-bills", Bill(frequency), f.Issue(1));
                Assert(r.Status == 400 && r.Body.Contains("Frequency must be Weekly, Monthly, Quarterly or Yearly."), frequency + ": " + r.Body);
            }
            Assert(Count(f, db => db.RecurringBills.Count()) == 0, "a rejected bill was stored");
            Assert((await f.SendAsync("POST", "/api/recurring-bills", Bill("Quarterly"), f.Issue(1))).Status == 201);
        });

        await check("Bill currency must be offered by the rate provider on create and on change", async f =>
        {
            var r = await f.SendAsync("POST", "/api/recurring-bills", Bill(currency: "ZZZ"), f.Issue(1));
            Assert(r.Status == 400 && r.Body.Contains("The currency is not supported by the exchange-rate provider."), r.Body);
            Assert(Count(f, db => db.RecurringBills.Count()) == 0, "an unpayable bill was stored");
            var usd = await f.SendAsync("POST", "/api/recurring-bills", Bill(currency: "USD"), f.Issue(1));
            Assert(usd.Status == 201, usd.Body);
            var id = Json(usd).GetProperty("billId").GetInt32();
            var change = await f.SendAsync("PUT", $"/api/recurring-bills/{id}",
                new { name = "Internet", categoryId = 2, amount = 999m, currencyCode = "ZZZ", reminderDaysBefore = 3 }, f.Issue(1));
            Assert(change.Status == 400, change.Body);
            Assert(Count(f, db => db.RecurringBills.Count(b => b.CurrencyCode == "USD")) == 1, "the rejected edit changed the bill");
        });

        await check("Names reject line breaks and control characters; trimmed padding and Unicode stay valid", async f =>
        {
            var cases = new (string Path, Func<string, object> Body, string Field, string Message)[]
            {
                ("/api/categories", n => new { name = n, type = "Expense" }, "Name", "Category names can't contain line breaks or control characters."),
                ("/api/savings-goals", n => new { name = n, targetAmount = 100m }, "Name", "Goal names can't contain line breaks or control characters."),
                ("/api/recurring-bills", n => Bill(name: n), "Name", "Bill names can't contain line breaks or control characters."),
            };
            var seeded = Count(f, db => db.Categories.Count(c => c.UserId == 1));
            foreach (var (path, body, field, message) in cases)
            {
                foreach (var bad in new[] { "Two\nLines", "Bell\u0007", "Tab\tInside", "Nul\0" })
                {
                    var r = await f.SendAsync("POST", path, body(bad), f.Issue(1));
                    Assert(r.Status == 400 && Errors(r, field).SequenceEqual([message]), $"{path} {JsonSerializer.Serialize(bad)}: {r.Body}");
                }
                var ok = await f.SendAsync("POST", path, body("  Café Ñandú-O'Hara\n"), f.Issue(1));
                Assert(ok.Status == 201 && Json(ok).GetProperty("name").GetString() == "Café Ñandú-O'Hara", path + ": " + ok.Body);
            }
            Assert(Count(f, db => db.Categories.Count(c => c.UserId == 1)) == seeded + 1 && Count(f, db => db.SavingsGoals.Count()) == 1
                && Count(f, db => db.RecurringBills.Count()) == 1, $"a rejected name was stored: categories {Count(f, db => db.Categories.Count(c => c.UserId == 1))}, goals {Count(f, db => db.SavingsGoals.Count())}, bills {Count(f, db => db.RecurringBills.Count())}");
            var named = await f.SendAsync("PUT", "/api/auth/me", new { fullName = "A\u0000B" }, f.Issue(1));
            Assert(named.Status == 400, "PUT /api/auth/me accepted a control character: " + named.Body);
        });

        await check("Malformed JSON values get plain per-field messages without serializer or type internals", async f =>
        {
            foreach (var (json, field) in new[] { ("\"abc\"", "amount"), ("1e400", "amount"), ("NaN", "amount") })
            {
                var r = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1), rawJson: Tx(json));
                Assert(r.Status == 400, r.Body);
                Assert(Errors(r, field).SequenceEqual([SafeValidationResponses.UnreadableValue]) || Errors(r, "body").Length == 1, json + ": " + r.Body);
                foreach (var leak in new[] { "System.", "PersonalFinance.", "BytePosition", "LineNumber", "request field" })
                    Assert(!r.Body.Contains(leak, StringComparison.Ordinal), $"{json} leaked {leak}: {r.Body}");
            }
            var dates = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1),
                rawJson: Tx("1").Replace("2026-10-10", "2026-02-30"));
            Assert(Errors(dates, "transactionDate").SequenceEqual([SafeValidationResponses.UnreadableValue]), dates.Body);
            var extra = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1), rawJson: Tx("1").Replace("{", "{\"userId\":2,"));
            Assert(extra.Status == 400 && Errors(extra, "userId").Length == 1 && !extra.Body.Contains("PersonalFinance."), extra.Body);
            var broken = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1), rawJson: "{\"amount\":");
            Assert(broken.Status == 400 && !broken.Body.Contains("System.") && !broken.Body.Contains("BytePosition"), broken.Body);
            Assert(Count(f, db => db.Transactions.Count()) == 0, "a malformed request stored a transaction");
        });

        await check("Validation messages never show regular expressions or framework field wording", async f =>
        {
            var type = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1), rawJson: Tx("1").Replace("\"Expense\"", "\"Transfer\""));
            Assert(Errors(type, "Type").SequenceEqual(["Choose Income or Expense."]), type.Body);
            var currency = await f.SendAsync("POST", "/api/transactions", bearer: f.Issue(1), rawJson: Tx("1").Replace("\"PHP\"", "\"php\""));
            Assert(Errors(currency, "CurrencyCode").SequenceEqual(["Use a three-letter uppercase currency code, for example PHP."]), currency.Body);
            var colour = await f.SendAsync("POST", "/api/categories", new { name = "Colour", type = "Expense", colorHEX = "red" }, f.Issue(1));
            var threshold = await f.SendAsync("POST", "/api/budgets", new { categoryId = 2, periodMonth = "2031-05-01", limitAmount = 10m, alertThresholdPct = 101 }, f.Issue(1));
            Assert(Errors(threshold, "AlertThresholdPct").SequenceEqual(["Enter an alert threshold from 1 to 100."]), threshold.Body);
            foreach (var r in new[] { type, currency, colour, threshold })
                Assert(r.Status == 400 && !r.Body.Contains("regular expression") && !r.Body.Contains("The field "), r.Body);
        });

        await check("A request body over the endpoint limit is a client error (413), not a server error", async f =>
        {
            var context = new DefaultHttpContext { RequestServices = f.Services };
            context.Response.Body = new MemoryStream();
            var handled = await new SafeExceptionHandler(NullLogger<SafeExceptionHandler>.Instance)
                .TryHandleAsync(context, new BadHttpRequestException("Request body too large.", StatusCodes.Status413PayloadTooLarge), default);
            context.Response.Body.Position = 0;
            var body = await new StreamReader(context.Response.Body).ReadToEndAsync();
            Assert(handled && context.Response.StatusCode == 413 && body.Contains("The request is too large.") && !body.Contains("Request body too large"), body);
        });
    }
}
