internal static class MemberInsightsChecks
{
    public static void Run()
    {
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ReportsController.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Reports", "Index.cshtml"));
        if (!controller.Contains("AuthorizationConfiguration.UserPolicy") || !controller.Contains("MemberSessionGate.AcquireAsync") || !controller.Contains("reports.GetMonthlyAsync"))
            throw new InvalidOperationException("Insights must use the owner session and authoritative monthly reporting API.");
        foreach (var section in new[] { "Financial reports", "Net transaction flow", "Spending breakdown", "Income vs. expenses", "Monthly comparison", "data-report-refresh" })
            if (!view.Contains(section)) throw new InvalidOperationException($"Insights must include {section}.");
        if (view.Contains("report.Budgets") || view.Contains("asp-action=\"Quote\"") || view.Contains("style="))
            throw new InvalidOperationException("Member reports must remove duplicate Budgets/FX and use CSP-compatible charts.");
        Console.WriteLine("PASS: Member Insights structure and authoritative report integration.");
    }
}
