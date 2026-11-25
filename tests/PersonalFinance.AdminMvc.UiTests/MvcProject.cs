internal static class MvcProject
{
    public static string FindFile(params string[] relative)
    {
        var suffix = Path.Combine(new[] { "PersonalFinance.AdminMvc" }.Concat(relative).ToArray());
        var sourceRoot = Environment.GetEnvironmentVariable("OWLI_MVC_SOURCE_ROOT");
        if (!string.IsNullOrWhiteSpace(sourceRoot))
        {
            var configuredPath = Path.Combine(sourceRoot, suffix);
            if (File.Exists(configuredPath)) return configuredPath;
        }
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, suffix)))
            directory = directory.Parent;
        var path = directory is null ? null : Path.Combine(directory.FullName, suffix);
        return path is not null && File.Exists(path)
            ? path
            : throw new InvalidOperationException($"Could not locate {suffix} above {AppContext.BaseDirectory}.");
    }

    public static string FindDirectory(params string[] relative)
    {
        var suffix = Path.Combine(new[] { "PersonalFinance.AdminMvc" }.Concat(relative).ToArray());
        var sourceRoot = Environment.GetEnvironmentVariable("OWLI_MVC_SOURCE_ROOT");
        if (!string.IsNullOrWhiteSpace(sourceRoot))
        {
            var configuredPath = Path.Combine(sourceRoot, suffix);
            if (Directory.Exists(configuredPath)) return configuredPath;
        }
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, suffix)))
            directory = directory.Parent;
        var path = directory is null ? null : Path.Combine(directory.FullName, suffix);
        return path is not null && Directory.Exists(path)
            ? path
            : throw new InvalidOperationException($"Could not locate directory {suffix} above {AppContext.BaseDirectory}.");
    }
}
