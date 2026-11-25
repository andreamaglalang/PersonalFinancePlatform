using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;
using MemberReports = PersonalFinance.AdminMvc.Areas.Member.Controllers.ReportsController;

internal static class InsightsRedesignChecks
{
    public static async Task RunAsync()
    {
        var month = new DateOnly(2026, 11, 1);
        Check(InsightsProjection.Months(month).SequenceEqual(Enumerable.Range(6, 6).Select(m => new DateOnly(2026, m, 1))), "Six consecutive reporting months end at the selection.");
        Check(InsightsProjection.Months(new(2024, 2, 1)).First() == new DateOnly(2023, 9, 1), "Leap-year and year-crossing windows retain whole calendar months.");
        Check(InsightsProjection.ClampMonth(new(2027, 1, 1), month) == month && InsightsProjection.ClampMonth(new(2000, 1, 1), month) == new DateOnly(2000, 6, 1), "Future months clamp to current and oldest trend stays inside the API range.");
        Check(InsightsProjection.BarHeight(0, 35000) == 0 && Math.Abs(InsightsProjection.BarHeight(5000, 30000) - 30m) < .000001m && InsightsProjection.BarHeight(30000, 30000) == 180, "Zero bars have zero height; shared-scale 5k and 30k bars have a 1:6 height ratio.");
        Check(InsightsProjection.BarHeight(10, 0) == 0 && InsightsProjection.Share(1, 0) == 0, "Empty data never divides by zero.");
        var report = Report(month, 35000, 19851.03m, [new(8, "Utilities", 5499, 3), new(2, "Groceries", 4538, 8), new(9, "Other expenses", 9813, 9), new(44, "Tiny category", 1.03m, 1)]);
        var slices = InsightsProjection.Slices(report, new Dictionary<int, string> { [8] = "#123456" });
        Check(slices.First().Name == "Other expenses" && slices.Sum(s => s.Share) is > 99.999999m and < 100.000001m, "Slices rank by exact spend and use the full expense denominator.");
        Check(slices.Single(s => s.CategoryId == 8).Color == "#123456" && slices.Last().Share < .01m, "Configured category colors survive, and tiny categories have no artificial minimum share.");
        Check(slices.Last().Offset + slices.Last().Share is > 99.999999m and < 100.000001m, "Donut offsets close the full circle without using rounded labels.");
        Check(InsightsProjection.Slices(Report(month, 0, 0, [])).Count == 0 && InsightsProjection.Slices(Report(month, 0, 100, [new(1, "Only", 100, 1)])).Single().Share == 100, "Empty and single-category donuts are honest.");
        Check(InsightsProjection.CategoryColor(3, "red;bad") == InsightsProjection.CategoryColor(3), "Category colors reject CSS or markup injection.");
        var history = new[] { Point(month.AddMonths(-1), 200, 100), Point(month, 300, 125) };
        Check(InsightsProjection.SpendingComparison(month, history)!.Contains("up 25.0%") && InsightsProjection.SpendingComparison(month, [Point(month.AddMonths(-1), 0, 100), Point(month, 0, 75)])!.Contains("down 25.0%"), "Spending changes use previous-month expenses and Android half-up rounding.");
        Check(InsightsProjection.SpendingComparison(month, [Point(month.AddMonths(-1), 0, 100), Point(month, 0, 100)])!.Contains("unchanged"), "Unchanged spend remains neutral.");
        Check(InsightsProjection.SpendingComparison(month, [Point(month, 0, 0)]) is null && InsightsProjection.SpendingComparison(month, [Point(month.AddMonths(-1), 0, 0), Point(month, 0, 5)])!.Contains("percentage comparison is unavailable"), "Unavailable history and zero baseline never invent percentages.");
        Check(InsightsProjection.SpendingComparison(month, [Point(month.AddMonths(-1), 0, 0), Point(month, 0, 0)]) == "No spending recorded in either month.", "Both-zero comparison has a clear state.");
        var scale = InsightsProjection.Scale(history);
        Check(scale >= 300 && InsightsProjection.BarHeight(125, scale) < InsightsProjection.BarHeight(300, scale), "The axis covers the largest income or expense, with one scale for both series.");
        var culture = CultureInfo.CurrentCulture;
        try { CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("de-DE"); Check(InsightsProjection.Number(62.5m) == "62.5" && InsightsProjection.Money(1250) == "₱1,250.00", "SVG geometry and money formatting are culture invariant."); }
        finally { CultureInfo.CurrentCulture = culture; }

        using var sessions = new AdminSessionStore();
        var user = new CurrentUserResponse(91, "Isolated fixture", "fixture@example.test", "PHP", ["User"]);
        var session = sessions.Create(new AuthenticationResponse { AccessToken = "isolated-test-only", AccessTokenExpiresAtUtc = DateTime.UtcNow.AddHours(1), RefreshToken = "test-only", RefreshTokenExpiresAtUtc = DateTime.UtcNow.AddHours(2), User = user }, user)!;
        var auth = new AuthFixture();
        using var services = new ServiceCollection().AddSingleton<IAuthenticationService>(auth).AddSingleton<IUrlHelperFactory, UrlHelperFactory>().BuildServiceProvider();
        var context = new DefaultHttpContext { RequestServices = services, User = AuthenticationCoordinator.Principal(session) };
        var calls = new List<DateOnly>();
        var badMonth = (DateOnly?)null;
        var expireHistory = false;
        using var http = new HttpClient(new Handler(request => {
            Check(request.Method == HttpMethod.Get && request.Headers.Authorization?.ToString() == "Bearer isolated-test-only", "Reports use owner bearer tokens on read-only API calls.");
            var requested = DateOnly.Parse(request.RequestUri!.Query.Split('=')[1], CultureInfo.InvariantCulture);
            lock(calls) calls.Add(requested);
            if (expireHistory && requested != month) return new(HttpStatusCode.Unauthorized);
            if (requested == badMonth) return new(HttpStatusCode.ServiceUnavailable);
            return new(HttpStatusCode.OK) { Content = JsonContent.Create(Report(requested, 35000, requested == month ? 19851.03m : 0, requested == month ? report.ExpenseByCategory : [])) };
        })) { BaseAddress = new Uri("https://isolated-api.test/") };
        using var invalidHttp = new HttpClient(new Handler(_ => new(HttpStatusCode.OK) { Content = JsonContent.Create(report with { NetCashFlow = report.NetCashFlow + 1 }) })) { BaseAddress = new Uri("https://isolated-api.test/") };
        Check((await new ReportsApiClient(invalidHttp).GetMonthlyAsync(month,"isolated-test-only",default)).Status == 503, "A contradictory API net total is rejected before drawing charts.");
        var controller = new MemberReports(new ReportsApiClient(http), sessions) { ControllerContext = new(new ActionContext(context, new RouteData(), new ControllerActionDescriptor())), TempData = new TempDataDictionary(context, new TempFixture()) };
        var model = (MemberInsightsViewModel)((ViewResult)await controller.Index("2026-11", default)).Model!;
        Check(model.Report is { TotalIncome: 35000, TotalExpenses: 19851.03m, NetCashFlow: 15148.97m } && model.MonthlyHistory.Count == 6 && !model.HistoryPartial, "The Member controller carries authoritative PHP totals and all six API reports.");
        Check(calls.Distinct().Count() == 6 && calls.Count == 6, "The current report is reused; each calendar month is requested once.");
        badMonth = month.AddMonths(-2);
        model = (MemberInsightsViewModel)((ViewResult)await controller.Index("2026-11", default)).Model!;
        Check(model.HistoryPartial && model.MonthlyHistory.All(p => p.Month != badMonth) && model.Report is not null, "A failed historical month stays unavailable while verified data remains visible.");
        badMonth = month;
        model = (MemberInsightsViewModel)((ViewResult)await controller.Index("2026-11", default)).Model!;
        Check(model.Report is null && model.ReportError is not null && context.Response.StatusCode == 503, "A selected-report API failure is never a confirmed zero month.");
        badMonth = null; expireHistory = true;
        Check(await controller.Index("2026-11", default) is RedirectToActionResult { ActionName: "Login" } && auth.SignedOut && sessions.Find(session.Id) is null, "Session expiry during history invalidates the owner session.");
        Check(typeof(MemberReports).GetCustomAttributes(typeof(Microsoft.AspNetCore.Authorization.AuthorizeAttribute), true).Cast<Microsoft.AspNetCore.Authorization.AuthorizeAttribute>().Single().Policy == "User", "Insights preserves Member authorization.");
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Reports", "Index.cshtml"));
        Check(!view.Contains("style=") && !view.Contains("member-quote-card") && !view.Contains("report.Budgets") && !view.Contains("Income by category"), "Insights uses CSP-safe geometry and removes duplicate converter, budgets and income-category panels.");
        Check(view.Contains("data-amount=") && view.Contains("height=\"@Number(incomeHeight)") && view.Contains("stroke-dasharray=") && view.Contains("Report unavailable"), "Rendered charts bind SVG geometry and preserve missing-data labels.");
        Console.WriteLine("PASS: Insights monthly navigation, six-month data, donut shares, scale and zeros, comparison cases, culture, errors, owner session and CSP-safe presentation.");
    }
    private static MemberInsightsViewModel.MonthPoint Point(DateOnly month, decimal income, decimal expense) => new(month, "PHP", income, expense, income - expense, 1);
    private static MonthlyReportResponse Report(DateOnly month, decimal income, decimal expense, IReadOnlyList<ReportCategoryTotal> categories) => new(month, "PHP", income, expense, income - expense, categories.Sum(c => c.TransactionCount) + (income > 0 ? 1 : 0), 0, categories, income > 0 ? [new(1, "Income", income, 1)] : [], []);
    private static void Check(bool value, string message) { if (!value) throw new InvalidOperationException(message); }
    private sealed class TempFixture : ITempDataProvider { public IDictionary<string, object> LoadTempData(HttpContext c) => new Dictionary<string, object>(); public void SaveTempData(HttpContext c, IDictionary<string, object> data) { } }
    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> response) : HttpMessageHandler { protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => Task.FromResult(response(request)); }
    private sealed class AuthFixture : IAuthenticationService
    {
        public bool SignedOut { get; private set; }
        public Task SignOutAsync(HttpContext c, string? s, AuthenticationProperties? p) { SignedOut = true; return Task.CompletedTask; }
        public Task<AuthenticateResult> AuthenticateAsync(HttpContext c, string? s) => throw new NotSupportedException();
        public Task ChallengeAsync(HttpContext c, string? s, AuthenticationProperties? p) => throw new NotSupportedException();
        public Task ForbidAsync(HttpContext c, string? s, AuthenticationProperties? p) => throw new NotSupportedException();
        public Task SignInAsync(HttpContext c, string? s, System.Security.Claims.ClaimsPrincipal u, AuthenticationProperties? p) => throw new NotSupportedException();
    }
}
