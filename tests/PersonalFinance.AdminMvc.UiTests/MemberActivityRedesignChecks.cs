using System.Net;
using System.Net.Http.Json;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class MemberActivityRedesignChecks
{
    public static async Task RunAsync()
    {
        var rows = Enumerable.Range(1, 20).Select(id => Tx(id, "Expense", 2, "Food & Dining", 10m, 20m,
                new DateOnly(2026, 11, 5), "Lunch"))
            .Append(Tx(21, "Expense", 2, "Food & Dining", 50m, 75m, new DateOnly(2026, 11, 6), "Special market"))
            .ToArray();
        var requests = new List<Uri>();
        var client = new TransactionApiClient(new HttpClient(new Handler(request =>
        {
            requests.Add(request.RequestUri!);
            var query = request.RequestUri!.Query;
            Check(query.Contains("Type=Expense") && query.Contains("CategoryId=2")
                && query.Contains("FromDate=2026-11-01") && query.Contains("ToDate=2026-11-30"),
                "Complete Activity reads retain server-side type, category, and month filters.");
            var page = query.Contains("Page=2", StringComparison.Ordinal) ? 2 : 1;
            var items = page == 1 ? rows.Take(20).ToArray() : rows.Skip(20).ToArray();
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(new TransactionPage(items, page, TransactionApiClient.PageSize, rows.Length))
            };
        })) { BaseAddress = new Uri("https://api.test/") });

        var complete = await client.ListAllAsync("safe-test-token", default, "Expense", 2,
            new DateOnly(2026, 11, 1), new DateOnly(2026, 11, 30));
        Check(complete.Succeeded && complete.Value?.Count == 21 && requests.Count == 2,
            "Activity pagination retrieves the complete matching API dataset before search, totals, and display pagination.");

        var filtered = ActivityProjection.Build(complete.Value!, "Expense", 2, new(2026, 11, 1),
            new(2026, 11, 30), "special", null, 1, TransactionApiClient.PageSize);
        Check(filtered.TotalCount == 1 && filtered.SummaryCount == 1 && filtered.Items.Single().TransactionId == 21,
            "Search reaches matching records on later API pages and returns a correct matching count.");
        Check(filtered.ExpenseTotal == 75m && filtered.IncomeTotal == 0m
            && filtered.DailyVolume[new DateOnly(2026, 11, 6)] == 75m,
            "Filtered totals and chart values use the API's PHP-normalized AmountBase across the complete dataset.");

        var all = ActivityProjection.Build(complete.Value!, null, null, new(2026, 11, 1), new(2026, 11, 30),
            null, null, 2, TransactionApiClient.PageSize);
        Check(all.TotalCount == 21 && all.Items.Count == 1 && all.Items[0].TransactionId == 1
            && all.ExpenseTotal == 475m && all.DailyVolume[new DateOnly(2026, 11, 5)] == 400m,
            "Activity summary is full-period while display pagination returns only the requested page.");

        var selectedDay = ActivityProjection.Build(complete.Value!, null, null, new(2026, 11, 1),
            new(2026, 11, 30), null, new(2026, 11, 5), 1, TransactionApiClient.PageSize);
        Check(selectedDay.TotalCount == 20 && selectedDay.SummaryCount == 21 && selectedDay.ExpenseTotal == 475m,
            "Selecting a chart day narrows the list without turning period totals into a partial-day subtotal.");

        var customRange = ActivityProjection.Build(complete.Value!, null, null, new(2026, 11, 6),
            new(2026, 11, 30), null, null, 1, TransactionApiClient.PageSize);
        Check(customRange.TotalCount == 1 && customRange.ExpenseTotal == 75m
            && customRange.DailyVolume.Count == 1,
            "Custom date ranges update matching results, full-range totals, and chart data.");
        var largest = ActivityProjection.Build(complete.Value!, null, null, new(2026, 11, 1),
            new(2026, 11, 30), null, null, 1, TransactionApiClient.PageSize, "Largest");
        Check(largest.Items[0].TransactionId == 21 && largest.ExpenseTotal == 475m,
            "Bounded-period amount ordering uses PHP-normalized amounts without changing period totals.");

        var amountSearch = Tx(30, "Expense", 3, "Other", 1250m, 1250m, new(2026, 11, 1), null);
        Check(ActivityProjection.MatchesSearch(amountSearch, "₱1,250")
            && ActivityProjection.MatchesSearch(amountSearch, "1,250.00")
            && !ActivityProjection.MatchesSearch(amountSearch, "999"),
            "Android-style amount search handles currency marks, separators, and exact unrelated terms.");

        var failed = new TransactionApiClient(new HttpClient(new Handler(_ =>
            new HttpResponseMessage(HttpStatusCode.ServiceUnavailable))) { BaseAddress = new Uri("https://api.test/") });
        Check((await failed.ListAllAsync("safe-test-token", default)).Status == 503,
            "An API failure remains a failure and cannot be interpreted as an empty Activity history.");

        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Index.cshtml"));
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));
        Check(view.Contains("var barHeight =", StringComparison.Ordinal)
            && view.Contains("height=\"@barHeight.ToString", StringComparison.Ordinal),
            "The daily volume chart binds scaled pixel heights directly to its bars.");
        foreach (var marker in new[] { "Everything you’ve recorded", "Daily transaction activity", "All time",
            "Expenses", "Search transactions", "Showing", "Protected bill payment", "No matching transactions",
            "name=\"FromDate\"", "name=\"ToDate\"", "Largest first", "Smallest first" })
            Check(view.Contains(marker, StringComparison.Ordinal), $"Activity view includes {marker}.");
        Check(controller.Contains("ListAllAsync", StringComparison.Ordinal)
            && controller.Contains("ActivityProjection.Build", StringComparison.Ordinal),
            "Activity controller obtains and projects the complete API history before pagination.");
        Check(view.Contains("<div class=\"activity-actions\">", StringComparison.Ordinal)
            && css.Contains(".activity-row-actions, .member-row-actions { display: table-cell", StringComparison.Ordinal),
            "Action cells remain table cells and align controls in an inner flex container.");
        Check(view.Contains("aria-current=\"page\"", StringComparison.Ordinal)
            && view.Contains("Page = pageNumber", StringComparison.Ordinal),
            "Pagination exposes the active page and preserves server query filters.");

        Console.WriteLine("PASS: Activity complete-history filters, summary math, day selection, amount search, failure state, action alignment, and pagination.");
    }

    private static TransactionResponse Tx(int id, string type, int category, string name, decimal amount,
        decimal amountBase, DateOnly date, string? description) => new(id, Guid.NewGuid(), type, category,
        name, amount, "PHP", 1m, date, amountBase, date, description,
        new DateTime(2026, 11, 24, 0, 0, 0, DateTimeKind.Utc), null, null);

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
