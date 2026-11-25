internal static class MemberDashboardDataChecks
{
    public static void Run()
    {
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "HomeController.cs"));
        var model = File.ReadAllText(MvcProject.FindFile("ViewModels", "MemberDashboardViewModel.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Home", "Index.cshtml"));

        // Month uses reports; All time is aggregated from the complete owner-scoped transaction API history.
        if (controller.Contains("ReportsApiClient") is false || controller.Contains("GetMonthlyAsync") is false)
            throw new InvalidOperationException("HomeController must read the monthly report.");
        if (controller.Contains("ListAllAsync") is false || model.Contains("ApplyAllTime") is false)
            throw new InvalidOperationException("All time must aggregate a complete transaction history.");
        if (controller.Contains("MemberSessionGate.AcquireAsync") is false
            || controller.Contains("AuthorizationConfiguration.UserPolicy") is false)
            throw new InvalidOperationException("HomeController must use the member gate and User policy.");
        if (controller.Contains("AdministratorPolicy"))
            throw new InvalidOperationException("HomeController must not use the Administrator policy.");

        foreach (var marker in new[] { "MonthDate", "BaseCurrency", "TotalIncome", "TotalExpenses",
            "NetCashFlow", "TransactionCount", "IncomeRing", "ExpenseRing" })
            if (model.Contains(marker) is false)
                throw new InvalidOperationException($"MemberDashboardViewModel is missing {marker}.");

        foreach (var marker in new[] { "Model.MonthLabel", "Model.IsAllTime", "This is not an account balance.",
            "member-ring", "member-rank", "Where your money went", "All time", "Month" })
            if (view.Contains(marker) is false)
                throw new InvalidOperationException($"Home/Index.cshtml is missing {marker}.");
        foreach (var forbidden in new[] { "4,309.25", "1,849.00", "3,500.75" })
            if (view.Contains(forbidden))
                throw new InvalidOperationException($"Home/Index.cshtml must not contain the fictional value {forbidden}.");

        Console.WriteLine("PASS: the member dashboard provides month and complete-history reporting without fabricated figures.");
    }
}
