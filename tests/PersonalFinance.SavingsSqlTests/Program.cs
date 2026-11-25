using System.Security.Cryptography;
using System.Text.Json;
using PersonalFinance.SavingsSqlTests;

// Intentionally no host, DI container, DbContext, SqlConnection, process invocation,
// configuration fallback or live-operation dispatch. This executable only reads a manifest.
if (args.Length != 4
    || !string.Equals(args[0], "--mode", StringComparison.Ordinal)
    || !string.Equals(args[1], SqlTestTargetGuard.OfflinePhase, StringComparison.Ordinal)
    || !string.Equals(args[2], "--manifest", StringComparison.Ordinal)
    || string.IsNullOrWhiteSpace(args[3])
    || args[3].StartsWith("--", StringComparison.Ordinal))
{
    Console.Error.WriteLine("Usage: --mode validate-offline --manifest <local-manifest.json>. No live modes are implemented.");
    return 2;
}

try
{
    // Reject network/device paths before opening. Do not read API configuration or environment targets.
    var path = Path.GetFullPath(args[3]);
    if (!OperatingSystem.IsWindows()
        || path.Length < 3 || !char.IsAsciiLetter(path[0]) || path[1] != ':' || path[2] != '\\')
        throw new ManifestPolicyException("A local Windows drive path is required for the manifest.");
    if (new DriveInfo(path[..3]).DriveType != DriveType.Fixed || path.AsSpan(2).Contains(':'))
        throw new ManifestPolicyException("A fixed local drive and ordinary manifest file are required.");

    // Reject reparse points in every existing component to avoid following directory/file links.
    for (string? component = path; component is not null; component = Path.GetDirectoryName(component))
    {
        if ((File.GetAttributes(component) & FileAttributes.ReparsePoint) != 0)
            throw new ManifestPolicyException("Manifest paths containing reparse points are prohibited.");
    }

    using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
    var buffer = new byte[SqlTestTargetGuard.MaximumManifestBytes + 1];
    var length = 0;
    while (length < buffer.Length)
    {
        var read = file.Read(buffer, length, buffer.Length - length);
        if (read == 0) break;
        length += read;
    }
    var bytes = buffer.AsMemory(0, length);
    _ = SqlTestTargetGuard.ParseAndValidate(bytes);
    Console.WriteLine("PASS: offline manifest syntax and policy validation only.");
    Console.WriteLine("Manifest SHA256: " + Convert.ToHexString(SHA256.HashData(bytes.Span)));
    Console.WriteLine("No live identity, database absence, file safety, permissions or human approval has been verified.");
    Console.WriteLine("No connection was opened. No live phase is implemented or authorized.");
    return 0;
}
catch (ManifestPolicyException ex)
{
    Console.Error.WriteLine("REJECTED: " + ex.Message);
    return 2;
}
catch (JsonException)
{
    Console.Error.WriteLine("REJECTED: invalid manifest JSON, missing required properties, unknown properties or incorrect field types.");
    return 2;
}
catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException or System.Security.SecurityException)
{
    // Do not echo untrusted manifest contents, supplied paths, credentials or internal exceptions.
    Console.Error.WriteLine("REJECTED: manifest could not be read safely.");
    return 2;
}
