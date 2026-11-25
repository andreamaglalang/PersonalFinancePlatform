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

internal static class MemberCurrencyChecks
{
    private const string AndroidCurrencies = "PHP,USD,EUR,JPY,GBP,AUD,CAD,SGD,HKD,CNY,KRW,CHF";

    public static void Run()
    {
        var store = File.ReadAllText(MvcProject.FindFile("Services", "MemberCurrencyPreferences.cs"));
        var fx = File.ReadAllText(MvcProject.FindFile("Services", "MemberFxReview.cs"));
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "CurrencyController.cs"));
        var activity = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var createView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Create.cshtml"));
        var historyView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Index.cshtml"));
        var prefsView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Currency", "Index.cshtml"));
        var draft = File.ReadAllText(MvcProject.FindFile("ViewModels", "MemberTransactionDraftViewModel.cs"));

        // The supported set must match Android's SupportedCurrencies exactly (order included).
        var android = AndroidCurrencies.Split(',');
        var block = store.Substring(store.IndexOf("string[] Supported", StringComparison.Ordinal));
        block = block.Substring(0, block.IndexOf("];", StringComparison.Ordinal));
        var offered = System.Text.RegularExpressions.Regex.Matches(block, "\"([A-Z]{3})\"")
            .Select(match => match.Groups[1].Value).ToArray();
        if (offered.SequenceEqual(android) is false)
            throw new InvalidOperationException(
                $"The MVC currency list must match Android's SupportedCurrencies (found {string.Join(',', offered)}).");
        if (store.Contains("AccountCurrency = \"PHP\"") is false)
            throw new InvalidOperationException("The account currency must be fixed at PHP.");

        // Browser-local, account-scoped, and free of tokens or financial data.
        if (store.Contains("Request.Cookies[CookieName]") is false || store.Contains("Response.Cookies.Append") is false)
            throw new InvalidOperationException("Preferences must persist in a browser cookie.");
        if (store.Contains("string.Equals(parts[1], userId") is false)
            throw new InvalidOperationException("A cookie written for one account must never be read for another.");
        if (store.Contains("access_token") || store.Contains("refresh_token") || store.Contains("AmountBase"))
            throw new InvalidOperationException("The preference cookie must not carry tokens or financial amounts.");
        if (store.Contains("Expires = DateTimeOffset.UtcNow.AddYears(1)") is false)
            throw new InvalidOperationException("Preferences must survive a browser restart (persistent cookie).");

        // Preferences screen: member-scoped, antiforgery-protected, read-only account currency, Frankfurter credit.
        if (controller.Contains("AuthorizationConfiguration.UserPolicy") is false || controller.Contains("AdministratorPolicy"))
            throw new InvalidOperationException("Currency preferences must be member-scoped.");
        if (controller.Contains("ValidateAntiForgeryToken") is false)
            throw new InvalidOperationException("Saving preferences must be antiforgery-protected.");
        if (prefsView.Contains("readonly") is false || prefsView.Contains("Account currency") is false)
            throw new InvalidOperationException("The account currency must be displayed read-only.");
        if (prefsView.Contains("Frankfurter") is false)
            throw new InvalidOperationException("The rates screen must credit Frankfurter.");
        if (prefsView.Contains("name=\"DefaultCurrency\"") is false
            || prefsView.Contains("name=\"ShowOriginalAmounts\"") is false
            || prefsView.Contains("name=\"ReviewRateBeforeSaving\"") is false)
            throw new InvalidOperationException("All three preferences must be posted.");

        // Add Transaction: the saved currency preselects, and the list is fixed rather than free text.
        if (activity.Contains("MemberCurrencyPreferences.Read(HttpContext, protection") is false)
            throw new InvalidOperationException("The create form must read the member's saved preference.");
        if (activity.Contains("MemberCurrencyPreferences.Normalise(preferences.DefaultCurrency)") is false)
            throw new InvalidOperationException("The saved default currency must preselect on the create form.");
        if (createView.Contains("name=\"CurrencyCode\"") is false || createView.Contains("<select") is false)
            throw new InvalidOperationException("Currency must be chosen from the fixed supported list.");
        if (draft.Contains("BindNever") is false)
            throw new InvalidOperationException("Preference wiring must not be client-bindable.");

        // Rate review is mandatory for a foreign currency, and re-validated on confirmation.
        if (activity.Contains("ReviewRateBeforeSaving") is false
            || activity.Contains("NeedsRate(model.CurrencyCode, accountCurrency)") is false)
            throw new InvalidOperationException("Review must apply only to foreign currencies.");
        if (activity.Contains("if (!model.FxConfirmed)") is false)
            throw new InvalidOperationException("A foreign-currency save must stop for explicit confirmation first.");
        if (fx.Contains("ProtectorPurpose + \"|\" + userId") is false)
            throw new InvalidOperationException("The rate confirmation must be bound to the signed-in account.");
        if (activity.Contains("MemberFxReview.Matches(protection, model.PostedFxProof") is false)
            throw new InvalidOperationException("Confirmation must be re-validated against a fresh quote.");
        if (createView.Contains("name=\"FxProof\"") is false || createView.Contains("name=\"FxConfirmed\"") is false)
            throw new InvalidOperationException("The review panel must post the confirmation and its proof.");
        var confirmationInput = System.Text.RegularExpressions.Regex.Match(createView,
            "<input[^>]*name=\\\"FxConfirmed\\\"[^>]*>", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (!confirmationInput.Success || confirmationInput.Value.Contains("checked", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("FX confirmation must require an explicit user check.");
        if (createView.Contains("Confirm and save") is false)
            throw new InvalidOperationException("The submit action must state that it confirms the rate.");
        if (createView.Contains("quote.Source") is false)
            throw new InvalidOperationException("The reviewed quote must show its source attribution.");

        // History shows the base amount, honouring the preference.
        if (historyView.Contains("Model.ShowOriginalAmounts") is false
            || historyView.Contains("item.AmountBase") is false || historyView.Contains("Model.BaseCurrency") is false)
            throw new InvalidOperationException("History must show original and PHP base amounts per preference.");
        if (activity.Contains("model.ShowOriginalAmounts = currencyPreferences.ShowOriginalAmounts") is false
            || activity.Contains("model.BaseCurrency = MemberSessionGate.BaseCurrency") is false)
            throw new InvalidOperationException("The history view must receive the preference and account currency.");

        Console.WriteLine("PASS: currency preferences are browser-local, account-scoped and wired into the transaction flow.");

        // --- Live round-trip assertions (this is the defect that shipped: Pack wrote five segments while the
        //     reader demanded four, so every read fell back to defaults and toggles looked unsaved). ---
        const string Owner = "7";
        foreach (var currency in android)
        {
            foreach (var show in new[] { true, false })
            {
                foreach (var review in new[] { true, false })
                {
                    var saved = new MemberCurrencyPreferences.Preferences(currency, show, review);
                    if (!MemberCurrencyPreferences.TryUnpack(
                            MemberCurrencyPreferences.Pack(Owner, saved), Owner, out var restored))
                        throw new InvalidOperationException($"Pack/TryUnpack failed for {currency} {show} {review}.");
                    if (!restored.Equals(saved))
                        throw new InvalidOperationException(
                            $"Preferences did not survive the round-trip: wanted {saved}, got {restored}.");
                }
            }
        }

        // One account must never read another account's settings.
        var mine = new MemberCurrencyPreferences.Preferences("USD", true, true);
        if (MemberCurrencyPreferences.TryUnpack(MemberCurrencyPreferences.Pack(Owner, mine), "8", out var foreign)
            || !foreign.Equals(MemberCurrencyPreferences.Preferences.Defaults))
            throw new InvalidOperationException("Another account's preferences were readable, or defaults were not used.");

        // Corrupt, truncated and wrong-version payloads fail closed to defaults.
        foreach (var bad in new[] { "", "v1", "v1|7|USD", "v2|7|USD|1|1", "garbage" })
        {
            if (MemberCurrencyPreferences.TryUnpack(bad, Owner, out var parsed)
                && !parsed.Equals(MemberCurrencyPreferences.Preferences.Defaults))
                throw new InvalidOperationException($"A malformed payload was accepted: '{bad}'.");
        }

        // The cookie value is binary, so the URL-safe codec must round-trip any length.
        for (var length = 1; length <= 97; length++)
        {
            var bytes = new byte[length];
            for (var i = 0; i < length; i++) bytes[i] = (byte)(i * 31 + 7);
            var encoded = MemberCurrencyPreferences.Base64Url.Encode(bytes);
            if (encoded.Any(c => c is '+' or '/' or '=')
                || !MemberCurrencyPreferences.Base64Url.Decode(encoded).SequenceEqual(bytes))
                throw new InvalidOperationException($"Base64Url failed at length {length}.");
        }

        Console.WriteLine("PASS: currency preference pack/unpack round-trips for every currency, toggle and account.");

        // Render the real Add Transaction view and assert exactly one option is selected, matching the model.
        var html = RenderCreate(new MemberTransactionDraftViewModel
        {
            Type = "Expense",
            CategoryId = 4,
            Amount = 10m,
            CurrencyCode = "USD",
            TransactionDate = new DateOnly(2026, 11, 24),
            Currencies = MemberCurrencyPreferences.Supported,
            BaseCurrency = "PHP",
            Categories =
            [
                new CategoryResponse { CategoryId = 4, Name = "OWLI Demo Test", Type = "Expense",
                    ColorHEX = "#8B5CF6", IsActive = true, IsSystemDefault = false }
            ]
        }).GetAwaiter().GetResult();
        var start = html.IndexOf("<select id=\"CurrencyCode\"", StringComparison.Ordinal);
        var end = html.IndexOf("</select>", start, StringComparison.Ordinal);
        var selectMarkup = html[start..(end + 9)];
        var selectedCount = System.Text.RegularExpressions.Regex.Matches(selectMarkup, "selected=").Count;
        if (selectedCount != 1)
            throw new InvalidOperationException(
                $"Exactly one currency option must be selected; found {selectedCount}. Markup: {selectMarkup}");
        if (!selectMarkup.Contains("selected=\"selected\" value=\"USD\""))
            throw new InvalidOperationException("The saved currency must preselect in the Add Transaction dropdown.");
        if (!html.Contains("Selected currency: USD"))
            throw new InvalidOperationException("The create form must identify the selected transaction currency.");

        // Full cookie transport round-trip: Write -> Set-Cookie header -> Cookie header -> Read.
        var services = scopeForCookie();
        var provider = services.GetRequiredService<IDataProtectionProvider>();
        var writeContext = new DefaultHttpContext { RequestServices = services };
        MemberCurrencyPreferences.Write(writeContext, provider, Owner, mine);
        var setCookie = writeContext.Response.Headers["Set-Cookie"].ToString();
        if (setCookie.Contains("path=/") is false)
            throw new InvalidOperationException("The preference cookie must be scoped to the whole site.");

        var readContext = new DefaultHttpContext { RequestServices = services };
        readContext.Request.Headers["Cookie"] = setCookie.Split(';')[0];
        var transported = MemberCurrencyPreferences.Read(readContext, provider, Owner);
        if (!transported.Equals(mine))
            throw new InvalidOperationException(
                $"Preferences did not survive the real cookie transport: wanted {mine}, got {transported}.");
    }

    private static IServiceProvider scopeForCookie()
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ApplicationName = typeof(MemberCurrencyPreferences).Assembly.GetName().Name,
            EnvironmentName = "Testing"
        });
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        return builder.Build().Services;
    }

    private static async Task<string> RenderCreate(MemberTransactionDraftViewModel model)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ApplicationName = typeof(MemberCurrencyPreferences).Assembly.GetName().Name,
            EnvironmentName = "Testing"
        });
        builder.Services.AddControllersWithViews()
            .AddApplicationPart(typeof(MemberCurrencyPreferences).Assembly);
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        builder.Services.AddSingleton<IUrlHelperFactory, FixtureUrlFactory>();
        await using var app = builder.Build(); // Compiled Razor only; never starts a listening host.
        using var scope = app.Services.CreateScope();

        var context = new DefaultHttpContext { RequestServices = scope.ServiceProvider };
        var routes = new RouteData();
        routes.Values["area"] = "Member";
        routes.Values["controller"] = "Activity";
        routes.Values["action"] = "Create";
        var action = new ActionContext(context, routes, new ActionDescriptor
        {
            RouteValues = new Dictionary<string, string?>
            {
                ["area"] = "Member", ["controller"] = "Activity", ["action"] = "Create"
            }
        });
        var engine = scope.ServiceProvider.GetRequiredService<IRazorViewEngine>();
        var view = engine.GetView(null, "/Areas/Member/Views/Activity/Create.cshtml", true);
        if (!view.Success || view.View is null) throw new InvalidOperationException("Compiled create view not found.");
        using var writer = new StringWriter();
        var data = new ViewDataDictionary<MemberTransactionDraftViewModel>(
            new EmptyModelMetadataProvider(), new ModelStateDictionary()) { Model = model };
        await view.View.RenderAsync(new ViewContext(action, view.View, data,
            new TempDataDictionary(context, new FixtureTempData()), writer, new HtmlHelperOptions()));
        return writer.ToString();
    }

    private sealed class FixtureTempData : ITempDataProvider
    {
        public IDictionary<string, object> LoadTempData(HttpContext context) => new Dictionary<string, object>();
        public void SaveTempData(HttpContext context, IDictionary<string, object> values) { }
    }

    private sealed class FixtureUrlFactory : IUrlHelperFactory
    {
        public IUrlHelper GetUrlHelper(ActionContext context) => new FixtureUrl(context);
    }

    private sealed class FixtureUrl(ActionContext context) : IUrlHelper
    {
        public ActionContext ActionContext => context;
        public string? Action(UrlActionContext context) => "/fixture";
        public string? Content(string? contentPath) => contentPath?.Replace("~/", "/");
        public bool IsLocalUrl(string? url) => url?.StartsWith('/') == true;
        public string? Link(string? routeName, object? values) => "/fixture";
        public string? RouteUrl(UrlRouteContext context) => "/fixture";
    }
}
