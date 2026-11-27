using System.ComponentModel.DataAnnotations;
using System.Net;
using System.Reflection;
using System.Text;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;
using ApiPolicy = PersonalFinance.Infrastructure.Authentication.PasswordPolicy;
using SitePolicy = PersonalFinance.AdminMvc.ViewModels.PasswordPolicy;

/// <summary>System-wide validation feedback: messages name the actual failed rule; server text is never echoed raw.</summary>
internal static class ValidationFeedbackChecks
{
    static void Check(bool condition, string description) { if (!condition) throw new InvalidOperationException(description); }

    static List<string> Validate(object model)
    {
        var results = new List<ValidationResult>();
        Validator.TryValidateObject(model, new ValidationContext(model), results, true);
        return results.Select(r => r.ErrorMessage!).ToList();
    }

    public static async Task RunAsync()
    {
        // 1. Site and API password rules agree for every sample, message for message.
        foreach (var sample in new[] { "", "abc", "abcdefghijk1", "Synthetic!Pass123", "ABCDEFGHIJK1!", "Aa1!Aa1!Aa1!", "Éabcdefghij1",
                     "Aaaaaaaaaaaa", "Aa1!" + new string('x', 125), "pässwörd-Long1" })
            Check(SitePolicy.Describe(sample) == ApiPolicy.Describe(sample), $"site and API password messages differ for '{sample}'.");

        // 2. Reported defect: 12 matching characters without uppercase or symbol get the exact unmet rules.
        var register = new RegisterViewModel { FullName = "Member", Email = "member@example.test", Password = "abcdefghijk1", ConfirmPassword = "abcdefghijk1" };
        var errors = Validate(register);
        Check(errors.SequenceEqual(["Password must contain an uppercase letter and a special character, such as ! or #."]),
            "registration must name the missing uppercase letter and special character: " + string.Join(" | ", errors));
        Check(!errors.Any(e => e.Contains("at least 12 characters and match")), "the misleading length/match message must be gone.");

        // 3. Every failing field is reported at once, in plain words.
        errors = Validate(new RegisterViewModel { FullName = "", Email = "x", Password = "abc", ConfirmPassword = "abd" });
        foreach (var expected in new[] { "Enter your full name.", "Enter a valid email address.", "Passwords do not match." })
            Check(errors.Contains(expected), $"missing '{expected}' in: {string.Join(" | ", errors)}");
        Check(errors.Any(e => e.StartsWith("Password must contain at least 12 characters", StringComparison.Ordinal)), "password rules missing.");

        errors = Validate(new MemberPasswordInput { CurrentPassword = "", NewPassword = "abcdefghijk1", ConfirmPassword = "x" });
        Check(errors.Contains("Enter your current password.") && errors.Contains("Passwords do not match.")
            && errors.Contains("Password must contain an uppercase letter and a special character, such as ! or #."),
            "change password must report each field: " + string.Join(" | ", errors));

        // 4. Amounts: one specific message per rule, on every form.
        Check(MoneyInput.Problem("") == "Enter an amount." && MoneyInput.Problem("abc")!.StartsWith("Enter the amount as a number")
            && MoneyInput.Problem("0") == "Enter an amount greater than zero." && MoneyInput.Problem("-3") == "Enter an amount greater than zero."
            && MoneyInput.Problem("10.555") == "Amounts can have no more than two decimal places."
            && MoneyInput.Problem("99999999999999999") == "That amount is too large." && MoneyInput.Problem("1250.50") is null,
            "MoneyInput must name each amount rule.");
        errors = Validate(new MemberTransactionDraftViewModel { Type = "Expense", CategoryId = null, Amount = 10.555m, CurrencyCode = "PHP", TransactionDate = null });
        Check(errors.Contains("Amounts can have no more than two decimal places.") && errors.Contains("Choose a category.") && errors.Contains("Choose a date."),
            "transaction form must name each problem: " + string.Join(" | ", errors));
        Check(!errors.Any(e => e.StartsWith("The field ", StringComparison.Ordinal) || e.Contains("CategoryId")), "no framework default messages.");
        errors = Validate(new MemberTransactionDraftViewModel { Type = "Expense", CategoryId = 1, Amount = 200_000_000m, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 1, 1) });
        Check(errors.SequenceEqual(["Enter an amount up to 100,000,000."]), "transaction cap message: " + string.Join(" | ", errors));
        Check(Validate(new MemberSavingsGoalFormModel { Name = "Trip", TargetAmount = "10.555" }).SequenceEqual(["Amounts can have no more than two decimal places."]), "goal amount message.");
        // Found in the browser run: an amount problem must be reported together with other field problems.
        errors = Validate(new BudgetCreateViewModel { LimitAmount = "10.555", AlertThresholdPct = null, CategoryId = null, Month = null });
        foreach (var expected in new[] { "Amounts can have no more than two decimal places.", "Enter an alert threshold from 1 to 100.", "Choose an expense category.", "Choose a month." })
            Check(errors.Contains(expected), $"budget form missing '{expected}': {string.Join(" | ", errors)}");
        errors = Validate(new MemberBillFormModel { Name = "", CategoryId = null, Amount = "0", CurrencyCode = "php", ReminderDaysBefore = 40, Frequency = "Monthly", FirstDueDate = null });
        foreach (var expected in new[] { "Enter a bill name.", "Choose an expense category.", "Enter an amount greater than zero.", "Currency is three letters, for example PHP.", "Reminder days must be a whole number from 0 to 30.", "Choose the first due date." })
            Check(errors.Contains(expected), $"bill form missing '{expected}': {string.Join(" | ", errors)}");

        // 5. Only reviewed API messages are shown; anything else falls back to the generic status message.
        Check(ApiMessages.Translate("A budget already exists for this category and month.") == "A budget already exists for this category and month.", "known message.");
        Check(ApiMessages.Translate("SqlException: Violation of UNIQUE KEY constraint 'IX_Budgets'") is null, "unknown text must not be shown.");
        Check(ApiMessages.Translate("Password must contain <b>an uppercase letter</b>.") is null, "rebuilt password sentence only.");
        Check(ApiMessages.Translate("Password must contain an uppercase letter and a special character, such as ! or #.") is not null, "password sentence.");
        Check(MemberSessionGate.Message(409, true, null).Contains("conflicts"), "generic fallback kept.");
        Check(MemberSessionGate.Message(503, true, "Enter an amount greater than zero.").Contains("could not confirm"),
            "an uncertain outcome must never show a validation reason.");
        // Every allowlisted API message must still exist in the API source, so the list cannot silently drift.
        var known = (Dictionary<string, string>)typeof(ApiMessages).GetField("Known", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
        var infrastructure = Path.Combine(Path.GetDirectoryName(MvcProject.FindDirectory())!, "PersonalFinance.Infrastructure");
        var source = string.Concat(Directory.EnumerateFiles(infrastructure, "*.cs", SearchOption.AllDirectories)
            .Where(p => !p.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") && !p.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
            .Select(File.ReadAllText));
        foreach (var message in known.Keys)
            Check(source.Contains('"' + message + '"'), $"allowlisted API message no longer exists in the API: {message}");

        // 6. The registration client keeps the API's reviewed field messages.
        var body = """{"title":"One or more validation errors occurred.","status":400,"errors":{"Password":["Password must contain an uppercase letter and a special character, such as ! or #."],"Email":["Enter a valid email address."],"$.x":["The JSON value could not be converted."]}}""";
        var client = new AuthenticationApiClient(new HttpClient(new Problem(HttpStatusCode.BadRequest, body)) { BaseAddress = new Uri("https://api.example.test/") });
        var result = await client.RegisterAsync(new RegisterRequest { FullName = "M", Email = "m@example.test", Password = "abcdefghijk1", ConfirmPassword = "abcdefghijk1" }, default);
        Check(result.Failure == ApiFailure.BadRequest && result.Fields["Password"].Single().StartsWith("Password must contain an uppercase letter")
            && result.Fields["Email"].Single() == "Enter a valid email address." && !result.Fields.ContainsKey("$.x"),
            "register must surface reviewed field messages only.");

        Console.WriteLine("PASS: validation feedback names the actual rule (passwords, fields, amounts) and shows only reviewed API messages.");
    }

    private sealed class Problem(HttpStatusCode status, string json) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(json, Encoding.UTF8, "application/problem+json") });
    }
}
