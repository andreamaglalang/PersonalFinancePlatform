/// <summary>L2: default months and dates follow Asia/Manila (BudgetPlanSummary.PhilippineToday), never the server's local clock.</summary>
internal static class ManilaDateChecks
{
    public static void Run()
    {
        var root = Path.GetDirectoryName(MvcProject.FindFile("Program.cs"))!;
        var offenders = Directory.EnumerateFiles(root, "*.*", SearchOption.AllDirectories)
            .Where(f => (f.EndsWith(".cs") || f.EndsWith(".cshtml")) && !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") && !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
            .Where(f => File.ReadAllText(f).Contains("DateTime.Today") || File.ReadAllText(f).Contains("DateTime.Now."))
            .Select(Path.GetFileName).ToArray();
        if (offenders.Length > 0) throw new InvalidOperationException("Server-local dates found (use PhilippineToday): " + string.Join(", ", offenders));
        Console.WriteLine("PASS: MVC default months and dates use Asia/Manila, not the server's local clock (L2).");
    }
}
