using System.Reflection;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>Defects found by the end-to-end browser run (docs/audits/OWLI_E2E_Acceptance.md).</summary>
internal static class E2eRegressionChecks
{
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
        // E2: every page links an icon, so browsers don't request a missing /favicon.ico on each page.
        foreach (var layout in new[] { MvcProject.FindFile("Views", "Shared", "_Layout.cshtml"), MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml") })
            if (!File.ReadAllText(layout).Contains("rel=\"icon\"", StringComparison.Ordinal))
                throw new InvalidOperationException($"{Path.GetFileName(layout)} must link the site icon.");
        if (!File.Exists(Path.Combine(MvcProject.FindDirectory("wwwroot"), "favicon.svg")))
            throw new InvalidOperationException("wwwroot/favicon.svg is missing.");
        Console.WriteLine("PASS: end-to-end regressions (contribution links bind; bill edit saves; budget status uses themed colours; site icon present).");
    }
}
