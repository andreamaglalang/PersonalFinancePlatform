internal static class MemberDashboardChecks
{
    public static void Run()
    {
        var home = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Home", "Index.cshtml"));
        foreach (var marker in new[] { "owli-screen-title", "member-dashboard-context", "member-panel", "member-period-tabs",
            "This is not an account balance.", "Where your money went", "member-quick",
            "member-dashboard-home", "member-flow-panel", "member-flow-content", "member-spending-chart",
            "Model.Expenses", "member-recent-row" })
        {
            if (home.Contains(marker) is false)
                throw new InvalidOperationException($"Member dashboard is missing {marker}.");
        }

        // Data honesty: no mockup figures or fabricated totals. Currency is formatted from BaseCurrency.
        foreach (var forbidden in new[] { "4,309.25", "1,849.00", "42.9%", "3,500.75" })
        {
            if (home.Contains(forbidden))
                throw new InvalidOperationException($"Member dashboard must not contain the fictional/hardcoded value {forbidden}.");
        }

        foreach (var required in new[] { "Url.Action(\"Create\", \"Activity\"", "Url.Action(\"Index\", \"Activity\"",
            "Model.TotalIncome", "Model.TotalExpenses", "Model.TransactionCount", "Model.Recent",
            "member-sign member-sign-negative", "<span class=\"member-sign\">+</span>", "currencyPrefix",
            "member-quick-expense", "member-quick-income", "member-quick-filter", "member-quick-category",
            "#transaction-filters" })
            if (home.Contains(required) is false)
                throw new InvalidOperationException($"Member dashboard must preserve the existing data/action binding {required}.");

        // The dashboard glyph set must exist for the insight row and quick actions.
        var icons = File.ReadAllText(MvcProject.FindFile("Views", "Shared", "_NavIcon.cshtml"));
        foreach (var glyph in new[] { "case \"eye\"", "case \"filter\"", "case \"minus\"", "case \"list\"" })
        {
            if (icons.Contains(glyph) is false)
                throw new InvalidOperationException($"_NavIcon.cshtml is missing the {glyph} glyph.");
        }

        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));
        if (css.Contains(".member-dashboard-home .member-home-grid { display: grid;") is false
            || css.Contains(".member-dashboard-home .member-sec-head > a") is false)
            throw new InvalidOperationException("Dashboard content must use the intended grid and a visible See all link.");

        if (home.Contains("Add expense") || home.Contains("Add income") || home.Contains(">Activity</b>"))
            throw new InvalidOperationException("Dashboard quick actions must use the approved Expense, Income, Filter, Category labels.");
        if (home.Contains("member-quick-category") is false || home.Contains("\"Categories\"") is false)
            throw new InvalidOperationException("The Category quick action must open personal category management.");
        Console.WriteLine("PASS: dashboard data bindings, reporting controls, quick actions, and visual structure are present.");
    }
}
