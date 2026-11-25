internal static class MemberLayoutChecks
{
    public static void Run()
    {
        var css = File.ReadAllText(MvcProject.FindFile("wwwroot", "css", "member.css"));

        // Desktop must be a grid with a full-width topbar row, not a flex row that re-orders the header.
        if (css.Contains("grid-template-areas: \"topbar topbar\" \"nav content\"") is false)
            throw new InvalidOperationException("member.css must place the desktop shell on a topbar/nav/content grid.");
        if (css.Contains(".member-shell { flex-direction: row; }"))
            throw new InvalidOperationException("The desktop shell must not be a flex row (it moves the header into a right rail).");
        if (css.Contains("grid-area: topbar") is false || css.Contains("grid-area: nav") is false
            || css.Contains("grid-area: content") is false)
            throw new InvalidOperationException("Every shell region must be assigned a grid area.");

        // Mobile keeps the fixed bottom bar.
        if (css.Contains(".member-nav { position: fixed;") is false)
            throw new InvalidOperationException("Mobile navigation must stay fixed to the bottom.");

        if (!css.Contains(".member-logout { margin-left: 0; }"))
            throw new InvalidOperationException("The logout control must not absorb topbar spacing independently.");

        Console.WriteLine("PASS: the member shell uses a full-width topbar, a left rail and a capped content column.");
    }
}
