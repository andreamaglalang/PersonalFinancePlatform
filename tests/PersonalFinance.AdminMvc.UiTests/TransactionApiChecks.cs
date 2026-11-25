internal static class TransactionApiChecks
{
    public static void Run()
    {
        var contracts = File.ReadAllText(MvcProject.FindFile("Services", "TransactionContracts.cs"));
        var client = File.ReadAllText(MvcProject.FindFile("Services", "TransactionApiClient.cs"));
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Index.cshtml"));

        // Exact current upstream contract: optional AffectedBudgets and Guid? ClientId. The upstream
        // TransactionResponse has no BaseCurrency property, so the client must not invent one.
        foreach (var marker in new[] { "AffectedBudgets", "Guid? ClientId", "TransactionId", "AmountBase" })
            if (contracts.Contains(marker) is false)
                throw new InvalidOperationException($"TransactionContracts.cs is missing {marker}.");
        if (contracts.Contains("string BaseCurrency") || contracts.Contains("BaseCurrency { get"))
            throw new InvalidOperationException("TransactionResponse must match upstream: no per-transaction currency property.");

        if (client.Contains("api/transactions") is false || client.Contains("Bearer") is false)
            throw new InvalidOperationException("TransactionApiClient must call api/transactions with the bearer header.");
        if (client.Contains("AccessToken") is false && client.Contains("bearer") is false)
            throw new InvalidOperationException("TransactionApiClient must send the caller's access token.");
        if (client.Contains("Console.") || client.Contains("ILogger") || client.Contains("RequestContent"))
            throw new InvalidOperationException("TransactionApiClient must not log request bodies or use a logger.");

        // Member-only authorization and gate.
        if (controller.Contains("AuthorizationConfiguration.UserPolicy") is false
            || controller.Contains("AdministratorPolicy"))
            throw new InvalidOperationException("ActivityController must use the User policy and never the Administrator policy.");
        if (controller.Contains("MemberSessionGate.AcquireAsync") is false
            || controller.Contains("MemberSessionGate.RejectAsync") is false)
            throw new InvalidOperationException("ActivityController must resolve and reject through MemberSessionGate.");
        if (controller.Contains("api/categories/defaults"))
            throw new InvalidOperationException("Member controllers must not touch the administrator category defaults.");

        // The view must surface model-level messages and an honest empty state.
        foreach (var marker in new[] { "Model.Error", "Model.Notice", "No matching transactions",
            "member-table", "activity-pagination" })
            if (view.Contains(marker) is false)
                throw new InvalidOperationException($"Activity/Index.cshtml is missing {marker}.");

        Console.WriteLine("PASS: the member Activity list uses the transaction API under the User policy with the exact upstream contract.");
    }
}
