using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;
using Account = PersonalFinance.AdminMvc.Areas.Member.Controllers.AccountController;
using Categories = PersonalFinance.AdminMvc.Areas.Member.Controllers.CategoriesController;

internal static class AccountWorkflowChecks
{
    public static async Task RunAsync()
    {
        using var sessions = new AdminSessionStore();
        var user = new CurrentUserResponse(71, "Original Fixture", "fixture@example.test", "PHP", ["User"]);
        var session = sessions.Create(new AuthenticationResponse { AccessToken="fixture-only", AccessTokenExpiresAtUtc=DateTime.UtcNow.AddHours(1), RefreshToken="fixture-only", RefreshTokenExpiresAtUtc=DateTime.UtcNow.AddHours(2), User=user }, user)!;
        var auth = new Auth();
        using var services = new ServiceCollection()
            .AddSingleton<IAuthenticationService>(auth)
            .AddSingleton<IUrlHelperFactory, StubUrlHelperFactory>()
            .BuildServiceProvider();
        var context = new DefaultHttpContext { RequestServices=services, User=AuthenticationCoordinator.Principal(session) };
        using var handler = new Api(); using var http = new HttpClient(handler) { BaseAddress=new("https://isolated-account.test/") };
        var controller = new Account(sessions,new AuthenticationApiClient(http)) { ControllerContext=new(new ActionContext(context,new RouteData(),new ControllerActionDescriptor())), TempData=new TempDataDictionary(context,new Temp()) };
        var first = (ViewResult)await controller.Index(default);
        Check(first.ViewName=="Index" && ((MemberAccountViewModel)first.Model!).DisplayName=="Original Fixture", "Overview retrieves the authenticated API profile.");
        controller.ModelState.AddModelError("Profile.FullName","Required");
        var invalid = (ViewResult)await controller.SaveProfile(new(){FullName=""},default);
        Check(invalid.ViewName=="Profile" && context.Response.StatusCode==400 && handler.Writes==0,"Invalid profile returns to the dedicated editor without API writes.");
        controller.ModelState.Clear(); handler.FailWrite=true;
        var failed=(ViewResult)await controller.SaveProfile(new(){FullName="Rejected"},default);
        Check(failed.ViewName=="Profile" && ((MemberAccountViewModel)failed.Model!).Error is not null && session.User.FullName=="Original Fixture","Failed API updates cannot look successful or change the session identity.");
        handler.FailWrite=false; context.Response.StatusCode=200;
        Check(await controller.SaveProfile(new(){FullName="  Updated Fixture  "},default) is RedirectToActionResult {ActionName:"Index"},"Successful update returns to Account.");
        Check(session.User.FullName=="Updated Fixture" && auth.SignedIn,"Successful update refreshes the authenticated name.");
        var fresh=(MemberAccountViewModel)((ViewResult)await controller.Profile(default)).Model!;
        Check(fresh.DisplayName=="Updated Fixture" && fresh.Initials=="UF" && fresh.Email=="fixture@example.test","Subsequent retrieval preserves the new name, initials and unchanged email.");
        var catalog=new Categories(new CategoryApiClient(http),sessions){ControllerContext=controller.ControllerContext,TempData=controller.TempData};
        var categories=(MemberCategoryIndexViewModel)((ViewResult)await catalog.Index(new(),default)).Model!;
        Check(categories.Items.Count==101 && categories.TotalCount==101 && handler.CategoryPages.SequenceEqual(new[]{1,2}),"Category reordering receives the complete API catalog across all pages.");
        handler.DuplicateCategory=true;
        var inconsistent=(MemberCategoryIndexViewModel)((ViewResult)await catalog.Index(new(),default)).Model!;
        Check(inconsistent.Error is not null && inconsistent.Items.Count==0,"An inconsistent catalog cannot be presented as a complete reorderable list.");
        controller.ModelState.AddModelError("Password.ConfirmPassword","Must match");var before=handler.Writes;
        Check(await controller.ChangePassword(new(),default) is ViewResult {ViewName:"Password"} && handler.Writes==before,"Invalid password confirmation never reaches the API.");
        controller.ModelState.Clear(); handler.FailWrite=true;
        Check(await controller.ChangePassword(new(){CurrentPassword="Fixture-current-123!",NewPassword="Fixture-next-123!",ConfirmPassword="Fixture-next-123!"},default) is ViewResult {ViewName:"Password"} && !auth.SignedOut,"A failed password update retains the authenticated session and shows its editor.");
        handler.FailWrite=false;
        Check(await controller.ChangePassword(new(){CurrentPassword="Fixture-current-123!",NewPassword="Fixture-next-123!",ConfirmPassword="Fixture-next-123!"},default) is RedirectToActionResult {ActionName:"Login"} && auth.SignedOut && sessions.Find(session.Id) is null,"A successful synthetic password update drops the MVC session and returns to sign-in.");
        Console.WriteLine("PASS: isolated Account profile retrieval/update persistence, validation/API failures, complete category catalog, and password/session workflows.");
    }
    private static void Check(bool ok,string message){if(!ok)throw new InvalidOperationException(message);}
    // RedirectToAction resolves through MVC's IUrlHelperFactory, which the isolated host does not register.
    private sealed class StubUrlHelperFactory : IUrlHelperFactory
    {
        public IUrlHelper GetUrlHelper(ActionContext context) => new StubUrlHelper(context);
    }
    private sealed class StubUrlHelper(ActionContext context) : IUrlHelper
    {
        public ActionContext ActionContext { get; } = context;
        public string? Action(UrlActionContext actionContext) =>
            "/" + actionContext.Controller + "/" + actionContext.Action;
        public string? Content(string? contentPath) => contentPath;
        public bool IsLocalUrl(string? url) => url is not null && url.StartsWith("/", StringComparison.Ordinal);
        public string? Link(string? routeName, object? values) => routeName;
        public string? RouteUrl(UrlRouteContext routeContext) => "/" + routeContext.RouteName;
    }
    private sealed class Temp:ITempDataProvider { public IDictionary<string,object> LoadTempData(HttpContext c)=>new Dictionary<string,object>(); public void SaveTempData(HttpContext c,IDictionary<string,object> d){} }
    private sealed class Auth:IAuthenticationService
    {
        public bool SignedOut,SignedIn;
        public Task SignInAsync(HttpContext c,string? s,System.Security.Claims.ClaimsPrincipal p,AuthenticationProperties? a){SignedIn=true;return Task.CompletedTask;}
        public Task SignOutAsync(HttpContext c,string? s,AuthenticationProperties? p){SignedOut=true;return Task.CompletedTask;}
        public Task<AuthenticateResult> AuthenticateAsync(HttpContext c,string? s)=>throw new NotSupportedException();
        public Task ChallengeAsync(HttpContext c,string? s,AuthenticationProperties? p)=>throw new NotSupportedException();
        public Task ForbidAsync(HttpContext c,string? s,AuthenticationProperties? p)=>throw new NotSupportedException();
    }
    private sealed class Api:HttpMessageHandler
    {
        public bool FailWrite,DuplicateCategory;public int Writes;public List<int> CategoryPages=[];
        private CurrentUserResponse user=new(71,"Original Fixture","fixture@example.test","PHP",["User"]);
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
        {
            Check(request.Headers.Authorization?.ToString()=="Bearer fixture-only","Every integration request uses the owner token.");
            if(request.RequestUri!.AbsolutePath=="/api/categories")
            {
                var page=Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(request.RequestUri.Query)["Page"].ToString()=="1"?1:2;CategoryPages.Add(page);
                var items=Enumerable.Range(page==1?1:DuplicateCategory?1:101,page==1?100:1).Select(id=>new CategoryResponse{CategoryId=id,Name="Category "+id,Type="Expense",ColorHEX="#6747C7",IsActive=true,IsSystemDefault=false}).ToArray();
                return new(HttpStatusCode.OK){Content=JsonContent.Create(new CategoryPageResponse{Items=items,Page=page,PageSize=100,TotalCount=101})};
            }
            if(request.Method!=HttpMethod.Get)
            {
                Writes++;if(FailWrite)return new(HttpStatusCode.BadRequest);
                if(request.Method==HttpMethod.Post)return new(HttpStatusCode.NoContent);
                var input=await request.Content!.ReadFromJsonAsync<UpdateProfileRequest>(ct);user=user with {FullName=input!.FullName};
            }
            return new(HttpStatusCode.OK){Content=JsonContent.Create(user)};
        }
    }
}
