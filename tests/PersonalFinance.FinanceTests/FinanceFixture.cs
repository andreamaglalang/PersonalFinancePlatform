using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.DependencyInjection.Extensions;
using PersonalFinance.Infrastructure.Persistence;
using PersonalFinance.Infrastructure.Finance;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Api.Controllers;
using PersonalFinance.AuthenticationTests;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.FinanceTests;

internal sealed record ApiReply(int Status, string Body, string? CacheControl)
{
    public IReadOnlyDictionary<string, string[]> Headers { get; init; } = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
    private string? Header(string name) => Headers.TryGetValue(name, out var values) ? values.FirstOrDefault() : null;
    public string? ETag => Header("ETag");
    public string? Location => Header("Location");
    public string? ContentType => Header("Content-Type");
    public string? Pragma => Header("Pragma");
}

/// <summary>Runs actual MVC/routing/JwtBearer/authorization/rate-limiting middleware in memory.
/// Identity and token persistence use test doubles; no SQL Server or listening socket is involved.</summary>
internal sealed class FinanceFixture : IAsyncDisposable
{
    private readonly WebApplication host;
    private readonly RequestDelegate pipeline;
    public FakeIdentityStore Users { get; } = new();
    public FakeSessionStore Store { get; } = new();
    public TestClock Clock { get; } = new() { Now = new DateTimeOffset(2026, 11, 3, 12, 0, 0, TimeSpan.Zero) };
    public SavingsRowVersionSimulation RowVersions { get; } = new();
    public JwtSettings Settings { get; }
    public IServiceProvider Services => host.Services; public FakeFx Fx { get; } = new();
    public FinanceFixture()
    {
        Settings = new JwtSettings { Issuer = "test-issuer", Audience = "test-audience", SigningKeyBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(64)) };
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            EnvironmentName = "Testing", ApplicationName = typeof(AuthController).Assembly.GetName().Name
        });
        builder.Logging.ClearProviders();
        builder.Configuration.Sources.Clear();
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["Jwt:Issuer"] = Settings.Issuer, ["Jwt:Audience"] = Settings.Audience, ["Jwt:SigningKeyBase64"] = Settings.SigningKeyBase64
        });
        builder.Services.AddControllers().AddApplicationPart(typeof(AuthController).Assembly);
        builder.Services.AddInfrastructure(builder.Configuration);
        builder.Services.AddApiAuthentication(builder.Configuration);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        // Match zero-skew lifetime semantics against the same deterministic test clock used to issue tokens.
        builder.Services.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, options =>
            options.TokenValidationParameters.LifetimeValidator = (notBefore, expires, _, _) =>
                expires.HasValue && Clock.Now.UtcDateTime < expires.Value
                && (!notBefore.HasValue || Clock.Now.UtcDateTime >= notBefore.Value));
        builder.Services.AddProblemDetails();
        builder.Services.AddExceptionHandler<SafeExceptionHandler>();
        builder.Services.AddSingleton<IUserStore<ApplicationUser>>(Users);
        builder.Services.AddSingleton<IRoleStore<IdentityRole<int>>>(new FakeRoleStore());
        builder.Services.AddSingleton<IAuthSessionStore>(Store);
        builder.Services.AddSingleton<TimeProvider>(Clock);
        builder.Services.RemoveAll<DbContextOptions<ApplicationDbContext>>();
        builder.Services.RemoveAll<IDbContextOptionsConfiguration<ApplicationDbContext>>();
        var databaseName = Guid.NewGuid().ToString();
        builder.Services.AddDbContext<ApplicationDbContext>(o => o.UseInMemoryDatabase(databaseName).ReplaceService<IModelCustomizer, SavingsInMemoryModelCustomizer>().AddInterceptors(new CategoryComputedValueSimulation(), RowVersions));
        builder.Services.AddSingleton<IFrankfurterClient>(Fx);
        builder.Services.AddSingleton<IFinancialWriteScope, MemoryWriteScope>();
        host = builder.Build();
        host.Services.GetRequiredService<IStartupValidator>().Validate();
        var app = new ApplicationBuilder(host.Services);
        app.UseExceptionHandler();
        app.UseMiddleware<AuthTransportMiddleware>(); app.UseMiddleware<FinanceTransportMiddleware>();
        app.UseRouting();
        app.UseRateLimiter();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseEndpoints(endpoints => endpoints.MapControllers());
        pipeline = app.Build();
    }

    public async Task<ApiReply> SendAsync(string method, string path, object? body = null, string? bearer = null, string scheme = "https", IReadOnlyDictionary<string, string[]>? headers = null, string? rawJson = null)
    {
        using var scope = host.Services.CreateScope();
        var context = new DefaultHttpContext { RequestServices = scope.ServiceProvider };
        context.Request.Method = method;
        context.Request.Scheme = scheme;
        context.Request.Host = new HostString("localhost");
        var separator = path.IndexOf('?');
        context.Request.Path = separator < 0 ? path : path[..separator];
        if (separator >= 0) context.Request.QueryString = new QueryString(path[separator..]);
        context.Connection.RemoteIpAddress = IPAddress.Loopback;
        context.Response.Body = new MemoryStream();
        if (body is not null && rawJson is not null) throw new ArgumentException("Specify either a body or raw JSON.");
        if (body is not null || rawJson is not null)
        {
            var bytes = Encoding.UTF8.GetBytes(rawJson ?? JsonSerializer.Serialize(body, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
            context.Request.ContentType = "application/json";
            context.Request.ContentLength = bytes.Length;
            context.Request.Body = new MemoryStream(bytes);
        }
        if (bearer is not null) context.Request.Headers.Authorization = "Bearer " + bearer;
        if (headers is not null)
            foreach (var header in headers) context.Request.Headers[header.Key] = header.Value;
        var accessor = host.Services.GetRequiredService<IHttpContextAccessor>();
        accessor.HttpContext = context;
        try { await pipeline(context); }
        finally { accessor.HttpContext = null; }
        context.Response.Body.Position = 0;
        var text = await new StreamReader(context.Response.Body).ReadToEndAsync();
        return new ApiReply(context.Response.StatusCode, text, context.Response.Headers.CacheControl)
        {
            Headers = context.Response.Headers.ToDictionary(h => h.Key, h => h.Value.Select(v => v ?? string.Empty).ToArray(), StringComparer.OrdinalIgnoreCase)
        };
    }

    public async Task<int> RegisterAsync()
    {
        var reply = await SendAsync("POST", "/api/auth/register", new { fullName = "Test Person", email = "person@example.test", password = Fixture.Password, confirmPassword = Fixture.Password });
        if (reply.Status != 201) throw new InvalidOperationException("Test registration returned " + reply.Status + ": " + reply.Body);
        return JsonDocument.Parse(reply.Body).RootElement.GetProperty("id").GetInt32();
    }
    public async Task<string> LoginAsync()
    {
        await RegisterAsync();
        var reply = await SendAsync("POST", "/api/auth/login", new { email = "person@example.test", password = Fixture.Password });
        if (reply.Status != 200) throw new InvalidOperationException("Test login returned " + reply.Status);
        return JsonDocument.Parse(reply.Body).RootElement.GetProperty("accessToken").GetString()!;
    }
    public string Issue(int id, DateTimeOffset? issuedAt = null, string? issuer = null, string? audience = null, string? signingKey = null)
    {
        var options = new JwtSettings { Issuer = issuer ?? Settings.Issuer, Audience = audience ?? Settings.Audience, SigningKeyBase64 = signingKey ?? Settings.SigningKeyBase64 };
        var clock = new TestClock { Now = issuedAt ?? Clock.Now };
        var roles = Users.Membership[id].Select(r => r == "USER" ? "User" : "Administrator").ToArray();
        return new JwtTokenIssuer(Options.Create(options), clock).Issue(Users.Users[id], roles).Value;
    }
    public async Task SeedAsync()
    {
        for (var id = 1; id <= 3; id++)
        {
            var u = new ApplicationUser { Id = id, FullName = "Test", Email = $"u{id}@example.test", NormalizedEmail = $"U{id}@EXAMPLE.TEST", UserName = $"u{id}", BaseCurrency = "PHP", SecurityStamp = Guid.NewGuid().ToString() };
            Users.Users[id] = u; Users.Membership[id] = id == 3 ? ["USER", "ADMINISTRATOR"] : ["USER"];
        }
        await DbAsync(async db => {
            db.Users.AddRange(Users.Users.Values);
            db.Categories.AddRange(
                new() { CategoryId=1,UserId=1,Name="Salary",Type=PersonalFinance.Domain.Enums.TransactionType.Income },
                new() { CategoryId=2,UserId=1,Name="Food",Type=PersonalFinance.Domain.Enums.TransactionType.Expense },
                new() { CategoryId=3,UserId=2,Name="Private",Type=PersonalFinance.Domain.Enums.TransactionType.Expense },
                new() { CategoryId=4,Name="Default",Type=PersonalFinance.Domain.Enums.TransactionType.Expense },
                new() { CategoryId=5,UserId=1,Name="Archived",Type=PersonalFinance.Domain.Enums.TransactionType.Expense,IsActive=false });
            await db.SaveChangesAsync();
        });
    }
    public async Task DbAsync(Func<ApplicationDbContext,Task> action)
    { using var scope = Services.CreateScope(); await action(scope.ServiceProvider.GetRequiredService<ApplicationDbContext>()); }
    public ValueTask DisposeAsync() => host.DisposeAsync();
}


