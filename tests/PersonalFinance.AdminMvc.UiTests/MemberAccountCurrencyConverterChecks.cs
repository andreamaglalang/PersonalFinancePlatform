using System.Net;
using System.Reflection;
using System.Text;
using Microsoft.AspNetCore.Authorization;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

internal static class MemberAccountCurrencyConverterChecks
{
    public static async Task RunAsync()
    {
        Check(typeof(AccountController).GetCustomAttribute<AuthorizeAttribute>()?.Policy == "User",
            "Member Account requires the signed-in User policy.");
        Check(typeof(CurrencyConverterController).GetCustomAttribute<AuthorizeAttribute>()?.Policy == "User",
            "Currency Converter requires the signed-in User policy.");
        var quoteAction = typeof(CurrencyConverterController).GetMethod(nameof(CurrencyConverterController.Quote))!;
        Check(quoteAction.IsDefined(typeof(Microsoft.AspNetCore.Mvc.ValidateAntiForgeryTokenAttribute)),
            "Currency quote form requires antiforgery validation.");
        Check(MemberCurrencyPreferences.Supported.SequenceEqual(
                new[] { "PHP", "USD", "EUR", "JPY", "GBP", "AUD", "CAD", "SGD", "HKD", "CNY", "KRW", "CHF" }),
            "Currency Converter must use the existing twelve-currency allowlist.");

        var layout = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml"));
        var account = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Account", "Index.cshtml"));
        var converter = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "CurrencyConverter", "Index.cshtml"));
        var script = File.ReadAllText(MvcProject.FindFile("wwwroot", "js", "owli-ui.js"));
        Check(layout.Contains("asp-controller=\"Account\" asp-action=\"Index\""), "Member shell must link to its Account page.");
        Check(account.Contains("data-confirm-logout") && account.Contains("asp-controller=\"CurrencyConverter\""),
            "Account keeps secure logout and links to the converter.");
        Check(account.Contains("asp-action=\"Profile\"") && account.Contains("asp-action=\"Password\"")
            && account.Contains("Manage categories") && !account.Contains("Session timing is unavailable")
            && account.Split("Manage categories", StringSplitOptions.None).Length == 2,
            "Account links to dedicated profile/password forms, retains finance settings, and avoids duplicate or technical sections.");
        var login = File.ReadAllText(MvcProject.FindFile("Views", "Account", "Login.cshtml"));
        Check(login.Contains("class=\"login\"") && login.Contains("class=\"lg-art\"")
            && login.Contains("login-stars.svg") && login.Contains("_OwlMark")
            && !login.Contains("Member sign-in")
            && !login.Contains("One OWLI account for members and administrators")
            && !login.Contains("OWLI never asks for your password outside this page")
            && !login.Contains("lg-portal") && login.Contains("data-password-toggle")
            && login.Contains("model='\"lock\"'") && login.Contains("lg-register"),
            "Sign-in retains the two-column OWLI brand panel and form controls while removing only PDF-specified copy.");
        var accountController = typeof(AccountController);
        Check(accountController.GetMethod("SaveProfile")!.IsDefined(typeof(Microsoft.AspNetCore.Mvc.ValidateAntiForgeryTokenAttribute))
            && accountController.GetMethod("ChangePassword")!.IsDefined(typeof(Microsoft.AspNetCore.Mvc.ValidateAntiForgeryTokenAttribute)),
            "Member profile and password updates require antiforgery validation.");
        Check(File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Account", "Appearance.cshtml")).Contains("data-theme-choice=\"system\"") && script.Contains("prefers-color-scheme")
            && script.Contains("themeMode === \"system\""), "System appearance follows browser preference and updates when it changes.");
        Check(converter.Contains("data-currency-swap") && converter.Contains("quote.RateDate")
            && converter.Contains("quote.Source") && converter.Contains("never creates or changes a transaction"),
            "Converter exposes swap, quote date/source and makes its read-only behavior explicit.");

        using var handler = new QuoteHandler();
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://api.example.test/") };
        var client = new ExchangeRateApiClient(http);
        var result = await client.QuoteAsync("USD", "PHP", 1m, "fixture-token", default);
        Check(result is { Status: 200, Quote: { From: "USD", To: "PHP", Amount: 1m, Rate: 56.25m,
            ConvertedAmount: 56.25m, Source: "Frankfurter", RateDate: { Year: 2026, Month: 11, Day: 24 } } },
            "A valid API quote is shown without changing financial records.");
        Check(handler.Method == HttpMethod.Get && handler.Authorization == "Bearer fixture-token"
            && handler.Path!.StartsWith("/api/exchange-rates/quote?", StringComparison.Ordinal),
            "The converter uses the authenticated existing GET quote contract.");
        handler.Status = HttpStatusCode.TooManyRequests;
        Check(await client.QuoteAsync("USD", "PHP", 1m, "fixture-token", default) is { Status: 429, Quote: null },
            "Rate limiting is propagated without retries or writes.");
        Check(await client.QuoteAsync("ZZ", "PHP", 1m, "fixture-token", default) is { Status: 400 }
            && handler.Calls == 2, "Invalid codes are rejected before transport.");

        using var profileHandler = new ProfilePasswordHandler();
        using var authHttp = new HttpClient(profileHandler) { BaseAddress = new Uri("https://api.example.test/") };
        var authClient = new AuthenticationApiClient(authHttp);
        var profile = await authClient.UpdateProfileAsync(new() { FullName = "Updated" }, "fixture-token", default);
        var password = await authClient.ChangePasswordAsync(new() { CurrentPassword = "Current-password!1", NewPassword = "New-password!123" }, "fixture-token", default);
        Check(profile.Succeeded && profile.Value?.FullName == "Updated"
            && password.Succeeded && password.Value == true
            && profileHandler.Calls.SequenceEqual(new[] { "PUT /api/auth/me Bearer fixture-token", "POST /api/auth/change-password Bearer fixture-token" }),
            "Profile/password MVC transport uses the existing authenticated client without adding identity IDs to its requests.");

        Console.WriteLine("PASS: Member Account, local System theme, and authenticated read-only currency conversion contracts.");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class QuoteHandler : HttpMessageHandler
    {
        public int Calls { get; private set; }
        public HttpMethod? Method { get; private set; }
        public string? Path { get; private set; }
        public string? Authorization { get; private set; }
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++;
            Method = request.Method;
            Path = request.RequestUri!.PathAndQuery;
            Authorization = request.Headers.Authorization?.ToString();
            const string body = """{"from":"USD","to":"PHP","amount":1,"rate":56.25,"rateDate":"2026-11-24","requestedDate":"2026-11-24","convertedAmount":56.25,"source":"Frankfurter","retrievedAtUtc":"2026-11-24T00:00:00Z"}""";
            return Task.FromResult(new HttpResponseMessage(Status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class ProfilePasswordHandler : HttpMessageHandler
    {
        public List<string> Calls { get; } = [];
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls.Add($"{request.Method} {request.RequestUri!.AbsolutePath} {request.Headers.Authorization}");
            if (request.Method == HttpMethod.Put)
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"id":1,"fullName":"Updated","email":"member@example.test","baseCurrency":"PHP","roles":["User"]}""", Encoding.UTF8, "application/json")
                });
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NoContent));
        }
    }
}
