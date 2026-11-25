using System.Globalization;
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

internal static class InsightsRenderChecks
{
    public static async Task RunAsync()
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ApplicationName = typeof(ReportsApiClient).Assembly.GetName().Name, EnvironmentName = "Testing" });
        builder.Services.AddControllersWithViews().AddApplicationPart(typeof(ReportsApiClient).Assembly);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        builder.Services.AddSingleton<IUrlHelperFactory, FixtureUrlFactory>();
        await using var app = builder.Build();
        using var scope = app.Services.CreateScope();
        async Task<string> Render(MemberInsightsViewModel model)
        {
            var context = new DefaultHttpContext { RequestServices = scope.ServiceProvider };
            var routes = new RouteData(); routes.Values["area"] = "Member"; routes.Values["controller"] = "Reports"; routes.Values["action"] = "Index";
            var descriptor = new ActionDescriptor { RouteValues = new Dictionary<string, string?> { ["area"] = "Member", ["controller"] = "Reports", ["action"] = "Index" } };
            var action = new ActionContext(context, routes, descriptor);
            var engine = scope.ServiceProvider.GetRequiredService<IRazorViewEngine>();
            var found = engine.GetView(null, "/Areas/Member/Views/Reports/Index.cshtml", true);
            Check(found.Success, "Compiled Member Insights view is available.");
            using var writer = new StringWriter();
            var data = new ViewDataDictionary<MemberInsightsViewModel>(new EmptyModelMetadataProvider(), new ModelStateDictionary()) { Model = model };
            await found.View!.RenderAsync(new ViewContext(action, found.View, data, new TempDataDictionary(context, new FixtureTempData()), writer, new HtmlHelperOptions()));
            return writer.ToString();
        }
        var month = new DateOnly(2026,11,1);
        var report = new MonthlyReportResponse(month,"PHP",35000,19851.03m,15148.97m,3,0,[new(2,"Largest",19850,1),new(3,"Tiny",1.03m,1)],[new(1,"Income",35000,1)],[]);
        var history = InsightsProjection.Months(month).Select(m => new MemberInsightsViewModel.MonthPoint(m,"PHP",m==month?35000:0,m==month?19851.03m:0,m==month?15148.97m:0,m==month?3:0)).ToArray();
        var culture = CultureInfo.CurrentCulture;
        string html;
        try { CultureInfo.CurrentCulture=CultureInfo.GetCultureInfo("fr-FR"); html=await Render(new("2026-11") { Report=report,MonthlyHistory=history }); }
        finally { CultureInfo.CurrentCulture=culture; }
        var rectangles = System.Text.RegularExpressions.Regex.Matches(html,"<rect class=\"insights-(income|expense)-bar\"[^>]*>").Select(m=>m.Value).ToArray();
        Check(rectangles.Length==12, "Actual Razor emits six income/expense pairs.");
        Check(rectangles.Count(r=>r.Contains("height=\"0\""))==10, "Five zero months emit genuinely zero-height SVG bars.");
        Check(rectangles.Single(r=>r.Contains("data-month=\"2026-11\"")&&r.Contains("data-series=\"income\"")).Contains("height=\"157.5\""), "35k income renders at 157.5 of 180 plot units against the common 40k scale.");
        Check(rectangles.Single(r=>r.Contains("data-month=\"2026-11\"")&&r.Contains("data-series=\"expenses\"")).Contains("height=\"89.329635\""), "19,851.03 expenses render proportionally against that same scale.");
        Check(!html.Contains("style=") && html.Contains("stroke-dasharray=") && html.Contains("&#x20B1;35,000.00"), "Compiled markup preserves invariant geometry under a comma-decimal culture and needs no inline styles.");
        var empty = await Render(new("2026-11") { Report=new(month,"PHP",0,0,0,0,0,[],[],[]),MonthlyHistory=history.Select(p=>p with {Income=0,Expenses=0,NetFlow=0,TransactionCount=0}).ToArray() });
        Check(empty.Contains("No spending this month") && !empty.Contains("insights-donut-segment") && empty.Contains("No financial activity"), "Empty reports render useful copy and no fabricated donut slices.");
        var failed = await Render(new("2026-11") { ReportError="Reports could not be loaded." });
        Check(failed.Contains("Reports unavailable") && !failed.Contains("insights-trend-svg") && !failed.Contains("No spending this month"), "Failed reports cannot masquerade as an empty chart.");
        var partial = await Render(new("2026-11") {Report=report,MonthlyHistory=history.Where(p=>p.Month!=new DateOnly(2026,10,1)).ToArray(),HistoryPartial=true});
        Check(partial.Contains("N/A") && partial.Contains("Report unavailable") && !partial.Contains("data-month=\"2026-10\""), "Missing months have a labeled gap, not synthetic zero bars.");
        Console.WriteLine("PASS: compiled Insights Razor geometry, culture, empty/error states, missing-month gaps and common scale.");
    }
    private static void Check(bool value,string message) { if(!value) throw new InvalidOperationException(message); }
    private sealed class FixtureTempData : ITempDataProvider {public IDictionary<string,object> LoadTempData(HttpContext context)=>new Dictionary<string,object>();public void SaveTempData(HttpContext context,IDictionary<string,object> values) {}}
    private sealed class FixtureUrlFactory : IUrlHelperFactory {public IUrlHelper GetUrlHelper(ActionContext context)=>new FixtureUrl(context);}
    private sealed class FixtureUrl(ActionContext context) : IUrlHelper
    {
        public ActionContext ActionContext=>context;
        public string? Action(UrlActionContext c)=>"/fixture";
        public string? Content(string? path)=>path?.Replace("~/","/");
        public bool IsLocalUrl(string? url)=>url?.StartsWith('/')==true;
        public string? Link(string? name,object? values)=>"/fixture";
        public string? RouteUrl(UrlRouteContext c)=>"/fixture";
    }
}
