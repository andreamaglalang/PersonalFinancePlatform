using System.Reflection;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>Defects found by the end-to-end browser run (docs/audits/OWLI_E2E_Acceptance.md).</summary>
internal static class E2eRegressionChecks
{
    private static void TransactionResultCheck(int status, PersonalFinance.AdminMvc.Services.TransactionResponse row, Guid clientId, bool saved, string message)
    {
        using var http = new HttpClient(new FixedResponse(status, System.Text.Json.JsonSerializer.Serialize(row, new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web)))) { BaseAddress = new Uri("https://isolated-api.test/") };
        var result = new PersonalFinance.AdminMvc.Services.TransactionApiClient(http).CreateAsync(new(clientId, "Expense", 2, 44.20m, "PHP", new DateOnly(2026, 11, 27), null), "fixture", default).GetAwaiter().GetResult();
        if (result.Succeeded != saved) throw new InvalidOperationException(message);
    }

    private sealed class FixedResponse(int status, string json) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(new HttpResponseMessage((System.Net.HttpStatusCode)status) { Content = new StringContent(json, System.Text.Encoding.UTF8, "application/json") });
    }

    public static void Run()
    {
        // E1: the member route is {area}/{controller}/{action}/{id?}, so a contribution id cannot come from the route.
        // The Details page links put it in the query string; binding it [FromRoute] made every edit/delete link a 404.
        foreach (var action in typeof(SavingsGoalsController).GetMethods().Where(m => m.Name.Contains("Contribution", StringComparison.Ordinal)))
            foreach (var parameter in action.GetParameters().Where(p => p.Name == "contributionId"))
                if (parameter.GetCustomAttribute<FromRouteAttribute>() is not null || parameter.GetCustomAttribute<FromQueryAttribute>() is null)
                    throw new InvalidOperationException($"{action.Name}: contributionId must bind from the query string the links generate.");
        var details = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Details.cshtml"));
        if (!details.Contains("asp-route-contributionId", StringComparison.Ordinal))
            throw new InvalidOperationException("Contribution links must pass contributionId.");
        // E3: bill edits never post the creation-only Frequency/FirstDueDate, so their [Required] rules must not block
        // the save (it previously failed silently: those fields' messages are not rendered when editing).
        var bills = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "BillsController.cs"));
        if (!bills.Contains("ModelState.Remove(nameof(model.Frequency))") || !bills.Contains("ModelState.Remove(nameof(model.FirstDueDate))"))
            throw new InvalidOperationException("Bill edit must ignore the creation-only schedule fields.");
        var model = new PersonalFinance.AdminMvc.ViewModels.MemberBillFormModel { Name = "Fibre", CategoryId = 1, Amount = "99.50", CurrencyCode = "PHP", ReminderDaysBefore = 3 };
        var problems = new List<System.ComponentModel.DataAnnotations.ValidationResult>();
        System.ComponentModel.DataAnnotations.Validator.TryValidateObject(model, new(model), problems, true);
        if (problems.SelectMany(p => p.MemberNames).Except(["Frequency", "FirstDueDate"]).Any())
            throw new InvalidOperationException("A valid bill edit must only miss the creation-only fields.");
        // E4: budget status text used undefined --income/--expense tokens, so the light-theme fallback colour applied in
        // dark mode (3.19:1 contrast). Status text must use the themed --owli-* tokens.
        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));
        var site = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "site.css"));
        foreach (var line in css.Split('\n').Where(l => l.Contains(".plan-budget-status", StringComparison.Ordinal)))
            foreach (System.Text.RegularExpressions.Match token in System.Text.RegularExpressions.Regex.Matches(line, @"var\((--[\w-]+)"))
                if (!css.Contains(token.Groups[1].Value + ":", StringComparison.Ordinal) && !site.Contains(token.Groups[1].Value + ":", StringComparison.Ordinal))
                    throw new InvalidOperationException($"Budget status text uses undefined colour token {token.Groups[1].Value}.");
        // E7: with no category of the chosen type the form must say so and link to adding one.
        var create = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Create.cshtml"));
        if (!create.Contains("data-no-categories", StringComparison.Ordinal) || !create.Contains("asp-controller=\"Categories\"", StringComparison.Ordinal))
            throw new InvalidOperationException("Add transaction must explain when no category of the chosen type exists.");
        // F1-F3 (financial integrity): identities and versions come from the form the user opened, never re-read at submit.
        var goalsController = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "SavingsGoalsController.cs"));
        var billsController = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "BillsController.cs"));
        string Body(string source, string signature) { var start = source.IndexOf(signature, StringComparison.Ordinal); var next = source.IndexOf("[Http", start + 1, StringComparison.Ordinal); return source[start..(next < 0 ? source.Length : next)]; }
        var contributePost = Body(goalsController, "public async Task<IActionResult> Contribute([FromRoute] int id, [FromForm]");
        if (contributePost.IndexOf("return Expired(id)", StringComparison.Ordinal) is var expiredAt && (expiredAt < 0 || expiredAt > contributePost.IndexOf("ModelState.IsValid", StringComparison.Ordinal)))
            throw new InvalidOperationException("A contribution form with a missing or malformed ClientId must be treated as expired before other validation.");
        if (contributePost.Contains("Guid.NewGuid()") || !contributePost.Contains("model.ClientId"))
            throw new InvalidOperationException("F1: a contribution must use the ClientId minted when its form opened.");
        if (goalsController.Contains("current.Value.Version") || goalsController.Contains("current.Value!.Version"))
            throw new InvalidOperationException("F3: goal/contribution writes must send the version the form was opened with.");
        var payPost = Body(billsController, "public async Task<IActionResult> PayConfirmed(");
        if (payPost.Contains("Guid.NewGuid()") || payPost.Contains("NextDueDate") || !payPost.Contains("new(paymentId, confirmedDue)"))
            throw new InvalidOperationException("F2: a payment must stay bound to the confirmed occurrence and its form identity.");
        foreach (var (view, marker) in new[] { ("Contribute", "asp-for=\"ClientId\""), ("EditContribution", "asp-for=\"Version\""), ("DeleteContribution", "name=\"version\""),
                     ("Cancel", "name=\"version\""), ("Delete", "name=\"version\""), ("Create", "asp-for=\"Version\"") })
            if (!File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", view + ".cshtml")).Contains(marker, StringComparison.Ordinal))
                throw new InvalidOperationException($"SavingsGoals/{view} must post {marker}.");
        var payView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Bills", "Pay.cshtml"));
        if (!payView.Contains("name=\"clientId\"") || !payView.Contains("name=\"dueDate\""))
            throw new InvalidOperationException("Bills/Pay must post the occurrence and its identity.");
        // E2: every page links an icon, so browsers don't request a missing /favicon.ico on each page.
        foreach (var layout in new[] { MvcProject.FindFile("Views", "Shared", "_Layout.cshtml"), MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml") })
            if (!File.ReadAllText(layout).Contains("rel=\"icon\"", StringComparison.Ordinal))
                throw new InvalidOperationException($"{Path.GetFileName(layout)} must link the site icon.");
        if (!File.Exists(Path.Combine(MvcProject.FindDirectory("wwwroot"), "favicon.svg")))
            throw new InvalidOperationException("wwwroot/favicon.svg is missing.");
        // R3-1: an over-funded goal shows its surplus, never a negative remaining amount.
        var goalDetails = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Details.cshtml"));
        if (!goalDetails.Contains("over target", StringComparison.Ordinal) || !goalDetails.Contains("Math.Max(0m, goal.RemainingAmount)", StringComparison.Ordinal))
            throw new InvalidOperationException("R3-1: goal details must show the surplus instead of a negative remaining amount.");
        // R3-2: a rate confirmation issued moments ago must be accepted against the fresh quote rebuilt on submit.
        var protection = new Microsoft.AspNetCore.DataProtection.EphemeralDataProtectionProvider();
        PersonalFinance.AdminMvc.Services.MemberFxReview.Quote Quote(decimal rate, TimeSpan validFor) => new(10.25m, "USD", new DateOnly(2026, 11, 27), rate,
            new DateOnly(2026, 11, 27), decimal.Round(10.25m * rate, 2), "PHP", "Frankfurter", DateTimeOffset.UtcNow.Add(validFor));
        bool Accepts(string proof, string user, decimal rate) => PersonalFinance.AdminMvc.Services.MemberFxReview.Matches(protection, proof, user, 10.25m, "USD", new DateOnly(2026, 11, 27), Quote(rate, TimeSpan.FromMinutes(10)));
        var issued = PersonalFinance.AdminMvc.Services.MemberFxReview.Issue(protection, "7", Quote(58.123m, TimeSpan.FromMinutes(10)));
        var expired = PersonalFinance.AdminMvc.Services.MemberFxReview.Issue(protection, "7", Quote(58.123m, TimeSpan.FromSeconds(-1)));
        if (!Accepts(issued, "7", 58.123m)) throw new InvalidOperationException("R3-2: a fresh rate confirmation must be accepted.");
        if (Accepts(expired, "7", 58.123m) || Accepts(issued, "8", 58.123m) || Accepts(issued, "7", 58.5m))
            throw new InvalidOperationException("R3-2: expired, other-account or changed-rate confirmations must be refused.");
        // R3-3: a new transaction uses the ClientId minted when its form opened.
        var activityController = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var createPost = Body(activityController, "public async Task<IActionResult> Create([FromForm]");
        if (createPost.Contains("CreateTransactionRequest(Guid.NewGuid()", StringComparison.Ordinal) || !createPost.Contains("new CreateTransactionRequest(clientId", StringComparison.Ordinal)
            || !create.Contains("asp-for=\"ClientId\"", StringComparison.Ordinal))
            throw new InvalidOperationException("R3-3: a transaction must use the ClientId minted when its form opened.");
        // R3-3: the API answers a replayed ClientId with 200 and the original row; that is a confirmed save, not an unknown outcome.
        var replayId = Guid.NewGuid();
        var replayRow = new PersonalFinance.AdminMvc.Services.TransactionResponse(9, replayId, "Expense", 2, "Food", 44.20m, "PHP", 1m, new DateOnly(2026, 11, 27), 44.20m, new DateOnly(2026, 11, 27), null, DateTime.UtcNow, null, null);
        TransactionResultCheck(200, replayRow, replayId, true, "R3-3: a replayed create (200, same ClientId) must count as saved.");
        TransactionResultCheck(200, replayRow with { ClientId = Guid.NewGuid() }, replayId, false, "R3-3: a 200 carrying another ClientId must not count as saved.");
        // R3-4: form validation never shows framework wording or regular expressions.
        static string[] Problems(object model) { var found = new List<System.ComponentModel.DataAnnotations.ValidationResult>();
            System.ComponentModel.DataAnnotations.Validator.TryValidateObject(model, new(model), found, true); return found.Select(r => r.ErrorMessage ?? "").ToArray(); }
        var messages = Problems(new PersonalFinance.AdminMvc.ViewModels.CategoryCreateViewModel { Name = "   ", Type = "Other", ColorHEX = "zz" })
            .Concat(Problems(new PersonalFinance.AdminMvc.ViewModels.CategoryEditViewModel { Name = new string('x', 51), ColorHEX = "zz", OriginalState = "s" }))
            .Concat(Problems(new PersonalFinance.AdminMvc.ViewModels.MemberCategoryFormViewModel { Name = "", Type = "Other", ColorHEX = "zz" }))
            .Concat(Problems(new PersonalFinance.AdminMvc.ViewModels.MemberTransactionDraftViewModel { Type = "Other", CategoryId = 1, Amount = 1, TransactionDate = new DateOnly(2026, 1, 1) }))
            .Concat(Problems(new PersonalFinance.AdminMvc.ViewModels.MemberCurrencyPreferencesViewModel { DefaultCurrency = "usd" })).ToArray();
        if (messages.Length < 9 || messages.Any(m => m.Contains("The field", StringComparison.Ordinal) || m.Contains("regular expression", StringComparison.Ordinal) || m.Contains("field is required", StringComparison.Ordinal)))
            throw new InvalidOperationException("R3-4: validation messages must be plain language: " + string.Join(" | ", messages));
        // Registration refinement: hint-first checklist with drawn marks, Show/Hide on both password fields, no redundant footer.
        var register = File.ReadAllText(MvcProject.FindFile("Views", "Account", "Register.cshtml"));
        var rules = File.ReadAllText(MvcProject.FindFile("Views", "Shared", "_PasswordRequirements.cshtml"));
        if (System.Text.RegularExpressions.Regex.Matches(register, "data-password-toggle").Count != 2 || register.Contains("Registration creates a member account", StringComparison.Ordinal)
            || !register.Contains("[\"PasswordRulesCollapsible\"] = true", StringComparison.Ordinal))
            throw new InvalidOperationException("Registration must offer Show/Hide on both password fields, a collapsible checklist and no redundant footer.");
        if (!rules.Contains("password-rules-hint", StringComparison.Ordinal) || !rules.Contains("<svg class=\"password-rule-mark\"", StringComparison.Ordinal) || rules.Contains('○') || rules.Contains('✓'))
            throw new InvalidOperationException("The password checklist must show a hint and drawn marks, not text glyphs.");
        Console.WriteLine("PASS: end-to-end regressions (contribution/payment identities and versions from the form; contribution links bind; bill edit saves; budget status uses themed colours; site icon present; R3: goal surplus, rate confirmation, transaction identity and replay, plain validation wording).");
    }
}
