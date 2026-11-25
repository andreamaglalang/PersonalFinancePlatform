internal static class MemberBudgetChecks
{
    public static void Run()
    {
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "BudgetsController.cs"));
        var index = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Budgets", "Index.cshtml"));
        var create = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Budgets", "Create.cshtml"));
        var edit = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Budgets", "Edit.cshtml"));
        var delete = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Budgets", "Delete.cshtml"));
        var layout = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml"));

        // Member authorization only: never the administrator policy, and the caller's own session token.
        if (controller.Contains("AuthorizationConfiguration.UserPolicy") is false)
            throw new InvalidOperationException("Member budgets must require the User policy.");
        if (controller.Contains("AdministratorPolicy") || controller.Contains("IsInRole(\"Administrator\")"))
            throw new InvalidOperationException("Member budgets must not use an administrator check.");
        if (controller.Contains("MemberSessionGate.AcquireAsync") is false
            || controller.Contains("MemberSessionGate.RejectAsync") is false)
            throw new InvalidOperationException("Member budgets must resolve and reject through MemberSessionGate.");

        // Reuse the upstream budget contracts rather than inventing parallel ones.
        foreach (var marker in new[] { "BudgetApiClient", "CreateBudgetRequest(", "UpdateBudgetRequest(",
            "budgets.ListAsync", "budgets.GetAsync", "budgets.CreateAsync", "budgets.UpdateAsync", "budgets.DeleteAsync" })
            if (controller.Contains(marker) is false)
                throw new InvalidOperationException($"Member budgets must use the existing budget API contract: {marker}.");

        // Every write is antiforgery-protected; failures redirect so a retried POST cannot double-apply.
        var writes = controller.Split("[HttpPost").Length - 1;
        var tokens = controller.Split("ValidateAntiForgeryToken").Length - 1;
        if (writes != tokens || writes < 3)
            throw new InvalidOperationException("Every member budget POST must carry ValidateAntiForgeryToken.");

        // Forms use the tag helper so the token is actually emitted.
        foreach (var view in new[] { create, edit, delete })
            if (view.Contains("method=\"post\" asp-action=") is false)
                throw new InvalidOperationException("Budget forms must use the form tag helper so antiforgery is emitted.");
        if (create.Contains("asp-validation-summary=\"ModelOnly\"") is false)
            throw new InvalidOperationException("Create must render model-level validation messages.");
        if (delete.Contains("cannot be undone") is false)
            throw new InvalidOperationException("Delete must be a confirmation page with an explicit warning.");

        // Category options are paged to completion, and the list is never assumed to fit one page.
        if (controller.Contains("LoadExpenseCategoriesAsync") is false
            || controller.Contains("data.TotalCount") is false)
            throw new InvalidOperationException("Budget categories must be paged until the API reports completion.");
        if (index.Contains("Url.Action(\"Edit\"") is false || index.Contains("Url.Action(\"Delete\"") is false)
            throw new InvalidOperationException("Budget rows must link to edit and delete.");
        if (index.Contains("member-budget-grid") is false || index.Contains("Math.Clamp(percentage, 0m, 100m)") is false
            || index.Contains("Math.Abs(budget.RemainingAmount)") is false)
            throw new InvalidOperationException("Budget cards must display real totals and clamp progress visually for exceeded limits.");

        // Plan navigation must reach the member budgets page instead of a "Soon" placeholder.
        if (layout.Contains("asp-controller=\"Budgets\"") is false || layout.Contains("data-soon") is true
            && layout.Contains("data-nav=\"plan\" data-soon") is true)
            throw new InvalidOperationException("Member Plan navigation must point at the budgets page.");

        Console.WriteLine("PASS: member budgets are member-scoped, antiforgery-protected and reuse the budget API contracts.");
    }
}
