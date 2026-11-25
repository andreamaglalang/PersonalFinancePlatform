using System.Security.Cryptography;
using System.Text.Json;
using PersonalFinance.SavingsSqlLiveTests;

// Offline declaration inspection ONLY. No SQL/production references, test links, host,
// process invocation, live phase dispatcher or external configuration resolution exists.
if (args.Length != 4 || args[0] != "--mode" || args[1] != "validate-policy-offline"
    || args[2] != "--policy" || string.IsNullOrWhiteSpace(args[3]) || args[3].StartsWith("--", StringComparison.Ordinal))
{
    Console.Error.WriteLine("Usage: --mode validate-policy-offline --policy <local-policy.json>. Live phases are unavailable.");
    return 2;
}

try
{
    var path = Path.GetFullPath(args[3]);
    if (!OperatingSystem.IsWindows() || path.Length < 3 || !char.IsAsciiLetter(path[0])
        || path[1] != ':' || path[2] != '\\' || path.AsSpan(2).Contains(':'))
        throw new PolicyDeclarationException("A local Windows policy file is required.");
    if (new DriveInfo(path[..3]).DriveType != DriveType.Fixed)
        throw new PolicyDeclarationException("Only a fixed local drive is permitted.");
    for (string? component = path; component is not null; component = Path.GetDirectoryName(component))
    {
        if ((File.GetAttributes(component) & FileAttributes.ReparsePoint) != 0)
            throw new PolicyDeclarationException("Policy path reparse points are prohibited.");
    }

    using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
    var buffer = new byte[OfflineAuthorizationPolicy.MaximumBytes + 1];
    var length = 0;
    while (length < buffer.Length)
    {
        var read = file.Read(buffer, length, buffer.Length - length);
        if (read == 0) break;
        length += read;
    }
    var bytes = buffer.AsMemory(0, length);
    var declaration = OfflineAuthorizationPolicy.ParseAndValidate(bytes, DateTimeOffset.UtcNow);
    // Even a structurally valid live-phase record cannot acquire execution authority here.
    if (declaration.Phase != DeclaredPhase.OfflineValidation)
        throw new PolicyDeclarationException("Live-phase declarations cannot be requested through this offline-only skeleton.");

    Console.WriteLine("VALID: offline policy structure and declaration consistency only.");
    Console.WriteLine("Policy SHA256: " + Convert.ToHexString(SHA256.HashData(bytes.Span)));
    Console.WriteLine("No human authorization, signature, artifact, manifest, server or database identity has been verified.");
    Console.WriteLine("No execution authority was granted. No live capability exists or was invoked.");
    return 0;
}
catch (PolicyDeclarationException ex)
{
    Console.Error.WriteLine("REJECTED: " + ex.Message);
    return 2;
}
catch (JsonException)
{
    Console.Error.WriteLine("REJECTED: malformed policy, missing/unknown fields, null values or invalid field types.");
    return 2;
}
catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException
    or NotSupportedException or System.Security.SecurityException or InvalidOperationException or KeyNotFoundException)
{
    // Never echo untrusted document fields, supplied paths, secrets or internal exception details.
    Console.Error.WriteLine("REJECTED: policy could not be read or validated safely.");
    return 2;
}
