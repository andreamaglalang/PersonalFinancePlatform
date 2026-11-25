internal static class DesignTokenChecks
{
    public static void Run()
    {
        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));
        var sharedCss = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "site.css"));
        // Approved Owlight visual identity plus established income/expense states.
        foreach (var token in new[]
        {
            "--owli-income: #156b3e", "--owli-income: #6fd6a3",
            "--owli-expense: #a5311f", "--owli-expense: #ff9e8f",
            "--owli-hero-start: #2a1a5e", "--owli-hero-start: #2e1d66",
            "--owli-entries-fill: #ebd9ae", "--owli-entries-fill: #2d2727",
            "--owli-top-fill: #fee3e1", "--owli-top-fill: #2c1e2d",
            "--owli-nav-unselected: #6b6285", "--owli-nav-unselected: #a79fc6",
            "--owli-accent: #ae81f8", "--owli-accent: #ae81f8", "--owli-gold: #ebd9ae",
            "--owli-tile: #f1ebfa", "--owli-tile: #221a44",
            "--owli-field: #f4efff", "--owli-field: #1a1f38",
            "--owli-hairline: #e6dff1", "--owli-hairline: #262b47",
            "--owli-panel-start: #2a1a5e", "--owli-panel-start: #2e1d66",
            "--owli-on-panel: #f4efff", "--owli-panel-income: #8fe3b8",
            "--owli-panel-expense: #ffb4ab", "--owli-on-panel-muted: #bdb1e3",
            "--radius-hero: 28px", "--radius-card: 20px", "--radius-button: 16px"
        })
        {
            if (css.Contains(token) is false)
                throw new InvalidOperationException($"member.css is missing the Android token {token}.");
        }
        foreach (var token in new[]
        {
            "--page: #f4efff", "--surface: #ffffff", "--ink: #2a1659",
            "--accent: #ae81f8", "--gold: #ebd9ae", "--expense-accent: #ffb4ab"
        })
        {
            if (sharedCss.Contains(token) is false)
                throw new InvalidOperationException($"site.css is missing the approved Owlight token {token}.");
        }
        foreach (var style in new[] { ".owli-hero {", ".owli-screen-title {", ".owli-section-title {",
            ".owli-amount {", ".owli-body {", ".owli-caption {", ".owli-label {", ".owli-overline {" })
        {
            if (css.Contains(style) is false)
                throw new InvalidOperationException($"member.css is missing the Owli type style {style}.");
        }
        foreach (var component in new[] { ".member-panel {", ".member-card {", ".member-list {",
            ".member-chip {", ".member-btn {", ".member-field {", ".member-tile {" })
        {
            if (css.Contains(component) is false)
                throw new InvalidOperationException($"member.css is missing the shared component {component}");
        }
        if (css.Contains(".member-dashboard { width: min(100%, 1080px); max-width: 1080px") is false)
            throw new InvalidOperationException("Member data pages must use the wider responsive desktop content width.");
        if (sharedCss.Contains("input:not([type=\"checkbox\"], [type=\"radio\"], [type=\"hidden\"])") is false
            || sharedCss.Contains("input[type=\"checkbox\"], input[type=\"radio\"] { display: inline-block") is false)
            throw new InvalidOperationException("Shared form sizing must exclude checkboxes and radio buttons.");
        if (css.Split("@media (min-width: 900px)").Length - 1 != 1)
            throw new InvalidOperationException("The Member desktop shell must have a single consolidated breakpoint.");
        Console.WriteLine("PASS: shared and Member styles use the approved Owlight palette, responsive widths and type scale.");
    }
}
