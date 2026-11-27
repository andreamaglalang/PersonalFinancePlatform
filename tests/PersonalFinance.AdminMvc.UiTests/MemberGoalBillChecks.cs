internal static class MemberGoalBillChecks
{
    public static void Run()
    {
        var goals = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "SavingsGoalsController.cs"));
        var bills = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "BillsController.cs"));
        var goalIndex = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Index.cshtml"));
        var goalForm = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Create.cshtml"));
        var contribute = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Contribute.cshtml"));
        var cancel = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "SavingsGoals", "Cancel.cshtml"));
        var billIndex = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Bills", "Index.cshtml"));
        var billForm = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Bills", "Create.cshtml"));
        var pay = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Bills", "Pay.cshtml"));
        var goalClient = File.ReadAllText(MvcProject.FindFile("Services", "SavingsGoalApiClient.cs"));
        var billClient = File.ReadAllText(MvcProject.FindFile("Services", "RecurringBillApiClient.cs"));
        var layout = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml"));

        foreach (var (name, controller) in new[] { ("savings goals", goals), ("bills", bills) })
        {
            if (controller.Contains("AuthorizationConfiguration.UserPolicy") is false)
                throw new InvalidOperationException($"Member {name} must require the User policy.");
            if (controller.Contains("AdministratorPolicy") || controller.Contains("IsInRole(\"Administrator\")"))
                throw new InvalidOperationException($"Member {name} must not use an administrator check.");
            if (controller.Contains("MemberSessionGate.AcquireAsync") is false
                || controller.Contains("MemberSessionGate.RejectAsync") is false)
                throw new InvalidOperationException($"Member {name} must resolve and reject through MemberSessionGate.");
        }

        // Every write is antiforgery-protected; failures redirect rather than re-rendering a mutation.
        foreach (var (name, controller) in new[] { ("savings goals", goals), ("bills", bills) })
        {
            var posts = controller.Split("[HttpPost").Length - 1;
            var tokens = controller.Split("ValidateAntiForgeryToken").Length - 1;
            if (posts != tokens || posts < 2)
                throw new InvalidOperationException($"Every member {name} POST must carry ValidateAntiForgeryToken.");
        }

        // Contributions and payments mint a fresh ClientId; cancellation uses the goal's row version as If-Match.
        if (goals.Contains("Guid.NewGuid()") is false || goals.Contains("current.Value!.Version") is false)
            throw new InvalidOperationException("Goals must mint a fresh ClientId and cancel with If-Match.");
        if (bills.Contains("Guid.NewGuid()") is false)
            throw new InvalidOperationException("Bills must mint a fresh ClientId for each payment.");
        if (goalClient.Contains("If-Match") is false)
            throw new InvalidOperationException("The goal client must send the row version as If-Match.");
        if (goalClient.Contains("api/savings-goals") is false || billClient.Contains("api/recurring-bills") is false)
            throw new InvalidOperationException("The clients must use the existing upstream routes only.");

        // The schedule is fixed at creation: an edit must never submit frequency or first due date.
        var billFormText = billForm;
        if (!billFormText.Contains("if (!editing)"))
            throw new InvalidOperationException("The bill form must hide the fixed schedule fields when editing.");
        if (billForm.Contains("name=\"CategoryId\" type=\"number\"")
            || billForm.Contains("<select id=\"CategoryId\"") is false
            || bills.Contains("LoadExpenseCategoriesAsync") is false)
            throw new InvalidOperationException("Bill forms must select owner-visible expense categories instead of asking for an internal ID.");

        foreach (var view in new[] { goalForm, contribute, cancel, billForm, pay })
            if (view.Contains("method=\"post\" asp-action=") is false)
                throw new InvalidOperationException("Member forms must use the form tag helper so antiforgery is emitted.");
        if (cancel.Contains("cannot be undone") is false || pay.Contains("records one expense transaction") is false)
            throw new InvalidOperationException("Cancel and Pay must state the consequence before confirming.");
        if (goalIndex.Contains("Url.Action(\"Contribute\"") is false || goalIndex.Contains("Url.Action(\"Cancel\"") is false)
            throw new InvalidOperationException("Goal rows must link to contribute, edit and cancel.");
        if (goalIndex.Contains("@if (goal.Status == \"Active\")") is false)
            throw new InvalidOperationException("Contribution and edit actions must not be offered for completed or cancelled goals.");
        if (goalIndex.Contains("member-goal-grid") is false || goalIndex.Contains("goal.ProgressPercentage") is false
            || billIndex.Contains("member-bill-grid") is false || billIndex.Contains("bill.NextDueDate") is false)
            throw new InvalidOperationException("Goals and bills must use responsive cards with real progress and schedule data.");
        if (billIndex.Contains("Url.Action(\"Pay\"") is false || billIndex.Contains("Url.Action(\"Edit\"") is false)
            throw new InvalidOperationException("Bill rows must link to pay, edit and delete.");

        var planNavigation = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_PlanNavigation.cshtml"));
        if (layout.Contains("data-nav=\"plan\"") is false
            || planNavigation.Contains("asp-controller=\"SavingsGoals\"") is false
            || planNavigation.Contains("asp-controller=\"Bills\"") is false)
            throw new InvalidOperationException("Member Plan navigation must reach budgets, goals and bills.");

        // The API refuses future occurrences (409); MVC must not offer an early payment or show a misleading conflict.
        if (!billIndex.Contains("bill.NextDueDate <= PersonalFinance.AdminMvc.Services.BudgetPlanSummary.PhilippineToday")
            || !billIndex.Contains("Payment opens on") || !bills.Contains("can be paid from its due date"))
            throw new InvalidOperationException("Record payment must be offered only for a due occurrence.");
        Console.WriteLine("PASS: member savings goals and recurring bills are member-scoped, antiforgery-protected and idempotent.");
    }
}
