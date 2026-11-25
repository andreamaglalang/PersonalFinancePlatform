using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

internal static class MemberDashboardAllTimeChecks
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static async Task RunAsync()
    {
        var rows = Enumerable.Range(1, 21).Select(id => Tx(id, id % 2 == 0 ? "Income" : "Expense",
            id, $"Category {id}", id * 10m, DateOnly.FromDateTime(new DateTime(2026, 11, 23).AddDays(-id)))).ToArray();
        var requests = new List<int>();
        var client = Client(request =>
        {
            CheckBearer(request);
            var page = QueryPage(request);
            requests.Add(page);
            var items = page == 1 ? rows.Take(20).ToArray() : rows.Skip(20).ToArray();
            return Ok(new TransactionPage(items, page, TransactionApiClient.PageSize, rows.Length));
        });
        var result = await client.ListAllAsync("safe-test-token", default);
        Check(result.Status == 200 && result.Value?.Count == 21 && requests.SequenceEqual(new[] { 1, 2 }),
            "All-time reads all pages and returns the complete transaction set.");
        var completeHistory = result.Value ?? throw new InvalidOperationException("Expected the verified complete history.");
        Check(completeHistory.First().TransactionId == 1 && completeHistory.Last().TransactionId == 21,
            "All-time ordering is deterministic after loading every page.");

        var changedTotal = Client(request => Ok(new TransactionPage(
            QueryPage(request) == 1 ? rows.Take(20).ToArray() : rows.Skip(20).ToArray(), QueryPage(request),
            TransactionApiClient.PageSize, QueryPage(request) == 1 ? 21 : 22)));
        Check((await changedTotal.ListAllAsync("token", default)).Status == 503,
            "A changing total during pagination is rejected rather than summarized inaccurately.");

        var duplicate = Client(request =>
        {
            var page = QueryPage(request);
            return Ok(new TransactionPage(page == 1 ? rows.Take(20).ToArray() : new[] { rows[0] }, page,
                TransactionApiClient.PageSize, 21));
        });
        Check((await duplicate.ListAllAsync("token", default)).Status == 503,
            "Duplicate transaction IDs across pages are rejected.");

        var incomplete = Client(request => Ok(new TransactionPage(rows.Take(19).ToArray(), 1,
            TransactionApiClient.PageSize, 21)));
        Check((await incomplete.ListAllAsync("token", default)).Status == 503,
            "A short non-final page is rejected.");

        var empty = Client(_ => Ok(new TransactionPage([], 1, TransactionApiClient.PageSize, 0)));
        var emptyResult = await empty.ListAllAsync("token", default);
        Check(emptyResult.Status == 200 && emptyResult.Value?.Count == 0,
            "A valid zero-record response is a successful empty history.");

        var unauthorized = new TransactionApiClient(new HttpClient(new Handler(_ =>
            new(HttpStatusCode.Unauthorized))) { BaseAddress = new Uri("https://api.test/") });
        Check((await unauthorized.ListAllAsync("token", default)).Status == 401,
            "Session expiry is propagated to the existing member session gate.");

        var aggregate = new MemberDashboardViewModel();
        var history = new[]
        {
            Tx(1, "Income", 1, "Salary", 30_000m, new(2026, 1, 1)),
            Tx(2, "Income", 1, "Salary", 5_000m, new(2026, 11, 1)),
            Tx(3, "Expense", 2, "Food", 200m, new(2026, 11, 2)),
            Tx(4, "Expense", 3, "Bills", 100m, new(2025, 8, 1)),
            Tx(5, "Expense", 2, "Food", 50m, new(2024, 4, 1))
        };
        aggregate.ApplyAllTime(history, "PHP", new Dictionary<int, string> { [2] = "#ff0000" });
        Check(aggregate.IsAllTime && aggregate.TotalIncome == 35_000m && aggregate.TotalExpenses == 350m
            && aggregate.NetCashFlow == 34_650m && aggregate.TransactionCount == 5,
            "All-time totals include the full history and compute net flow as income minus expenses.");
        Check(aggregate.Expenses.Count == 2 && aggregate.Expenses[0].CategoryName == "Food"
            && aggregate.Expenses[0].Total == 250m && aggregate.Expenses[0].TransactionCount == 2
            && aggregate.Expenses[0].Share == 250m / 350m * 100m,
            "All-time category totals, counts, and proportions use full-history values.");
        Check(aggregate.Recent.Count == 5 && aggregate.Recent[0].TransactionId == 3,
            "All-time recent activity is newest-first across the full history.");

        Console.WriteLine("PASS: all-time pagination safety, owner-token transport, session expiry, and full-history aggregation.");
    }

    private static TransactionApiClient Client(Func<HttpRequestMessage, HttpResponseMessage> response) =>
        new(new HttpClient(new Handler(response)) { BaseAddress = new Uri("https://api.test/") });

    private static HttpResponseMessage Ok<T>(T value) => new(HttpStatusCode.OK)
    {
        Content = JsonContent.Create(value, options: Json)
    };

    private static int QueryPage(HttpRequestMessage request)
    {
        var part = request.RequestUri!.Query.TrimStart('?').Split('&')
            .Single(item => item.StartsWith("Page=", StringComparison.Ordinal));
        return int.Parse(part.AsSpan("Page=".Length), System.Globalization.CultureInfo.InvariantCulture);
    }

    private static void CheckBearer(HttpRequestMessage request) =>
        Check(request.Headers.Authorization is { Scheme: "Bearer", Parameter: "safe-test-token" },
            "All-time pages must use the authenticated Member bearer token.");

    private static TransactionResponse Tx(int id, string type, int category, string name, decimal amount,
        DateOnly date) => new(id, Guid.NewGuid(), type, category, name, amount, "PHP", 1m, date, amount,
            date, null, new DateTime(2026, 11, 24, 0, 0, 0, DateTimeKind.Utc), null, null);

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> response) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(response(request));
    }
}
