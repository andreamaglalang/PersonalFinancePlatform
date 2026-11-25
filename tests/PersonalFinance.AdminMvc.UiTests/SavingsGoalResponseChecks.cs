using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using Microsoft.AspNetCore.Mvc.Razor;
using Microsoft.AspNetCore.Mvc.Rendering;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

internal static class SavingsGoalResponseChecks
{
    public static async Task RunAsync()
    {
        foreach (var (label, saved, status) in new[]
        {
            ("unfunded", 0m, "Active"), ("partial", 25m, "Active"),
            ("fully funded", 100m, "Completed"), ("overfunded", 150m, "Completed")
        })
        {
            var goal = new SavingsGoalResponse(1, "[CP5 TEST] SG 20261123-01", 100m, null,
                status, saved, 100m - saved, Math.Max(0m, saved - 100m), saved, "PHP", "AAAAAAAAAAA=");
            using var handler = new FixtureHandler(HttpStatusCode.OK,
                JsonSerializer.Serialize(new SavingsGoalPage([goal], 1, 20, 1), new JsonSerializerOptions(JsonSerializerDefaults.Web)));
            using var http = new HttpClient(handler) { BaseAddress = new("https://fixture.invalid/") };
            var result = await new SavingsGoalApiClient(http).ListAsync(null, 1, "fixture-only", default);
            Check(result.Status == 200 && result.Value?.Items.Single() == goal,
                $"{label} API goal must survive deserialization and validation.");
            Check(handler.Requests == 1 && handler.Method == HttpMethod.Get,
                "Goal listing must make one GET without retry or mutation.");
            Console.WriteLine($"PASS: savings goal {label} response accepted.");
        }

        var invalid = new SavingsGoalResponse(1, "Invalid surplus", 100m, null, "Active",
            0m, 100m, -100m, 0m, "PHP", "AAAAAAAAAAA=");
        using (var handler = new FixtureHandler(HttpStatusCode.OK,
            JsonSerializer.Serialize(new SavingsGoalPage([invalid], 1, 20, 1), new JsonSerializerOptions(JsonSerializerDefaults.Web))))
        using (var http = new HttpClient(handler) { BaseAddress = new("https://fixture.invalid/") })
            Check((await new SavingsGoalApiClient(http).ListAsync(null, 1, "fixture-only", default)).Status == 503,
                "An actually contradictory negative surplus must remain rejected.");

        using var failureHandler = new FixtureHandler(HttpStatusCode.InternalServerError, "{}");
        using var failureHttp = new HttpClient(failureHandler) { BaseAddress = new("https://fixture.invalid/") };
        var failure = await new SavingsGoalApiClient(failureHttp).ListAsync(null, 1, "fixture-only", default);
        Check(failure.Status == 503 && failure.Value is null, "Failed HTTP response must remain unavailable.");

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ApplicationName = typeof(SavingsGoalApiClient).Assembly.GetName().Name,
            EnvironmentName = "Testing"
        });
        builder.Services.AddControllersWithViews().AddApplicationPart(typeof(SavingsGoalApiClient).Assembly);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        builder.Services.AddSingleton<IUrlHelperFactory, FixtureUrlFactory>();
        await using var app = builder.Build(); // Render compiled Razor only; never start a listening host.
        using var scope = app.Services.CreateScope();

        async Task<string> Render(MemberSavingsGoalIndexViewModel model)
        {
            var context = new DefaultHttpContext { RequestServices = scope.ServiceProvider };
            var routes = new RouteData();
            routes.Values["area"] = "Member";
            routes.Values["controller"] = "SavingsGoals";
            routes.Values["action"] = "Index";
            var descriptor = new ActionDescriptor { RouteValues = new Dictionary<string, string?>
                { ["area"] = "Member", ["controller"] = "SavingsGoals", ["action"] = "Index" } };
            var action = new ActionContext(context, routes, descriptor);
            var engine = scope.ServiceProvider.GetRequiredService<IRazorViewEngine>();
            var view = engine.GetView(null, "/Areas/Member/Views/SavingsGoals/Index.cshtml", true);
            Check(view.Success, "Compiled Savings Goals Razor view must be discoverable.");
            var razorView = view.View ?? throw new InvalidOperationException("Compiled goal view missing.");
            using var writer = new StringWriter();
            var data = new ViewDataDictionary<MemberSavingsGoalIndexViewModel>(
                new EmptyModelMetadataProvider(), new ModelStateDictionary()) { Model = model };
            var viewContext = new ViewContext(action, razorView, data,
                new TempDataDictionary(context, new FixtureTempData()), writer, new HtmlHelperOptions());
            await razorView.RenderAsync(viewContext);
            return writer.ToString();
        }

        var failedHtml = await Render(new() { Error = "The API is unavailable or returned an invalid response. Try again later." });
        Check(failedHtml.Contains("The API is unavailable") && failedHtml.Contains("Savings goals could not be loaded.")
            && !failedHtml.Contains("No savings goals yet"),
            "A failed API response must render an error and never the empty-success message.");
        var emptyHtml = await Render(new() { Results = new SavingsGoalPage([], 1, 20, 0) });
        Check(emptyHtml.Contains("No savings goals yet") && !emptyHtml.Contains("Savings goals could not be loaded."),
            "A successful empty result must render the honest empty state.");
        var populatedHtml = await Render(new()
        {
            Results = new SavingsGoalPage([new(1, "[CP5 TEST] SG 20261123-01", 100m, null,
                "Active", 0m, 100m, 0m, 0m, "PHP", "AAAAAAAAAAA=")], 1, 20, 1)
        });
        Check(populatedHtml.Contains("[CP5 TEST] SG 20261123-01") && populatedHtml.Contains("100.00")
            && !populatedHtml.Contains("No savings goals yet") && !populatedHtml.Contains("Savings goals could not be loaded."),
            "A populated result must render the existing goal rather than either empty/error state.");
        Console.WriteLine("PASS: contradictory surplus rejected; failed, empty and populated compiled Razor states remain distinct.");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class FixtureHandler(HttpStatusCode status, string body) : HttpMessageHandler
    {
        public int Requests { get; private set; }
        public HttpMethod? Method { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests++;
            Method = request.Method;
            return Task.FromResult(new HttpResponseMessage(status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class FixtureTempData : ITempDataProvider
    {
        public IDictionary<string, object> LoadTempData(HttpContext context) => new Dictionary<string, object>();
        public void SaveTempData(HttpContext context, IDictionary<string, object> values) { }
    }

    private sealed class FixtureUrlFactory : IUrlHelperFactory
    {
        public IUrlHelper GetUrlHelper(ActionContext context) => new FixtureUrl(context);
    }

    private sealed class FixtureUrl(ActionContext context) : IUrlHelper
    {
        public ActionContext ActionContext => context;
        public string? Action(UrlActionContext context) => "/fixture";
        public string? Content(string? contentPath) => contentPath?.Replace("~/", "/");
        public bool IsLocalUrl(string? url) => url?.StartsWith('/') == true;
        public string? Link(string? routeName, object? values) => "/fixture";
        public string? RouteUrl(UrlRouteContext context) => "/fixture";
    }
}
