using System.Globalization;
using System.Net;
using System.Reflection;
using System.Text.RegularExpressions;
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
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

internal static class ActivityRenderChecks
{
    public static async Task RunAsync()
    {
        var builder=WebApplication.CreateBuilder(new WebApplicationOptions{ApplicationName=typeof(TransactionApiClient).Assembly.GetName().Name,EnvironmentName="Testing"});
        builder.Services.AddControllersWithViews().AddApplicationPart(typeof(TransactionApiClient).Assembly);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();builder.Services.AddSingleton<IUrlHelperFactory,UrlFactory>();
        await using var app=builder.Build();using var scope=app.Services.CreateScope();
        async Task<string> Render<T>(string view,T model)
        {
            var context=new DefaultHttpContext{RequestServices=scope.ServiceProvider};var routes=new RouteData();routes.Values["area"]="Member";routes.Values["controller"]="Activity";routes.Values["action"]=view;
            var action=new ActionContext(context,routes,new ActionDescriptor{RouteValues=new Dictionary<string,string?>{["area"]="Member",["controller"]="Activity",["action"]=view}});
            var found=scope.ServiceProvider.GetRequiredService<IRazorViewEngine>().GetView(null,"/Areas/Member/Views/Activity/"+view+".cshtml",true);Check(found.Success,"Compiled Activity view is discoverable.");
            using var writer=new StringWriter();var data=new ViewDataDictionary<T>(new EmptyModelMetadataProvider(),new ModelStateDictionary()){Model=model};
            await found.View!.RenderAsync(new ViewContext(action,found.View,data,new TempDataDictionary(context,new Temp()),writer,new HtmlHelperOptions()));return WebUtility.HtmlDecode(writer.ToString());
        }
        ActivityIndexViewModel Model(int page)
        {
            var projection=ActivityProjection.Build(ActivityParityChecks.Rows(),null,null,null,null,null,null,page,20);
            return new(){Month="2026-11",Page=page,DataLoaded=true,TotalCount=projection.TotalCount,SummaryCount=projection.SummaryCount,IncomeTotal=projection.IncomeTotal,ExpenseTotal=projection.ExpenseTotal,DailyVolume=projection.DailyVolume,DailyNet=projection.DailyNet,DailyCount=projection.DailyCount,Items=projection.Items};
        }
        var culture=CultureInfo.CurrentCulture;string html;
        try {CultureInfo.CurrentCulture=CultureInfo.GetCultureInfo("de-DE");html=await Render("Index",Model(1));}finally{CultureInfo.CurrentCulture=culture;}
        Check(html.Contains("November 2026")&&html.Contains("Choose month")&&!html.Contains("activity-month-input"),"Visible reporting month is a readable label with a secondary picker, not a placeholder input.");
        Check(html.Contains("data-days=\"30\"")&&html.Contains("height=\"52\"")&&html.Contains("height=\"19.61\"")&&!html.Contains("style="),"Daily geometry is proportional, invariant and compatible with strict CSP.");
        Check(html.Contains("−USD 1.00 original")&&html.Contains("−₱56.00")&&!html.Contains("−₱1.00 USD"),"Foreign originals retain their currency and the normalized PHP amount remains primary.");
        Check(html.Contains("Day net")&&html.Contains("−₱210.00")&&html.Contains("21 transactions"),"Date groups show complete-day net and count, not a page subtotal.");
        Check(html.Contains("Protected bill payment")&&!html.Contains("/Edit/24")&&!html.Contains("/Delete/24"),"Protected bill rows render locks without unsupported action links.");
        var second=await Render("Index",Model(2));Check(second.Contains("21–24")&&second.Contains("−₱210.00")&&second.Contains("21 transactions"),"A split day on page two still shows the full daily total.");
        var filtered=Model(2);filtered.Type="Expense";filtered.CategoryId=2;filtered.Search="Fixture";filtered.Day=new(2026,11,5);filtered.AmountOrder="Largest";
        var filteredHtml=await Render("Index",filtered);
        var hrefs=Regex.Matches(filteredHtml,"href=\"([^\"]+)\"").Select(m=>m.Groups[1].Value).Where(h=>h.Contains("Page=1")).ToArray();
        Check(hrefs.Length>0&&hrefs.All(h=>h.Contains("Type=Expense")&&h.Contains("CategoryId=2")&&h.Contains("Search=Fixture")&&h.Contains("Month=2026-11")&&h.Contains("Day=2026-11-05")&&h.Contains("AmountOrder=Largest")&&h.EndsWith("#activity-results")),"Every pagination route preserves filters, search, selected day, amount order and a predictable results anchor.");
        Check(!filteredHtml.Contains("activity-date-group"),"Amount ordering does not invent date groups across interleaved days.");
        var failed=await Render("Index",new ActivityIndexViewModel{Month="2026-11",Error="The API is unavailable."});
        Check(failed.Contains("Transactions are unavailable")&&!failed.Contains("activity-overview-card")&&!failed.Contains("Nothing recorded for this period"),"An API error cannot display fabricated zero totals or an empty-success state.");
        var empty=await Render("Index",new ActivityIndexViewModel{Month="2026-11",DataLoaded=true});Check(empty.Contains("Nothing recorded for this period yet")&&!empty.Contains("Transactions are unavailable"),"A valid empty month remains distinguishable from failure.");
        var rangeEmpty = new ActivityIndexViewModel {Period="Range",FromDate=new(2026,10,1),ToDate=new(2026,10,31),DataLoaded=true};
        var rangeHtml = await Render("Index",rangeEmpty);
        Check(Regex.Matches(rangeHtml,"href=\"([^\"]+)\"[^>]*>Clear filters").Select(m=>m.Groups[1].Value).All(url=>url.Contains("Period=Month")&&url.Contains("Month=")), "Clearing an empty custom date range returns to a valid month query.");
        var all=Model(1);all.Month=null;all.Period="AllTime";Check(!(await Render("Index",all)).Contains("activity-day-chart"),"All-time activity does not draw a misleading monthly chart.");
        var draft=new MemberTransactionDraftViewModel{Type="Expense",CurrencyCode="USD",ReviewRequired=true,FxQuote=new(1,"USD",new(2026,11,1),56,new(2026,11,1),56,"PHP","Frankfurter",DateTimeOffset.UtcNow.AddMinutes(5)),FxProof="isolated-proof",Categories=[new(){CategoryId=2,Name="Groceries",Type="Expense",ColorHEX="#6747C7",IsActive=true,IsSystemDefault=false}]};
        var form=await Render("Create",draft);var confirmation=Regex.Match(form,"<input[^>]*id=\"FxConfirmed\"[^>]*>").Value;
        Check(confirmation.Length>0&&!confirmation.Contains("checked")&&form.Contains("data-transaction-entry")&&form.Contains("data-category-icon=\"2\""),"Compiled transaction form retains initially unchecked FX confirmation, category icons and readable fields.");
        Console.WriteLine("PASS: compiled Activity month controls, daily SVG geometry, FX currencies, complete-day groups, filtered pagination, protected rows and distinct error/empty states.");
    }
    private static void Check(bool value,string message){if(!value)throw new InvalidOperationException(message);}
    private sealed class Temp:ITempDataProvider{public IDictionary<string,object> LoadTempData(HttpContext c)=>new Dictionary<string,object>();public void SaveTempData(HttpContext c,IDictionary<string,object> d){}}
    private sealed class UrlFactory:IUrlHelperFactory{public IUrlHelper GetUrlHelper(ActionContext c)=>new Url(c);}
    private sealed class Url(ActionContext c):IUrlHelper
    {
        public ActionContext ActionContext=>c;
        public string? Action(UrlActionContext a)
        {
            var values=new RouteValueDictionary(a.Values);var id=values.TryGetValue("id",out var raw)?"/"+raw:"";values.Remove("id");
            return "/Member/Activity/"+a.Action+id+"?"+string.Join("&",values.Where(v=>v.Value is not null).Select(v=>Uri.EscapeDataString(v.Key)+"="+Uri.EscapeDataString(Convert.ToString(v.Value,CultureInfo.InvariantCulture)??"")));
        }
        public string? Content(string? p)=>p?.Replace("~/","/");public bool IsLocalUrl(string? u)=>u?.StartsWith('/')==true;public string? Link(string? r,object? v)=>"/fixture";public string? RouteUrl(UrlRouteContext r)=>"/fixture";
    }
}
