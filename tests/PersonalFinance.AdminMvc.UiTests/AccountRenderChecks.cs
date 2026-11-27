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

internal static class AccountRenderChecks
{
    public static async Task RunAsync()
    {
        var builder=WebApplication.CreateBuilder(new WebApplicationOptions{ApplicationName=typeof(TransactionApiClient).Assembly.GetName().Name,EnvironmentName="Testing"});
        builder.Services.AddControllersWithViews().AddApplicationPart(typeof(TransactionApiClient).Assembly);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();builder.Services.AddSingleton<IUrlHelperFactory,UrlFactory>();
        await using var app=builder.Build();using var scope=app.Services.CreateScope();
        async Task<string> Render<T>(string view,T model)
        {
            var context=new DefaultHttpContext{RequestServices=scope.ServiceProvider};var routes=new RouteData();routes.Values["area"]="Member";routes.Values["controller"]="Account";routes.Values["action"]=view;
            var action=new ActionContext(context,routes,new ActionDescriptor{RouteValues=new Dictionary<string,string?>{["area"]="Member",["controller"]="Account",["action"]=view}});
            var found=scope.ServiceProvider.GetRequiredService<IRazorViewEngine>().GetView(null,"/Areas/Member/Views/Account/"+view+".cshtml",true);Check(found.Success,"Compiled Account view is discoverable.");
            using var writer=new StringWriter();var data=new ViewDataDictionary<T>(new EmptyModelMetadataProvider(),new ModelStateDictionary()){Model=model};
            await found.View!.RenderAsync(new ViewContext(action,found.View,data,new TempDataDictionary(context,new Temp()),writer,new HtmlHelperOptions()));return WebUtility.HtmlDecode(writer.ToString());
        }

        var model=new MemberAccountViewModel{DisplayName="Test Finance User",Email="fixture@example.test",Profile=new(){FullName="Test Finance User"}};
        Check(model.Initials=="TF", "Initials match Android's first two name words.");
        Check(new MemberAccountViewModel{DisplayName="  Andrea  "}.Initials=="A" && new MemberAccountViewModel{DisplayName=""}.Initials=="", "Single and missing names are handled safely.");
        Check(new MemberAccountViewModel{DisplayName="\U0001F989 Owli"}.Initials=="\U0001F989O", "Initials preserve Unicode text elements.");
        var overview=await Render("Index",model);
        Check(overview.Contains("account-profile-hero") && overview.Contains(">TF</span>") && overview.Contains("Your profile and settings"),"Profile hero uses actual identity and compact heading.");
        Check(!overview.Contains("name=\"Profile.FullName\"")&&!overview.Contains("name=\"Password.CurrentPassword\"")&&overview.Contains("Finance setup")&&overview.Contains("Preferences"),"Overview organizes settings without exposing permanent editing forms.");
        Check(overview.Contains("data-confirm-logout")&&overview.Contains("method=\"post\""),"Logout remains a protected POST form.");
        var profile=await Render("Profile",model);
        Check(profile.Contains("SaveProfile") && profile.Contains("id=\"profile-email\"") && profile.Contains("readonly") && profile.Contains("Back to Account"),"Profile editing uses a dedicated form with read-only email and return navigation.");
        var password=await Render("Password",model);
        Check(password.Contains("ChangePassword") && Regex.Matches(password,"data-account-password-toggle=").Count==3 && password.Contains("data-password-rules=\"password-policy\"") && password.Contains("At least 4 different characters") && password.Contains("signs out active sessions"),"Password editor has all visibility controls, real policy and session disclosure.");
        var appearance=await Render("Appearance",model);
        Check(Regex.Matches(appearance,"data-theme-choice=").Count==3&&appearance.Contains("does not sync across devices"),"Appearance reuses all three browser-local preferences.");
        var about=await Render("About",model);Check(about.Contains("Your Wise Money Companion")&&!about.Contains("Build identifier"),"About stays concise without technical build information.");
        Console.WriteLine("PASS: compiled Account overview, Unicode initials, dedicated secure editors, appearance and About navigation.");
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
