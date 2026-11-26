using System.Net;
using System.Text;
using PersonalFinance.AdminMvc.Services;

/// <summary>R8: MVC consumes the C4 stored monthly snapshots.</summary>
internal static class MemberSummaryChecks
{
    public static async Task RunAsync()
    {
        string? path = null;
        async Task<MonthlySummaryResult> Call(string body, HttpStatusCode status = HttpStatusCode.OK)
        {
            var handler = new Handler(request => { path = request.RequestUri!.PathAndQuery; return new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") }; });
            return await new ReportsApiClient(new HttpClient(handler) { BaseAddress = new Uri("https://api.example.test/") }).GetSummariesAsync("t", default);
        }
        var ok = await Call("""{"items":[{"summaryMonth":"2026-10-01","totalIncome":200,"totalExpense":50.25,"netCashFlow":149.75,"savingsAllocated":0,"generatedAtUtc":"2026-11-01T00:30:00Z","baseCurrency":"PHP"}],"page":1,"pageSize":12,"totalCount":1}""");
        Check(ok.Status == 200 && ok.Page!.Items.Single().NetCashFlow == 149.75m && path == "/api/reports/summaries?page=1&pageSize=12", "C4 summaries are read from the API contract.");
        var inconsistent = await Call("""{"items":[{"summaryMonth":"2026-10-01","totalIncome":200,"totalExpense":50,"netCashFlow":1,"savingsAllocated":0,"generatedAtUtc":"2026-11-01T00:30:00Z","baseCurrency":"PHP"}],"page":1,"pageSize":12,"totalCount":1}""");
        Check(inconsistent.Status == 503, "Inconsistent snapshot totals are rejected, never displayed.");
        Check((await Call("{}", HttpStatusCode.Unauthorized)).Status == 401, "401 is surfaced so the session can be rejected.");
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Reports", "Index.cshtml"));
        Check(view.Contains("Monthly snapshots") && view.Contains("No monthly snapshots yet") && view.Contains("Model.SummariesError")
            && view.Contains("<caption class=\"visually-hidden\">Saved monthly snapshots"), "Snapshot section has empty, error and accessible table states.");
        Check(view.Contains("@Money(report.SavingsContributed, report.BaseCurrency)</strong> saved to goals"),
            "Parity with Android Insights: the monthly overview shows savings contributed (M2).");
        Console.WriteLine("PASS: Reports shows stored monthly snapshots (C4) with validation, empty and error states.");
    }

    private static void Check(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => Task.FromResult(respond(request));
    }
}
