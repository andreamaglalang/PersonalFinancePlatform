using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authorization;
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
builder.Services.AddMemoryCache();
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
builder.Services.AddSingleton<AdminSessionStore>();
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
builder.Services.AddHttpClient<AdminApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 1024 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddHttpClient<PersonalFinanceApiClient>((provider, http) =>
{
    http.BaseAddress = new Uri(provider.GetRequiredService<IOptions<BackendApiOptions>>().Value.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(10);
    http.MaxResponseContentBufferSize = 1024 * 1024;
    http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
}).ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
{
    AllowAutoRedirect = false, UseCookies = false, PooledConnectionLifetime = TimeSpan.FromMinutes(5)
}).RemoveAllLoggers();
builder.Services.AddScoped<AuthenticationCoordinator>();
builder.Services.AddScoped<AdminCookieEvents>();
builder.Services.AddKeyedSingleton<AdminSessionStore>(PersonalSession.Scheme);
builder.Services.AddKeyedScoped<AuthenticationCoordinator>(PersonalSession.Scheme, (services, key) =>
    new AuthenticationCoordinator(services.GetRequiredKeyedService<AdminSessionStore>(key),
        services.GetRequiredService<IAuthenticationApiClient>(),
        PersonalFinance.AdminMvc.Services.Contracts.ContractValidation.RegularUser));
builder.Services.AddScoped<PersonalCookieEvents>();
builder.Services.AddAuthentication(AdminSessionStore.Scheme)
    .AddCookie(AdminSessionStore.Scheme, options =>
    {
        options.Cookie.Name = "__Host-OWLI.Admin";
        options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Cookie.Path = "/";
        options.LoginPath = "/Account/Login";
        options.AccessDeniedPath = "/Account/AccessDenied";
        options.SlidingExpiration = false;
        options.ExpireTimeSpan = TimeSpan.FromHours(8);
        options.EventsType = typeof(AdminCookieEvents);
    })
    .AddCookie(PersonalSession.Scheme, options =>
    {
        options.Cookie.Name = "__Host-OWLI.Personal";
        options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Cookie.Path = "/";
        options.LoginPath = "/Personal/Login";
        options.AccessDeniedPath = "/Personal/AccessDenied";
        options.SlidingExpiration = false;
        options.ExpireTimeSpan = TimeSpan.FromHours(8);
        options.EventsType = typeof(PersonalCookieEvents);
    });
builder.Services.AddOptions<CookieAuthenticationOptions>(AdminSessionStore.Scheme)
    .Configure<AdminSessionStore>((options, store) => options.SessionStore = store);
builder.Services.AddOptions<CookieAuthenticationOptions>(PersonalSession.Scheme)
    .Configure<IServiceProvider>((options, services) =>
        options.SessionStore = services.GetRequiredKeyedService<AdminSessionStore>(PersonalSession.Scheme));
var administrators = new AuthorizationPolicyBuilder(AdminSessionStore.Scheme)
    .RequireAuthenticatedUser().RequireRole("Administrator").Build();
builder.Services.AddAuthorization(options =>
{
    options.AddPolicy("Administrator", administrators);
    options.AddPolicy(PersonalSession.Policy, policy => policy.AddAuthenticationSchemes(PersonalSession.Scheme)
        .RequireAuthenticatedUser().RequireRole("User").RequireAssertion(context => !context.User.IsInRole("Administrator")));
    options.DefaultPolicy = administrators;
    options.FallbackPolicy = administrators;
});
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
app.MapControllerRoute("default", "{controller=Dashboard}/{action=Index}/{id?}");
app.Run();
