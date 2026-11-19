using System.Net;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

internal static class ReportsChecks
{
    private const string EmptyReport = """
        {"month":"2026-11-01","baseCurrency":"PHP","totalIncome":0,"totalExpenses":0,"netCashFlow":0,
         "transactionCount":0,"savingsContributed":0,"expenseByCategory":[],"incomeByCategory":[],"budgets":[]}
        """;

    public static async Task RunAsync()
    {
        using var handler = new FixtureHandler();
        using var http = new HttpClient(handler) { BaseAddress = new("https://localhost:7027") };
        using var sessions = new AdminSessionStore();
        var authentication = new SignOutFixture();
        using var services = new ServiceCollection()
            .AddSingleton<IAuthenticationService>(authentication)
            .AddSingleton<IUrlHelperFactory, UrlHelperFactory>()
            .BuildServiceProvider();
        var context = new DefaultHttpContext { RequestServices = services };
        var user = new CurrentUserResponse(1, "Fixture", "fixture@example.test", "PHP", ["Administrator"]);
        var session = sessions.Create(new AuthenticationResponse
        {
            AccessToken = "fixture-only",
            AccessTokenExpiresAtUtc = DateTime.UtcNow.AddMinutes(5),
            RefreshToken = new string('x', 86),
            RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddHours(1),
            User = user
        }, user)!;
        context.User = AuthenticationCoordinator.Principal(session);
        var controller = new ReportsController(new ReportsApiClient(http), sessions)
        {
            ControllerContext = new(new ActionContext(context, new RouteData(), new ControllerActionDescriptor())),
            TempData = new TempDataDictionary(context, new TempDataFixture())
        };

        foreach (var badMonth in new[] { "1999-12", "9999-01", "2026-13", "2026-1", "2026-11-01" })
        {
            var model = Model(await controller.Index(badMonth, default));
            Check(context.Response.StatusCode == 400 && model.MonthError is not null, "Invalid month rejected locally.");
        }
        Check(handler.Calls == 0, "Invalid month never calls the API.");

        context.Response.StatusCode = 200;
        var empty = Model(await controller.Index("2026-11", default));
        Check(handler.RequestPath == "/api/reports/monthly?month=2026-11-01"
            && handler.Authorization == "Bearer fixture-only", "Exact month and server-side bearer on the GET.");
        Check(empty.Report is { TransactionCount: 0, SavingsContributed: 0, BaseCurrency: "PHP", Budgets.Count: 0,
            IncomeByCategory.Count: 0, ExpenseByCategory.Count: 0 } && empty.ReportError is null, "Zero-activity response accepted.");
        handler.Payload = EmptyReport.Replace("\"budgets\":[]", """
            "budgets":[{"budgetId":1,"categoryId":2,"categoryName":"Transport","isCategoryActive":false,
            "periodMonth":"2026-11-01","limitAmount":100,"alertThresholdPct":80,"spentAmount":120,
            "remainingAmount":-20,"status":"Exceeded","baseCurrency":"PHP"}]
            """);
        Check(Model(await controller.Index("2026-11", default)).Report?.Budgets.Single().Status == "Exceeded",
            "Budget remains visible in a month with zero transactions and its status comes from the API.");

        foreach (var payload in new[] { "{}", "{", EmptyReport.Replace("2026-11-01", "2026-10-01"),
            EmptyReport.Replace("\"expenseByCategory\":[]", "\"expenseByCategory\":[null]") })
        {
            handler.Payload = payload;
            Check(Model(await controller.Index("2026-11", default)) is { Report: null, ReportError: not null }
                && context.Response.StatusCode == 503, "Malformed or mismatched report rejected.");
        }
        foreach (var (api, mvc) in new[] { (400, 400), (404, 503), (429, 429), (500, 503) })
        {
            handler.Status = (HttpStatusCode)api;
            Check(Model(await controller.Index("2026-11", default)) is { Report: null, ReportError: not null }
                && context.Response.StatusCode == mvc, "API error renders a safe report state.");
        }
        handler.FailNetwork = true;
        Check(Model(await controller.Index("2026-11", default)).ReportError is not null
            && context.Response.StatusCode == 503, "Network failure renders an unavailable state.");
        handler.FailNetwork = false;

        context.User = new ClaimsPrincipal(new ClaimsIdentity([
            new Claim(AdminSessionStore.SessionClaim, session.Id), new Claim(ClaimTypes.NameIdentifier, "1"),
            new Claim(ClaimTypes.Role, "User")], AdminSessionStore.Scheme));
        var count = handler.Calls;
        Check(await controller.Index("2026-11", default) is ForbidResult && handler.Calls == count,
            "A non-Administrator principal cannot load a report using a session identifier.");
        context.User = AuthenticationCoordinator.Principal(session);
        handler.Status = HttpStatusCode.Forbidden;
        Check(await controller.Index("2026-11", default) is ForbidResult, "API 403 routes to access denied.");
        handler.Status = HttpStatusCode.Unauthorized;
        Check(await controller.Index("2026-11", default) is RedirectToActionResult { ActionName: "Login", ControllerName: "Account" }
            && authentication.SignedOut && sessions.Find(session.Id) is null, "API 401 clears the session and redirects to sign-in.");
        Console.WriteLine("PASS: Reports date conversion, authorization, empty and budget-only results, and malformed, HTTP and network failure handling.");
    }

    private static MonthlyReportsViewModel Model(IActionResult result) => (MonthlyReportsViewModel)((ViewResult)result).Model!;

    private static void Check(bool condition, string error)
    {
        if (!condition) throw new InvalidOperationException(error);
    }

    private sealed class FixtureHandler : HttpMessageHandler
    {
        public int Calls { get; private set; }
        public string? RequestPath { get; private set; }
        public string? Authorization { get; private set; }
        public string Payload { get; set; } = EmptyReport;
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;
        public bool FailNetwork { get; set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Calls++;
            Check(request.Method == HttpMethod.Get, "Report request must be GET.");
            RequestPath = request.RequestUri!.PathAndQuery;
            Authorization = request.Headers.Authorization?.ToString();
            if (FailNetwork) throw new HttpRequestException();
            return Task.FromResult(new HttpResponseMessage(Status)
            {
                Content = new StringContent(Payload, Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class SignOutFixture : IAuthenticationService
    {
        public bool SignedOut { get; private set; }
        public Task SignOutAsync(HttpContext context, string? scheme, AuthenticationProperties? properties)
        {
            SignedOut = scheme == AdminSessionStore.Scheme;
            return Task.CompletedTask;
        }

        public Task<AuthenticateResult> AuthenticateAsync(HttpContext context, string? scheme) => throw new NotSupportedException();
        public Task ChallengeAsync(HttpContext context, string? scheme, AuthenticationProperties? properties) => throw new NotSupportedException();
        public Task ForbidAsync(HttpContext context, string? scheme, AuthenticationProperties? properties) => throw new NotSupportedException();
        public Task SignInAsync(HttpContext context, string? scheme, ClaimsPrincipal principal, AuthenticationProperties? properties) =>
            throw new NotSupportedException();
    }

    private sealed class TempDataFixture : ITempDataProvider
    {
        public IDictionary<string, object> LoadTempData(HttpContext context) => new Dictionary<string, object>();
        public void SaveTempData(HttpContext context, IDictionary<string, object> values) => throw new NotSupportedException();
    }
}
