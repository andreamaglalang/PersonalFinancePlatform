using System.Net;
using System.Net.Http.Json;
using System.Text;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;
using MemberActivity = PersonalFinance.AdminMvc.Areas.Member.Controllers.ActivityController;

internal static class ActivityParityChecks
{
    public static readonly DateOnly Month = new(2026,11,1);
    public static TransactionResponse Tx(int id, DateOnly date, string type="Expense", decimal amount=10m, decimal? amountBase=null, string currency="PHP", int? bill=null) => new(id,Guid.NewGuid(),type,type=="Income"?1:2,type=="Income"?"Salary":"Groceries",amount,currency,currency=="PHP"?1m:56m,date,amountBase??amount,date,"Fixture transaction",new DateTime(2026,11,24,0,0,0,DateTimeKind.Utc),bill,bill is null?null:date);
    public static TransactionResponse[] Rows() => Enumerable.Range(1,21).Select(id=>Tx(id,Month.AddDays(4))).Concat([Tx(22,Month.AddDays(5),"Income",500),Tx(23,Month.AddDays(5),amount:1,amountBase:56,currency:"USD"),Tx(24,Month.AddDays(5),amount:1,bill:7)]).ToArray();
    public static async Task RunAsync()
    {
        var rows=Rows();
        var first=ActivityProjection.Build(rows,null,null,Month,new(2026,11,30),null,null,1,20);
        var second=ActivityProjection.Build(rows,null,null,Month,new(2026,11,30),null,null,2,20);
        Check(first.IncomeTotal==500 && first.ExpenseTotal==267 && first.TotalCount==24 && first.DailyVolume[Month.AddDays(5)]==557,"Complete-period totals use authoritative base values and include each bill payment once.");
        Check(first.DailyNet[Month.AddDays(4)]==-210 && second.DailyNet[Month.AddDays(4)]==-210 && second.DailyCount[Month.AddDays(4)]==21 && second.Items.Count==4,"A day spanning pages retains the complete daily net/count on both pages.");
        Check(ActivityProjection.Build(rows,null,null,Month,new(2026,11,30),null,Month.AddDays(5),1,20) is {TotalCount:3,SummaryCount:24,IncomeTotal:500,ExpenseTotal:267},"Selecting a chart day changes rows without replacing period totals with a partial day.");
        Check(ActivityProjection.Build(rows,null,null,null,null,"56.00",null,1,20).Items.Single().TransactionId==23,"Amount search finds a converted PHP amount across complete data.");
        var leapRows=new[]{Tx(80,new(2024,2,29)),Tx(81,new(2024,3,1))};
        Check(ActivityProjection.Build(leapRows,null,null,new(2024,2,1),new(2024,2,29),null,null,1,20).TotalCount==1,"Reporting boundaries include leap day and exclude the next month.");
        foreach(var name in new[]{"IncomeTotal","Items","DataLoaded","BaseCurrency","DailyNet"})Check(typeof(ActivityIndexViewModel).GetProperty(name)!.IsDefined(typeof(Microsoft.AspNetCore.Mvc.ModelBinding.BindNeverAttribute),true),"Server financial display state cannot be supplied as query parameters.");

        using var sessions=new AdminSessionStore();
        var user=new CurrentUserResponse(91,"Isolated fixture","fixture@example.test","PHP",["User"]);
        var session=sessions.Create(new AuthenticationResponse{AccessToken="fixture-only",AccessTokenExpiresAtUtc=DateTime.UtcNow.AddHours(1),RefreshToken="fixture-only",RefreshTokenExpiresAtUtc=DateTime.UtcNow.AddHours(2),User=user},user)!;
        var auth=new AuthFixture();
        using var services=new ServiceCollection().AddSingleton<IAuthenticationService>(auth).AddSingleton<IUrlHelperFactory,UrlHelperFactory>().BuildServiceProvider();
        var context=new DefaultHttpContext{RequestServices=services,User=AuthenticationCoordinator.Principal(session)};
        using var handler=new ApiFixture(rows);
        using var http=new HttpClient(handler){BaseAddress=new Uri("https://isolated-api.test/")};
        var protector=new EphemeralDataProtectionProvider();
        var controller=new MemberActivity(new TransactionApiClient(http),new CategoryApiClient(http),sessions,protector,new ExchangeRateApiClient(http)){ControllerContext=new(new ActionContext(context,new RouteData(),new ControllerActionDescriptor())),TempData=new TempDataDictionary(context,new TempFixture())};
        async Task<ActivityIndexViewModel> Index(ActivityIndexViewModel query)=>(ActivityIndexViewModel)((ViewResult)await controller.Index(query,default)).Model!;
        var model=await Index(new(){Month="2026-11",Page=99});
        Check(model.DataLoaded && model.Page==2 && model.Items.Count==4 && model.SummaryCount==24 && model.IncomeTotal==500 && model.ExpenseTotal==267,"Invalid high pages clamp to the last actual page without changing overview totals.");
        Check(model.Categories.Count==101 && handler.CategoryPages.Contains(2),"Category filters and forms retrieve metadata beyond the first category page, including historical categories.");
        model=await Index(new(){Month="2026-11",Type="Expense",CategoryId=2,Search="56.00"});
        Check(model.TotalCount==1 && model.Items.Single().TransactionId==23 && model.ExpenseTotal==56,"API type/category/month filtering and complete-history search compose correctly.");
        model=await Index(new(){Period="AllTime"});Check(model.Month is null && model.TotalCount==24,"All time clears the monthly range and includes the complete owner history.");
        model=await Index(new(){Month="9999-12"});Check(model.Month==BudgetPlanSummary.PhilippineToday.ToString("yyyy-MM"),"Injected future months clamp to the current Philippine-local month safely.");
        Check(await controller.Delete(24,default) is RedirectToActionResult && handler.Writes==0 && await controller.Edit(24,default) is RedirectToActionResult,"Direct edit/delete routes refuse protected bill payments without financial writes.");
        var stale=new MemberTransactionDraftViewModel{Type="Expense",CategoryId=2,Amount=25,CurrencyCode="PHP",TransactionDate=Month,Description="Isolated create"};
        Check(await controller.Create(stale,default) is ViewResult && handler.Writes==0 && !controller.ModelState.IsValid,"R3-3: a create form without its ClientId is refused before any POST.");controller.ModelState.Clear();
        // A malformed posted ClientId leaves its attempted value and binding error in ModelState; the returned form must
        // carry a fresh identity (input tag helpers prefer ModelState values over the model).
        stale.ClientId=null; controller.ModelState.SetModelValue("ClientId","not-a-guid","not-a-guid"); controller.ModelState.AddModelError("ClientId","bad");
        var recovered=(MemberTransactionDraftViewModel)((ViewResult)await controller.Create(stale,default)).Model!;
        Check(handler.Writes==0 && recovered.ClientId is {} fresh && fresh!=Guid.Empty && !controller.ModelState.ContainsKey("ClientId"),"R3-3: an expired form returns a fresh ClientId that is not masked by the stale posted value.");
        controller.ModelState.Clear(); recovered.CategoryId=2;
        Check(await controller.Create(recovered,default) is RedirectToActionResult && handler.Writes==1,"R3-3: the recovered form then saves once.");
        handler.Writes=0; controller.ModelState.Clear();
        var create=new MemberTransactionDraftViewModel{Type="Expense",CategoryId=2,Amount=25,CurrencyCode="PHP",TransactionDate=Month,Description="Isolated create",ClientId=Guid.NewGuid()};
        Check(await controller.Create(create,default) is RedirectToActionResult && handler.Writes==1,"A valid PHP creation uses the existing authenticated POST contract in isolated infrastructure.");
        handler.FailWrites=true;controller.ModelState.Clear();
        Check(await controller.Create(create,default) is ViewResult && !controller.ModelState.IsValid,"A failed transaction API save stays on the form and is never reported as success.");handler.FailWrites=false;
        controller.ModelState.Clear(); handler.ArchiveOriginal = true;
        var editView = (MemberTransactionDraftViewModel)((ViewResult)await controller.Edit(1, default)).Model!;
        Check(editView.Categories.Any(category => category.CategoryId == 2 && !category.IsActive), "An edit keeps the existing archived category available.");
        controller.ModelState.Clear();
        var edited = new MemberTransactionDraftViewModel { Type="Expense",CategoryId=2,Amount=20,CurrencyCode="PHP",TransactionDate=Month.AddDays(4),Description="Isolated edit",Version=new string('A',64) }; // hidden C1 field
        Check(await controller.Edit(1, edited, default) is RedirectToActionResult, "The existing archived category can be retained while editing a transaction through the owner-scoped PUT contract.");
        controller.ModelState.Clear(); var writesBefore = handler.Writes; edited.Version = null;
        Check(await controller.Edit(1, edited, default) is ViewResult && handler.Writes == writesBefore && !controller.ModelState.IsValid,
            "C1: an edit form without its version is refused before any PUT and asks for a reload.");
        handler.ArchiveOriginal = false; controller.ModelState.Clear();
        var payload=protector.CreateProtector(MemberCurrencyPreferences.ProtectorPurpose).Protect(Encoding.UTF8.GetBytes(MemberCurrencyPreferences.Pack("91",new("USD",true,true))));
        context.Request.Headers.Cookie=MemberCurrencyPreferences.CookieName+"="+Convert.ToBase64String(payload).TrimEnd('=').Replace('+','-').Replace('/','_');
        var writes=handler.Writes;
        var foreign=new MemberTransactionDraftViewModel{Type="Expense",CategoryId=2,Amount=1,CurrencyCode="USD",TransactionDate=Month,ClientId=Guid.NewGuid()};
        var review=(MemberTransactionDraftViewModel)((ViewResult)await controller.Create(foreign,default)).Model!;
        Check(review is {ReviewRequired:true,FxConfirmed:false,FxQuote:not null,FxProof:not null} && handler.Writes==writes,"The first foreign-currency review is unchecked and saves no transaction.");
        controller.ModelState.Clear(); foreign.FxConfirmed=true; foreign.PostedFxProof=review.FxProof;
        Check(await controller.Create(foreign,default) is RedirectToActionResult && handler.Writes==writes+1,"R3-2: confirming the reviewed rate saves the foreign-currency transaction once.");
        handler.FailReads=true;
        model=await Index(new(){Month="2026-11"});Check(!model.DataLoaded && model.Error is not null,"A failed API read stays unavailable rather than becoming confirmed empty activity.");handler.FailReads=false;
        handler.Expire=true;
        Check(await controller.Index(new(){Month="2026-11"},default) is RedirectToActionResult {ActionName:"Login"} && auth.SignedOut,"Expired sessions are invalidated and routed to sign-in.");
        Console.WriteLine("PASS: Activity controller complete aggregates, whole-day groups, leap boundaries, paging clamps, all categories, search, FX review, isolated creation/failure and protected records.");
    }
    private static void Check(bool value,string message){if(!value)throw new InvalidOperationException(message);}
    private sealed class ApiFixture(TransactionResponse[] rows):HttpMessageHandler
    {
        public int Writes; public bool FailWrites,FailReads,Expire,ArchiveOriginal; public HashSet<int> CategoryPages=[];
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r,CancellationToken ct)
        {
            Check(r.Headers.Authorization?.ToString()=="Bearer fixture-only","Every fixture API request carries the owner-scoped bearer.");
            if(Expire)return new(HttpStatusCode.Unauthorized);
            var query=QueryHelpers.ParseQuery(r.RequestUri!.Query);var path=r.RequestUri.AbsolutePath;
            if(path=="/api/categories")
            {
                var page=int.Parse(query["Page"].ToString());var size=int.Parse(query["PageSize"].ToString());CategoryPages.Add(page);
                var all=Enumerable.Range(1,101).Select(id=>new CategoryResponse{CategoryId=id,Name=id==1?"Salary":id==2?"Groceries":"Category "+id,Type=id==1?"Income":"Expense",ColorHEX="#6747C7",IsActive=!(ArchiveOriginal && id==2),IsSystemDefault=false}).ToArray();
                return Ok(new CategoryPageResponse{Items=all.Skip((page-1)*size).Take(size).ToArray(),Page=page,PageSize=size,TotalCount=101});
            }
            if(path=="/api/exchange-rates/quote")return Ok(new ExchangeRateQuoteResponse("USD","PHP",1,56,Month,Month,56,"Frankfurter",DateTime.UtcNow));
            if(r.Method==HttpMethod.Get && path=="/api/transactions")
            {
                if(FailReads)return new(HttpStatusCode.ServiceUnavailable);
                var filtered=rows.Where(t=>(!query.ContainsKey("Type")||t.Type==query["Type"])&&(!query.ContainsKey("CategoryId")||t.CategoryId==int.Parse(query["CategoryId"].ToString()))&&(!query.ContainsKey("FromDate")||t.TransactionDate>=DateOnly.Parse(query["FromDate"].ToString()))&&(!query.ContainsKey("ToDate")||t.TransactionDate<=DateOnly.Parse(query["ToDate"].ToString()))).OrderByDescending(t=>t.TransactionDate).ThenByDescending(t=>t.TransactionId).ToArray();
                var page=int.Parse(query["Page"].ToString());return Ok(new TransactionPage(filtered.Skip((page-1)*20).Take(20).ToArray(),page,20,filtered.Length));
            }
            if(r.Method==HttpMethod.Get && path.StartsWith("/api/transactions/"))return Ok(rows.Single(t=>t.TransactionId==int.Parse(path.Split('/').Last())));
            if(r.Method==HttpMethod.Post && path=="/api/transactions")
            {
                Writes++;if(FailWrites)return new(HttpStatusCode.ServiceUnavailable);
                var body=await r.Content!.ReadFromJsonAsync<CreateTransactionRequest>(ct);
                return new(HttpStatusCode.Created){Content=JsonContent.Create(Tx(500,body!.TransactionDate,body.Type,body.Amount) with {ClientId=body.ClientId!.Value,Description=body.Description})};
            }
            if(r.Method==HttpMethod.Put && path.StartsWith("/api/transactions/"))
            {
                Writes++;var body=await r.Content!.ReadFromJsonAsync<UpdateTransactionRequest>(ct);var id=int.Parse(path.Split('/').Last());
                return Ok(Tx(id,body!.TransactionDate,body.Type,body.Amount) with {Description=body.Description});
            }
            throw new InvalidOperationException("Unexpected fixture route "+path);
        }
        private static HttpResponseMessage Ok<T>(T value)=>new(HttpStatusCode.OK){Content=JsonContent.Create(value)};
    }
    private sealed class TempFixture:ITempDataProvider{public IDictionary<string,object> LoadTempData(HttpContext c)=>new Dictionary<string,object>();public void SaveTempData(HttpContext c,IDictionary<string,object> d){}}
    private sealed class AuthFixture:IAuthenticationService
    {
        public bool SignedOut;public Task SignOutAsync(HttpContext c,string? s,AuthenticationProperties? p){SignedOut=true;return Task.CompletedTask;}
        public Task<AuthenticateResult> AuthenticateAsync(HttpContext c,string? s)=>throw new NotSupportedException();public Task ChallengeAsync(HttpContext c,string? s,AuthenticationProperties? p)=>throw new NotSupportedException();public Task ForbidAsync(HttpContext c,string? s,AuthenticationProperties? p)=>throw new NotSupportedException();public Task SignInAsync(HttpContext c,string? s,System.Security.Claims.ClaimsPrincipal u,AuthenticationProperties? p)=>throw new NotSupportedException();
    }
}
