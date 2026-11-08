using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PersonalFinance.ApiSecurityTests;

internal static class StartupSmoke
{
    public static async Task<int> RunAsync(bool useHttps = true)
    {
        var root = new DirectoryInfo(Directory.GetCurrentDirectory());
        while (root is not null && !File.Exists(Path.Combine(root.FullName, "PersonalFinancePlatform.slnx"))) root = root.Parent;
        if (root is null) throw new InvalidOperationException("Run the startup smoke check from the solution directory.");
        var api = Path.Combine(root.FullName, "PersonalFinance.Api");
        var dll = Path.Combine(api, "bin", "Debug", "net10.0", "PersonalFinance.Api.dll");
        var directory = Path.Combine(Path.GetTempPath(), "FinanceAuthStartup-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var pfxPath = Path.Combine(directory, "startup-test.pfx");
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=localhost", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var names = new SubjectAlternativeNameBuilder(); names.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(names.Build());
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        var password = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        if (useHttps) await File.WriteAllBytesAsync(pfxPath, cert.Export(X509ContentType.Pfx, password));
        using var process = new Process();
        var info = new ProcessStartInfo("dotnet") { WorkingDirectory = api, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        info.ArgumentList.Add(dll); info.ArgumentList.Add("--urls"); info.ArgumentList.Add(useHttps ? "https://127.0.0.1:0" : "http://127.0.0.1:0");
        info.Environment["ASPNETCORE_ENVIRONMENT"] = "Development";
        info.Environment["Jwt__SigningKeyBase64"] = Convert.ToBase64String(RandomNumberGenerator.GetBytes(64));
        if (useHttps) info.Environment["Kestrel__Certificates__Default__Path"] = pfxPath;
        if (useHttps) info.Environment["Kestrel__Certificates__Default__Password"] = password;
        info.Environment["Logging__EventLog__LogLevel__Default"] = "None";
        process.StartInfo = info;
        var started = false;
        try
        {
            process.Start();
            started = true;
            var stderr = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            string? address = null;
            while (await process.StandardOutput.ReadLineAsync(timeout.Token) is string line)
            {
                var marker = line.IndexOf("Now listening on: ", StringComparison.Ordinal);
                if (marker >= 0) { address = line[(marker + "Now listening on: ".Length)..].Trim(); break; }
            }
            if (address is null) throw new InvalidOperationException("API did not reach its listening state. " + (process.HasExited ? await stderr : ""));
            using var handler = new HttpClientHandler
            {
                // Pin only this ephemeral test certificate; do not disable general certificate validation.
                ServerCertificateCustomValidationCallback = (_, remote, _, _) =>
                    remote is not null && remote.RawData.AsSpan().SequenceEqual(cert.RawData)
            };
            using var client = new HttpClient(handler) { BaseAddress = new Uri(address), Timeout = TimeSpan.FromSeconds(5) };
            var me = await client.GetAsync("/api/auth/me");
            var openApi = await client.GetAsync("/openapi/v1.json");
            if (me.StatusCode != (useHttps ? HttpStatusCode.Unauthorized : HttpStatusCode.BadRequest) || openApi.StatusCode != HttpStatusCode.OK)
                throw new InvalidOperationException($"Unexpected smoke statuses: me={(int)me.StatusCode}; OpenAPI={(int)openApi.StatusCode}.");
            Console.WriteLine(useHttps ? "PASS Real API HTTPS startup: /api/auth/me=401; /openapi/v1.json=200." : "PASS Real API HTTP startup: /api/auth/me=400 (HTTPS required); /openapi/v1.json=200. No credentials submitted.");
            Console.WriteLine("Temporary TLS/signing secrets were process-local; no authenticated/database-writing requests were sent.");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("STARTUP CHECK UNAVAILABLE/FAILED: " + ex.GetType().Name + ": " + ex.Message);
            return 1;
        }
        finally
        {
            if (started && !process.HasExited) { process.Kill(entireProcessTree: true); await process.WaitForExitAsync(); }
            if (File.Exists(pfxPath)) File.Delete(pfxPath);
            Directory.Delete(directory);
        }
    }
}

