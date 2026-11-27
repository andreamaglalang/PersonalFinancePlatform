using System.Globalization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using Microsoft.AspNetCore.Mvc.ModelBinding.Validation;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Primitives;
using PersonalFinance.AdminMvc.ViewModels;

/// <summary>Input-validation audit regressions for the MVC site (real MVC model binding and validation services).</summary>
internal static class InputValidationChecks
{
    static void Check(bool condition, string description) { if (!condition) throw new InvalidOperationException(description); }

    public static async Task RunAsync()
    {
        var services = new ServiceCollection().AddLogging().AddMvcCore().AddDataAnnotations().Services.BuildServiceProvider();
        var metadata = services.GetRequiredService<IModelMetadataProvider>();

        // 1. A typed transaction amount is read exactly: never "10,50" -> 1050, "1e5" -> 100000 or "1,000.00" -> 1000.
        async Task<(bool Bound, decimal? Value, string? Error)> Bind(string text)
        {
            var state = new ModelStateDictionary();
            var context = new DefaultModelBindingContext
            {
                ModelMetadata = metadata.GetMetadataForType(typeof(decimal?)), ModelName = "Amount", ModelState = state,
                ValueProvider = new FormValueProvider(BindingSource.Form, new FormCollection(new() { ["Amount"] = new StringValues(text) }), CultureInfo.InvariantCulture),
            };
            await new MoneyModelBinder().BindModelAsync(context);
            return (context.Result.IsModelSet, context.Result.Model as decimal?, state["Amount"]?.Errors.SingleOrDefault()?.ErrorMessage);
        }
        foreach (var text in new[] { "10,50", "1e5", "1,000.00", "+5", "₱100", "abc", "10..50", "NaN", "Infinity", "0x10" })
        {
            var (bound, _, error) = await Bind(text);
            Check(!bound && error == MoneyInput.NotANumber, $"'{text}' must be refused with the decimal-point message, not reinterpreted.");
        }
        foreach (var (text, value) in new[] { ("0.50", 0.50m), ("100.50", 100.50m), (" 5 ", 5m), ("000100", 100m), ("-1", -1m), ("10.555", 10.555m) })
        {
            var (bound, bound_value, error) = await Bind(text);
            Check(bound && bound_value == value && error is null, $"'{text}' must bind exactly as {value} (range rules then name the problem).");
        }
        var empty = await Bind("");
        Check(empty.Bound && empty.Value is null, "an empty amount binds as null so [Required] says 'Enter an amount.'");
        var amountAttributes = typeof(MemberTransactionDraftViewModel).GetProperty(nameof(MemberTransactionDraftViewModel.Amount))!.GetCustomAttributes(true);
        Check(amountAttributes.OfType<ModelBinderAttribute>().Any(a => a.BinderType == typeof(MoneyModelBinder)), "the transaction form must bind Amount with MoneyModelBinder.");
        Check(MoneyInput.Problem("+5") == MoneyInput.NotANumber && MoneyInput.Problem("1e5") == MoneyInput.NotANumber
            && MoneyInput.Problem("10,50") == MoneyInput.NotANumber && MoneyInput.Problem(" 0.50 ") is null, "text amount fields follow the same rules.");

        // 2. Unparseable text in a budget limit, goal target, contribution or bill amount is a field message, not an
        //    error page: MVC validation must never evaluate the computed decimal (decimal.Parse would throw).
        var validator = services.GetRequiredService<IObjectModelValidator>();
        string[] Validate(object model)
        {
            var action = new ActionContext(new DefaultHttpContext { RequestServices = services }, new RouteData(), new ActionDescriptor());
            validator.Validate(action, null, string.Empty, model);
            return action.ModelState.Values.SelectMany(v => v.Errors).Select(e => e.ErrorMessage).ToArray();
        }
        foreach (var text in new[] { "abc", "1e5", "10,50" })
        {
            var forms = new object[]
            {
                new BudgetCreateViewModel { CategoryId = 1, Month = "2031-05", LimitAmount = text, AlertThresholdPct = 80 },
                new BudgetEditViewModel { LimitAmount = text, AlertThresholdPct = 80 },
                new MemberSavingsGoalFormModel { Name = "Trip", TargetAmount = text },
                new MemberContributionFormModel { Amount = text, ContributedOn = new DateOnly(2026, 1, 1) },
                new MemberBillFormModel { Name = "Rent", CategoryId = 1, Amount = text, CurrencyCode = "PHP", ReminderDaysBefore = 3, Frequency = "Monthly", FirstDueDate = new DateOnly(2027, 1, 31) },
            };
            foreach (var form in forms)
            {
                string[] errors;
                try { errors = Validate(form); }
                catch (FormatException) { throw new InvalidOperationException($"{form.GetType().Name} threw while validating '{text}' (shown as an error page)."); }
                Check(errors.SequenceEqual([MoneyInput.NotANumber]), $"{form.GetType().Name} '{text}': {string.Join(" | ", errors)}");
            }
        }

        // 3. Names are single-line labels on every form; legitimate Unicode, apostrophes and hyphens stay valid.
        var named = new (Func<string, object> Form, string Message)[]
        {
            (n => new CategoryCreateViewModel { Name = n, Type = "Expense" }, "Category names can't contain line breaks or control characters."),
            (n => new MemberCategoryFormViewModel { Name = n, Type = "Expense" }, "Category names can't contain line breaks or control characters."),
            (n => new MemberSavingsGoalFormModel { Name = n, TargetAmount = "100" }, "Goal names can't contain line breaks or control characters."),
            (n => new MemberBillFormModel { Name = n, CategoryId = 1, Amount = "5", CurrencyCode = "PHP", ReminderDaysBefore = 3, Frequency = "Monthly", FirstDueDate = new DateOnly(2027, 1, 31) }, "Bill names can't contain line breaks or control characters."),
            (n => new RegisterViewModel { FullName = n, Email = "m@example.test", Password = "Synthetic!Pass123", ConfirmPassword = "Synthetic!Pass123" }, "Full name can't contain line breaks or control characters."),
            (n => new MemberProfileInput { FullName = n }, "Full name can't contain line breaks or control characters."),
        };
        foreach (var (form, message) in named)
        {
            foreach (var bad in new[] { "Two\nLines", "Bell\u0007", "Tab\tInside" })
                Check(Validate(form(bad)).SequenceEqual([message]), $"{form(bad).GetType().Name} must refuse {bad.Replace("\n", "\\n")}.");
            Check(Validate(form("  José Ñúñez-O'Hara ")).Length == 0, $"{form("x").GetType().Name} must accept Unicode names.");
        }
        // 4. Converter: an amount with three decimals is named as such (it was rounded to 10.56, sent, and then reported
        //    as a rate-service outage); an empty amount gets plain wording, not the framework's "field is required".
        var converter = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "CurrencyConverterController.cs"));
        Check(converter.Contains("MoneyInput.Problem(model.Amount, 1_000_000_000m)") && !converter.Contains("NumberStyles.AllowDecimalPoint"),
            "the converter must validate with MoneyInput instead of its own lenient parse.");
        Check(MoneyInput.Problem("10.555", 1_000_000_000m) == "Amounts can have no more than two decimal places."
            && MoneyInput.Problem("1000000000.01", 1_000_000_000m) == "Enter an amount up to 1,000,000,000.", "converter amount rules.");
        Check(Validate(new MemberCurrencyConverterViewModel { Amount = "" }).SequenceEqual(["Enter an amount."]), "empty converter amount wording.");

        Console.WriteLine("PASS: input validation (exact amount binding, no error page for unparseable amounts, single-line names).");
    }
}
