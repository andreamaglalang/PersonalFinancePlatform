internal static class MemberActivityWriteChecks
{
    public static void Run()
    {
        var model = File.ReadAllText(MvcProject.FindFile("ViewModels", "MemberTransactionDraftViewModel.cs"));
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Create.cshtml"));

        // The draft carries the upstream write contract plus the fields the form needs.
        foreach (var marker in new[] { "Type", "CategoryId", "Amount", "CurrencyCode", "TransactionDate",
            "Description", "MoneyAmount(", "RegularExpression(\"^(Income|Expense)$\"" })
            if (model.Contains(marker) is false)
                throw new InvalidOperationException($"MemberTransactionDraftViewModel is missing {marker}.");

        // The create path must mint a fresh ClientId, be antiforgery-protected and never retry.
        if (controller.Contains("Guid.NewGuid()") is false)
            throw new InvalidOperationException("Create must generate a fresh ClientId per submission.");
        if (controller.Contains("ValidateAntiForgeryToken") is false)
            throw new InvalidOperationException("Create POST must validate antiforgery.");
        if (controller.Contains("CreateTransactionRequest(") is false)
            throw new InvalidOperationException("Create must send the upstream CreateTransactionRequest.");
        if (controller.Contains("MemberSessionGate.AcquireAsync") is false)
            throw new InvalidOperationException("Create must resolve the session through MemberSessionGate.");
        if (controller.Contains("Choose a {0} category.") is false && controller.Contains("Choose an {model.Type.ToLowerInvariant()} category.") is false)
            throw new InvalidOperationException("Create must reject a category of the wrong type.");

        // The form must render model-level errors and carry no fictional amounts.
        if (view.Contains("asp-validation-summary=\"ModelOnly\"") is false)
            throw new InvalidOperationException("Create.cshtml must render model-level validation messages.");
        if (view.Contains("method=\"post\"") is false)
            throw new InvalidOperationException("Create.cshtml must post the draft.");
        foreach (var forbidden in new[] { "₱", "4,309.25", "1,849.00", "3,500.75" })
            if (view.Contains(forbidden))
                throw new InvalidOperationException($"Create.cshtml must not contain the fictional value {forbidden}.");

        Console.WriteLine("PASS: the member create form posts the upstream contract with a fresh ClientId and antiforgery.");

        var deleteView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Delete.cshtml"));
        var deleteController = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var indexView = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Index.cshtml"));

        // Delete needs a confirmation step, antiforgery, member authorization and bounded errors.
        if (deleteController.Contains("DeleteConfirmed") is false
            || deleteController.Contains("ValidateAntiForgeryToken") is false
            || deleteController.Contains("transactions.DeleteAsync") is false)
            throw new InvalidOperationException("The delete path must confirm, use antiforgery and call the transaction API.");
        if (!deleteController.Contains("[HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]"))
            throw new InvalidOperationException("The delete POST must be a distinct antiforgery-protected action.");
        // The form must render an antiforgery token via the form tag helper, not a raw action attribute.
        if (view.Contains("method=\"post\"") is false || view.Contains("asp-action=\"@(Model.TransactionId.HasValue ? \"Edit\" : \"Create\")\"") is false)
            throw new InvalidOperationException("The create/edit form must use the form tag helper so antiforgery is emitted.");
        if (deleteView.Contains("method=\"post\" asp-action=\"Delete\"") is false)
            throw new InvalidOperationException("Delete.cshtml must use the form tag helper so antiforgery is emitted.");
        if (deleteView.Contains("method=\"post\"") is false || deleteView.Contains("cannot be undone") is false)
            throw new InvalidOperationException("Delete.cshtml must be a confirmation page with an explicit warning.");
        if (indexView.Contains("Url.Action(\"Delete\"") is false)
            throw new InvalidOperationException("Activity rows must link to the delete confirmation.");

        var editViewModel = File.ReadAllText(MvcProject.FindFile("ViewModels", "MemberTransactionDraftViewModel.cs"));
        if (!controller.Contains("public async Task<IActionResult> Edit([FromRoute] int id")
            || !controller.Contains("transactions.UpdateAsync(id, request")
            || !controller.Contains("transactions.GetAsync(id, token!, ct)")
            || !controller.Contains("MemberFxReview.Matches")
            || !controller.Contains("IsBillPayment(original)"))
            throw new InvalidOperationException("Edit must reload the owner-scoped source row, preserve the FX review and reject bill-linked rows.");
        if (!editViewModel.Contains("BindNever") || !editViewModel.Contains("TransactionId"))
            throw new InvalidOperationException("The edit transaction ID must be server supplied, not form bound.");
        if (!indexView.Contains("Url.Action(\"Edit\"")
            || !indexView.Contains("var isProtected = item.BillId is not null || item.BillDueDate is not null;")
            || !indexView.Contains("if (isProtected)") || !indexView.Contains("activity-protected"))
            throw new InvalidOperationException("Ordinary rows must link to Edit while bill-payment rows remain locked.");

        Console.WriteLine("PASS: the member delete path confirms, is antiforgery-protected and member-scoped.");
    }
}
