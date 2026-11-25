using System.Text.RegularExpressions;
using System.Globalization;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class ObservationPolicy
{
    internal static void Need([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool value,
        ObservationFailure failure)
    {
        if (!value) throw new ObservationRejection(failure);
    }

    internal static void Expectation(DiscoveryExpectation e)
    {
        Need(e is not null && e.Operation == "offline-discovery-simulation"
            && ValidIdentity(e.Identity) && !string.IsNullOrWhiteSpace(e.Instance)
            && !string.IsNullOrWhiteSpace(e.Version) && e.Launch is not null,
            ObservationFailure.InvalidExpectation);
        Need(e.Budget >= TimeSpan.FromSeconds(10) && e.Budget <= TimeSpan.FromSeconds(60)
            && e.IssuedUtc.Offset == TimeSpan.Zero && e.NotBeforeUtc.Offset == TimeSpan.Zero
            && e.ExpiresUtc.Offset == TimeSpan.Zero && e.IssuedUtc <= e.NotBeforeUtc
            && e.NotBeforeUtc < e.ExpiresUtc && e.ExpiresUtc - e.IssuedUtc <= TimeSpan.FromHours(1),
            ObservationFailure.InvalidExpectation);
        var l = e.Launch;
        Need(Absolute(l.HostPath) && Absolute(l.EntryPath) && Absolute(l.WorkingDirectory)
            && !string.IsNullOrWhiteSpace(l.RuntimeVersion) && !l.Arguments.IsDefaultOrEmpty
            && l.Arguments.All(a => !string.IsNullOrEmpty(a)) && l.Arguments[0] == l.EntryPath
            && !l.Closure.IsDefaultOrEmpty && l.Closure.All(ValidFile)
            && l.Closure.Select(f => f.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() == l.Closure.Length
            && l.Closure.Select(f => (f.Volume, f.FileId)).Distinct().Count() == l.Closure.Length
            && l.Closure.Any(f => f.Path == l.HostPath) && l.Closure.Any(f => f.Path == l.EntryPath),
            ObservationFailure.InvalidExpectation);
    }

    internal static void Window(DiscoveryExpectation e, DateTimeOffset now, DateTimeOffset previous,
        TimeSpan elapsed, TimeSpan previousElapsed)
    {
        Need(now.Offset == TimeSpan.Zero && now >= previous && elapsed >= previousElapsed,
            ObservationFailure.ClockRollback);
        Need(e.NotBeforeUtc <= now && now < e.ExpiresUtc, ObservationFailure.InvalidWindow);
        Need(elapsed >= TimeSpan.Zero && elapsed < e.Budget, ObservationFailure.BudgetExpired);
    }

    internal static void Windows(DiscoveryExpectation e, SimulatedWindows w) =>
        Need(w is not null && w.Complete && w.Identity == e.Identity && !w.Impersonated && !w.Elevated,
            ObservationFailure.WindowsMismatch);

    internal static void Instance(DiscoveryExpectation e, SimulatedInstance i)
    {
        Need(i is not null && i.Complete && i.Name == e.Instance && i.Owner == e.Identity
            && i.Version == e.Version && i.Running && !i.Shared && i.Process is not null
            && i.Process.Pid > 0 && i.Process.CreationMarker > 0 && i.Process.OwnerSid == e.Identity.Sid,
            ObservationFailure.InstanceMismatch);
        Need(i.Endpoint is not null, ObservationFailure.EndpointMismatch);
        var match = Regex.Match(i.Endpoint.NamedPipe ?? "",
            @"\Anp:\\\\\.\\pipe\\LOCALDB#([A-Za-z0-9]{1,64})\\tsql\\query\z",
            RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
        Need(match.Success && i.Endpoint.InternalServer == e.Identity.Computer + @"\LOCALDB#" + match.Groups[1].Value,
            ObservationFailure.EndpointMismatch);
    }

    internal static void Artifacts(DiscoveryExpectation e, SimulatedArtifacts a) =>
        Need(a is not null && a.Complete && !a.Files.IsDefault && a.Files.Length == e.Launch.Closure.Length
            && a.Files.Zip(e.Launch.Closure).All(pair => pair.First is not null
                && SameFile(pair.Second, pair.First.Identity))
            && a.Files.All(f => f is not null && f.Complete && f.FixedLocalVolume && f.AncestorsVerified
                && !f.ReparseFound && !f.AliasFound && f.LinkCount == 1), ObservationFailure.ArtifactMismatch);

    internal static void Launch(DiscoveryExpectation e, SimulatedLaunch l) =>
        Need(l is not null && l.Complete && !l.UnexpectedEnvironment && l.ClosureVerified
            && l.HostPath == e.Launch.HostPath && l.EntryPath == e.Launch.EntryPath
            && l.WorkingDirectory == e.Launch.WorkingDirectory && l.RuntimeVersion == e.Launch.RuntimeVersion
            && !l.Arguments.IsDefault && l.Arguments.SequenceEqual(e.Launch.Arguments, StringComparer.Ordinal),
            ObservationFailure.LaunchMismatch);

    private static bool ValidIdentity(IdentityDeclaration i) => i is not null
        && !string.IsNullOrWhiteSpace(i.Computer) && i.Computer == i.Computer.Trim()
        && !i.Computer.Any(c => char.IsControl(c) || c == '\\')
        && !string.IsNullOrWhiteSpace(i.Account)
        && i.Account.StartsWith(i.Computer + "\\", StringComparison.Ordinal)
        && i.Account.Length > i.Computer.Length + 1
        && ValidAccountSuffix(i.Account[(i.Computer.Length + 1)..]) && ValidSid(i.Sid);
    private static bool ValidAccountSuffix(string suffix) => suffix == suffix.Trim()
        && !suffix.Any(c => char.IsControl(c) || c == '\\');
    private static bool ValidSid(string sid)
    {
        // This model supports only canonical account SIDs: revision 1, NT authority,
        // domain subauthority 21 and exactly four UInt32 numeric components.
        if (sid is null || sid.Length is < 16 or > 52) return false;
        var parts = sid.Split('-');
        if (parts.Length != 8 || parts[0] != "S" || parts[1] != "1"
            || parts[2] != "5" || parts[3] != "21") return false;
        return parts.Skip(4).All(p => p.Length is >= 1 and <= 10
            && (p.Length == 1 || p[0] != '0') && p.All(c => c is >= '0' and <= '9')
            && uint.TryParse(p, NumberStyles.None, CultureInfo.InvariantCulture, out _));
    }
    private static bool Absolute(string p) => !string.IsNullOrEmpty(p)
        && Regex.IsMatch(p, @"\A[A-Z]:\\[^\r\n]+\z", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1))
        && !p.Contains('/') && !p.Contains("..", StringComparison.Ordinal) && !p[2..].Contains(':')
        && !p.Any(c => char.IsControl(c) || "<>\"|?*".Contains(c))
        && p[3..].Split('\\').All(s => s.Length > 0 && s != "." && s == s.Trim()
            && !s.EndsWith('.'));
    private static bool ValidFile(ExpectedFile f) => f is not null && Absolute(f.Path)
        && f.Length is > 0 and <= 1_073_741_824 && f.Sha256 is { Length: 64 }
        && f.Sha256.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F')
        && !string.IsNullOrWhiteSpace(f.Volume) && !string.IsNullOrWhiteSpace(f.FileId);
    private static bool SameFile(ExpectedFile expected, SimulatedFileIdentity observed) => observed is not null
        && expected.Path == observed.Path && expected.Length == observed.Length
        && expected.Sha256 == observed.Sha256 && expected.Volume == observed.Volume
        && expected.FileId == observed.FileId;
}
