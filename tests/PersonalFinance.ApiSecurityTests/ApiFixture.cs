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

namespace PersonalFinance.ApiSecurityTests;

internal sealed record ApiReply(int Status, string Body, string? CacheControl);

/// <summary>Runs actual MVC/routing/JwtBearer/authorization/rate-limiting middleware in memory.
/// Identity and token persistence use test doubles; no SQL Server or listening socket is involved.</summary>
internal sealed class ApiFixture : IAsyncDisposable
{
    private readonly WebApplication host;
    private readonly RequestDelegate pipeline;
    public FakeIdentityStore Users { get; } = new();
    public FakeSessionStore Store { get; } = new();
    public TestClock Clock { get; } = new();
    public JwtSettings Settings { get; }
    public IServiceProvider Services => host.Services;
    public ApiFixture()
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
        builder.Services.AddControllers().AddApplicationPart(typeof(AuthController).Assembly).AddApplicationPart(typeof(TestAuthorizationController).Assembly);
        builder.Services.AddInfrastructure(builder.Configuration);
        builder.Services.AddApiAuthentication(builder.Configuration);
        builder.Services.AddProblemDetails();
        builder.Services.AddExceptionHandler<SafeExceptionHandler>();
        builder.Services.AddSingleton<IUserStore<ApplicationUser>>(Users);
        builder.Services.AddSingleton<IRoleStore<IdentityRole<int>>>(new FakeRoleStore());
        builder.Services.AddSingleton<IAuthSessionStore>(Store);
        builder.Services.AddSingleton<TimeProvider>(Clock);
        host = builder.Build();
        host.Services.GetRequiredService<IStartupValidator>().Validate();
        var app = new ApplicationBuilder(host.Services);
        app.UseExceptionHandler();
        app.UseMiddleware<AuthTransportMiddleware>();
        app.UseRouting();
        app.UseRateLimiter();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseEndpoints(endpoints => endpoints.MapControllers());
        pipeline = app.Build();
    }

    public async Task<ApiReply> SendAsync(string method, string path, object? body = null, string? bearer = null, string scheme = "https")
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
        if (body is not null)
        {
            var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(body, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
            context.Request.ContentType = "application/json";
            context.Request.ContentLength = bytes.Length;
            context.Request.Body = new MemoryStream(bytes);
        }
        if (bearer is not null) context.Request.Headers.Authorization = "Bearer " + bearer;
        var accessor = host.Services.GetRequiredService<IHttpContextAccessor>();
        accessor.HttpContext = context;
        try { await pipeline(context); }
        finally { accessor.HttpContext = null; }
        context.Response.Body.Position = 0;
        var text = await new StreamReader(context.Response.Body).ReadToEndAsync();
        return new ApiReply(context.Response.StatusCode, text, context.Response.Headers.CacheControl);
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
    public ValueTask DisposeAsync() => host.DisposeAsync();
}

