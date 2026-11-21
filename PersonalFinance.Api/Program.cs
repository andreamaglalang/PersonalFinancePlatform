
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure;

var builder = WebApplication.CreateBuilder(args);

// Opt-in only; production and launches without this variable retain existing hosting behavior.
var developmentThumbprint = Environment.GetEnvironmentVariable("OWLI_DEV_CERT_THUMBPRINT");
using var developmentCertificate = builder.Environment.IsDevelopment()
    && !string.IsNullOrEmpty(developmentThumbprint)
        ? LoadDevelopmentCertificate(developmentThumbprint)
        : null;

if (developmentCertificate is not null)
{
    if (builder.Configuration.GetSection("Kestrel:Endpoints").GetChildren().Any())
        throw new InvalidOperationException("OWLI development HTTPS cannot be combined with configured Kestrel endpoints.");

    // Suppress launch-profile URLs. Do not expose HTTP or non-loopback interfaces in this mode.
    builder.WebHost.UseUrls(Array.Empty<string>()).PreferHostingUrls(false);
    builder.WebHost.ConfigureKestrel(options =>
    {
        // Replace the default reloadable endpoint loader so later JSON reloads cannot add listeners.
        options.Configure(new ConfigurationBuilder().Build(), reloadOnChange: false);
        options.ListenLocalhost(7027, listener => listener.UseHttps(developmentCertificate));
    });
}

builder.Services.AddControllers();
builder.Services.AddInfrastructure(builder.Configuration);
builder.Services.AddApiAuthentication(builder.Configuration);
builder.Services.AddProblemDetails();
builder.Services.AddExceptionHandler<SafeExceptionHandler>();

builder.Logging.AddFilter(
    "Microsoft.AspNetCore.Hosting.Diagnostics",
    LogLevel.Warning);

builder.Services.AddOpenApi();

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.UseExceptionHandler();

if (!app.Environment.IsDevelopment())
{
    app.UseHsts();
}

app.UseMiddleware<AuthTransportMiddleware>();
app.UseMiddleware<FinanceTransportMiddleware>();

app.UseHttpsRedirection();
app.UseRouting();
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();

app.Run();

static X509Certificate2 LoadDevelopmentCertificate(string requestedThumbprint)
{
    // Permit conventional separators, then require a complete Windows SHA-1 certificate identifier.
    var normalized = new string(requestedThumbprint
        .Where(character => !char.IsWhiteSpace(character) && character != ':').ToArray()).ToUpperInvariant();
    if (normalized.Length != 40 || normalized.Any(character => !Uri.IsHexDigit(character)))
        throw new InvalidOperationException("OWLI development certificate thumbprint must contain exactly 40 hexadecimal digits.");

    using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
    store.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
    var certificates = store.Certificates;
    X509Certificate2? selected = null;
    try
    {
        var matches = certificates.Cast<X509Certificate2>()
            .Where(candidate => string.Equals(candidate.Thumbprint, normalized, StringComparison.OrdinalIgnoreCase))
            .ToArray();
        if (matches.Length != 1)
            throw new InvalidOperationException("OWLI development HTTPS requires exactly one matching current-user certificate.");

        var certificate = matches[0];
        var now = DateTime.UtcNow;
        if (now < certificate.NotBefore.ToUniversalTime() || now >= certificate.NotAfter.ToUniversalTime())
            throw new InvalidOperationException("OWLI development certificate is not currently valid.");
        if (!certificate.HasPrivateKey)
            throw new InvalidOperationException("OWLI development certificate requires a private key.");
        if (!certificate.MatchesHostname("localhost", allowWildcards: false, allowCommonName: false))
            throw new InvalidOperationException("OWLI development certificate requires an explicit localhost SAN.");

        var usages = certificate.Extensions.Cast<X509Extension>()
            .Where(extension => extension.Oid?.Value == "2.5.29.37").ToArray();
        if (usages.Length != 1 || !new X509EnhancedKeyUsageExtension(usages[0], usages[0].Critical)
            .EnhancedKeyUsages.Cast<Oid>().Any(oid => oid.Value == "1.3.6.1.5.5.7.3.1"))
            throw new InvalidOperationException("OWLI development certificate requires Server Authentication usage.");

        var keyUsages = certificate.Extensions.Cast<X509Extension>()
            .Where(extension => extension.Oid?.Value == "2.5.29.15").ToArray();
        if (keyUsages.Length > 1 || (keyUsages.Length == 1
            && !new X509KeyUsageExtension(keyUsages[0], keyUsages[0].Critical).KeyUsages
                .HasFlag(X509KeyUsageFlags.DigitalSignature)))
            throw new InvalidOperationException("OWLI development certificate does not permit TLS digital signatures.");

        selected = certificate;
        return certificate;
    }
    finally
    {
        // The selected certificate is disposed only after app.Run returns (or host setup fails).
        foreach (var certificate in certificates)
            if (!ReferenceEquals(certificate, selected)) certificate.Dispose();
    }
}
