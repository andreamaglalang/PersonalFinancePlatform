internal static class MemberSessionGateChecks
{
    public static void Run()
    {
        var gate = File.ReadAllText(MvcProject.FindFile("Services", "MemberSessionGate.cs"));

        // The member gate must never require the Administrator role.
        if (gate.Contains("IsInRole(\"Administrator\")") || gate.Contains("AdministratorPolicy")
            || gate.Contains("Roles.Contains"))
            throw new InvalidOperationException("The member session gate must not require the Administrator role.");

        // It must bind the token to the caller's own session identity.
        foreach (var marker in new[] { "session.Tokens.AccessToken", "AdminSessionStore.SessionClaim",
            "ClaimTypes.NameIdentifier", "IsCurrent(session)", "SignOutAsync(AdminSessionStore.Scheme)" })
        {
            if (gate.Contains(marker) is false)
                throw new InvalidOperationException($"The member session gate is missing {marker}.");
        }

        // No Member controller may be administrator-only.
        var memberRoot = MvcProject.FindDirectory("Areas", "Member");
        foreach (var file in Directory.GetFiles(memberRoot, "*.cs", SearchOption.AllDirectories))
        {
            var text = File.ReadAllText(file);
            if (text.Contains("AdministratorPolicy") || text.Contains("api/categories/defaults"))
                throw new InvalidOperationException($"{Path.GetFileName(file)} must not use administrator-only authorization or routes.");
        }

        Console.WriteLine("PASS: the member session gate binds the token to the caller's own session with no Administrator check.");
    }
}
