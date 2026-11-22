using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Configuration;
using PersonalFinance.AdminMvc.Services;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddControllersWithViews(options =>
    options.Filters.Add(new AutoValidateAntiforgeryTokenAttribute()));
builder.Services.AddAntiforgery(options =>
{
    options.Cookie.Name = "__Host-OWLI.Antiforgery";
    options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
    options.Cookie.HttpOnly = true;
    options.Cookie.SameSite = SameSiteMode.Strict;
    options.Cookie.Path = "/";
});
builder.Services.AddOptions<BackendApiOptions>()
    .Bind(builder.Configuration.GetSection(BackendApiOptions.Section))
    .Validate(BackendApiOptions.IsValid, "BackendApi:BaseUrl must be an absolute HTTPS origin without user information, path, query or fragment.")
    .ValidateOnStart();
builder.Services.AddHttpClient<IAuthenticationApiClient, AuthenticationApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 64 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddHttpContextAccessor();
builder.Services.AddHttpClient<ICategoryApiClient, CategoryApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 256 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddScoped<ICategoryManagementService, CategoryManagementService>();
builder.Services.AddHttpClient<BudgetApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 256 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddHttpClient<ReportsApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 1024 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddOwliAuthentication();
builder.Services.AddOwliAuthorization();
// Suppress framework exception/request diagnostics that might include submitted values or URLs.
builder.Logging.AddFilter("Microsoft.AspNetCore.Diagnostics", LogLevel.None);
builder.Logging.AddFilter("Microsoft.AspNetCore.Hosting.Diagnostics", LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.AspNetCore.Mvc", LogLevel.Warning);

var app = builder.Build();
app.UseExceptionHandler("/Account/Error");
if (!app.Environment.IsDevelopment()) app.UseHsts();
app.Use(async (context, next) =>
{
    if (!context.Request.IsHttps && !HttpMethods.IsGet(context.Request.Method)
        && !HttpMethods.IsHead(context.Request.Method))
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        return;
    }
    await next();
});
app.UseHttpsRedirection();
app.Use(async (context, next) =>
{
    context.Response.Headers.CacheControl = "no-store";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Response.Headers["Referrer-Policy"] = "no-referrer";
    context.Response.Headers["Content-Security-Policy"] =
        "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
    // Do not accept credentials on HTTP even if HTTPS redirection has no configured destination.
    if (!context.Request.IsHttps) { context.Response.StatusCode = 400; return; }
    await next();
});
app.UseStaticFiles();
app.UseRouting();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllerRoute(name: "member",
    pattern: "{area:Exists}/{controller=Home}/{action=Index}/{id?}");
app.MapControllerRoute("default", "{controller=Entry}/{action=Index}/{id?}");
app.Run();
