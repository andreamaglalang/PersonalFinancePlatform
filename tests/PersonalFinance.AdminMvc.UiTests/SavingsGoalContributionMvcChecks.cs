using System.Net;
using System.Text;
using PersonalFinance.AdminMvc.Services;

internal static class SavingsGoalContributionMvcChecks
{
    private const string Version = "AQIDBAUGBwg=";
    private const string Goal = """{"goalId":1,"name":"Trip","targetAmount":100,"targetDate":null,"status":"Active","savedAmount":5,"remainingAmount":95,"surplusAmount":0,"progressPercentage":5,"baseCurrency":"PHP","version":"AQIDBAUGBwg="}""";
    private const string Contribution = """{"contributionId":6,"goalId":1,"amount":5,"contributedOn":"2026-11-24","note":"fixture","baseCurrency":"PHP","version":"AQIDBAUGBwg="}""";

    public static async Task RunAsync()
    {
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "SavingsGoalsController.cs"));
        var detail = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Details.cshtml"));
        Check(controller.Contains("ListContributionsAsync") && controller.Contains("UpdateContributionAsync")
            && controller.Contains("DeleteContributionAsync") && controller.Contains("DeleteAsync(id"),
            "Member savings goals must expose contribution history, supported contribution corrections and eligible goal deletion.");
        Check(controller.Contains("ViewData[\"GoalId\"] = id") && controller.Contains("nameof(Create)")
            && controller.Contains("WriteError(result.Status, model, nameof(Create)"),
            "Goal edit validation and API errors must return the existing edit-aware form.");
        Check(detail.Contains("Contribution history") && detail.Contains("asp-action=\"EditContribution\"")
            && detail.Contains("asp-action=\"DeleteContribution\""), "Goal detail view exposes protected contribution history actions.");

        using var handler = new GoalHandler();
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://api.example.test/") };
        var client = new SavingsGoalApiClient(http);
        Check((await client.ListContributionsAsync(1, 1, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/savings-goals/1/contributions?Page=1&PageSize=20" && handler.LastMethod == HttpMethod.Get,
            "Contribution history uses its authenticated paged GET route.");
        Check((await client.GetContributionAsync(1, 6, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/savings-goals/1/contributions/6", "Contribution detail is owner-scoped to the parent goal.");
        Check((await client.UpdateContributionAsync(1, 6, new(6m, new DateOnly(2026, 11, 24), "fixture"), Version, "fixture-token", default)).Succeeded
            && handler.LastMethod == HttpMethod.Put && handler.LastVersion == $"\"{Version}\"",
            "Contribution updates use the resource row version as If-Match.");
        Check((await client.DeleteContributionAsync(1, 6, Version, "fixture-token", default)).Succeeded
            && handler.LastMethod == HttpMethod.Delete && handler.LastVersion == $"\"{Version}\"",
            "Contribution deletion uses the resource row version as If-Match.");
        Check((await client.DeleteAsync(1, Version, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/savings-goals/1" && handler.LastMethod == HttpMethod.Delete
            && handler.LastVersion == $"\"{Version}\"", "Goal deletion uses the server concurrency precondition.");
        Console.WriteLine("PASS: Savings Goal contribution history/correction/deletion and row-version protections.");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class GoalHandler : HttpMessageHandler
    {
        public HttpMethod? LastMethod { get; private set; }
        public string? LastPath { get; private set; }
        public string? LastVersion { get; private set; }
        private int _calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            LastMethod = request.Method;
            LastPath = request.RequestUri!.PathAndQuery;
            LastVersion = request.Headers.IfMatch.SingleOrDefault()?.Tag;
            _calls++;
            if (request.Method == HttpMethod.Delete && _calls == 5)
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NoContent));
            var response = request.Method == HttpMethod.Get && LastPath.EndsWith("?Page=1&PageSize=20", StringComparison.Ordinal)
                ? """{"items":[""" + Contribution + """],"page":1,"pageSize":20,"totalCount":1}"""
                : LastPath.EndsWith("/contributions/6", StringComparison.Ordinal) && request.Method == HttpMethod.Get
                    ? Contribution
                    : request.Method == HttpMethod.Put
                        ? """{"contribution":{"contributionId":6,"goalId":1,"amount":6,"contributedOn":"2026-11-24","note":"fixture","baseCurrency":"PHP","version":"AQIDBAUGBwg="},"goal": """ + Goal + "}" 
                        : LastPath.EndsWith("/contributions/6", StringComparison.Ordinal)
                            ? """{"contributionId":6,"deleted":true,"goal": """ + Goal + "}"
                            : string.Empty;
            return Task.FromResult(new HttpResponseMessage(request.Method == HttpMethod.Post ? HttpStatusCode.Created : HttpStatusCode.OK)
            {
                Content = new StringContent(response, Encoding.UTF8, "application/json")
            });
        }
    }
}
