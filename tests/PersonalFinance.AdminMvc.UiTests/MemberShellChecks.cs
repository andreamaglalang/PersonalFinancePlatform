internal static class MemberShellChecks
{
    public static void Run()
    {
        var layout = File.ReadAllText(MvcProject.FindFile(
            "Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml")).ReplaceLineEndings("\n");
        foreach (var marker in new[]
        {
            "asp-area=\"\"", "asp-controller=\"Account\"", "asp-action=\"Logout\"", "method=\"post\"",
            "data-confirm-logout", "id=\"logout-dialog\""
        })
        {
            if (layout.Contains(marker) is false)
                throw new InvalidOperationException($"The member shell is missing the logout marker {marker}.");
        }
        Console.WriteLine("PASS: member shell exposes the existing antiforgery-protected logout and dialog.");

        // Primary destinations follow the approved OWLI order; Plan keeps budgets, goals, and bills grouped.
        foreach (var marker in new[] { "data-nav=\"home\"", "data-nav=\"activity\"", "data-nav=\"plan\"",
            "data-nav=\"insights\"", "data-nav=\"account\"" })
        {
            if (layout.Contains(marker) is false)
                throw new InvalidOperationException($"The member navigation is missing {marker}.");
        }
        if (layout.Contains("asp-area=\"Member\" asp-controller=\"Home\" asp-action=\"Index\" data-nav=\"home\"") is false)
            throw new InvalidOperationException("Home must be the Member dashboard destination.");
        if (layout.Contains("asp-area=\"Member\" asp-controller=\"Activity\" asp-action=\"Index\" data-nav=\"activity\"") is false)
            throw new InvalidOperationException("Activity must link to the Member Activity page.");
        if (layout.Contains("data-nav=\"activity\" data-soon") || layout.Contains("Activity</span>\n            <span class=\"member-nav-soon-text\">Soon"))
            throw new InvalidOperationException("Activity is implemented and must not be labelled Soon.");
        if (layout.Contains("data-nav-secondary=\"currency\"")) throw new InvalidOperationException("Currency settings belong within Account.");
        if (layout.Split("data-nav=").Length - 1 != 5)
            throw new InvalidOperationException("Primary navigation must contain Home, Activity, Plan, Insights, and Account.");
        foreach (var marker in new[] { "asp-controller=\"Budgets\"", "asp-controller=\"SavingsGoals\"", "asp-controller=\"Bills\"", "asp-controller=\"Notifications\"" })
        {
            var plan = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_PlanNavigation.cshtml"));
            if (plan.Contains(marker) is false)
                throw new InvalidOperationException($"Plan navigation must preserve {marker}.");
        }
        if (layout.Contains(">Dashboard<") || layout.Contains(">Dashboard "))
            throw new InvalidOperationException("The duplicate Dashboard destination must be removed (Home is the dashboard).");

        if (layout.Contains("member-nav-add"))
            throw new InvalidOperationException("The large Add transaction control must be removed from the sidebar.");
        if (layout.IndexOf("data-nav=\"home\"", StringComparison.Ordinal)
            > layout.IndexOf("data-nav=\"activity\"", StringComparison.Ordinal)
            || layout.IndexOf("data-nav=\"activity\"", StringComparison.Ordinal)
            > layout.IndexOf("data-nav=\"plan\"", StringComparison.Ordinal)
            || layout.IndexOf("data-nav=\"plan\"", StringComparison.Ordinal)
            > layout.IndexOf("data-nav=\"insights\"", StringComparison.Ordinal)
            || layout.IndexOf("data-nav=\"insights\"", StringComparison.Ordinal)
            > layout.IndexOf("data-nav=\"account\"", StringComparison.Ordinal))
            throw new InvalidOperationException("Primary Member navigation order must be Home, Activity, Plan, Insights, Account.");
        if (layout.Contains("class=\"member-topbar-link\" asp-area=\"Member\" asp-controller=\"Account\"")
            || layout.Contains("asp-controller=\"Account\" asp-action=\"Index\"") is false)
            throw new InvalidOperationException("Account must be in the sidebar, not duplicated in the header.");
        if (layout.Contains("isAccountController") is false || layout.Contains("\"CurrencyConverter\"") is false)
            throw new InvalidOperationException("Account-related settings must highlight the Account destination.");

        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));
        if (css.Contains("@media (min-width: 900px)") is false)
            throw new InvalidOperationException("member.css must switch to a sidebar at 900px.");
        if (css.Contains("member-nav-item:focus-visible") is false && css.Contains("member-nav-item[aria-current=\"page\"]") is false)
            throw new InvalidOperationException("Member navigation must provide an active and keyboard-focus state.");
        if (css.Contains(".member-nav-divider { display: none;") is false
            || css.Contains(".member-nav-divider { display: block;") is false)
            throw new InvalidOperationException("Desktop-only sidebar dividers must not appear in the mobile bottom navigation.");
        if (css.Contains("member-nav-item[data-soon]") || css.Contains("member-nav-soon-text"))
            throw new InvalidOperationException("Obsolete Soon styling must be removed.");
        Console.WriteLine("PASS: member navigation retains all destinations and remains responsive.");
    }
}
