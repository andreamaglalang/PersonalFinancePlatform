using System.Collections.Immutable;
using System.Text.RegularExpressions;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class ReviewPacketPolicy
{
    internal const string Discovery = "discovery-only-review";
    internal const string Metadata = "endpoint-bound-metadata-review";
    internal const string FileName = "PersonalFinance.BillPaymentSqlLivePreflight.dll";
    internal const string OutputDirectory =
        @"C:\OWLI-Fixtures\PersonalFinancePlatform\tests\PersonalFinance.BillPaymentSqlLivePreflight\bin\Debug\net10.0";
    internal const string HostPath = @"C:\Program Files\dotnet\dotnet.exe";
    internal const string HistoricalComputer = "OWLI-FIXTURE";
    internal const string HistoricalAccount = @"OWLI-FIXTURE\developer";
    internal const string HistoricalSid = "S-1-5-21-111111111-222222222-333333333-1001";

    internal static ConsistencyResult Validate(ParsedReviewPacket parsed,
        ReviewedConsistencyBaseline baseline, ObservedConsistency observed, DateTimeOffset now)
    {
        try
        {
            Need(parsed is not null && parsed.Packet is not null && baseline is not null && observed is not null,
                ReviewFailure.MalformedPacket);
            var packet = parsed.Packet;
            Need(packet.SchemaVersion == 1 && CanonicalGuid(packet.RequestId)
                && packet.Operation is Discovery or Metadata && packet.Target is not null
                && packet.Command is not null && packet.Command.Host is not null
                && packet.WindowsIdentity is not null && packet.InstanceOwner is not null
                && packet.QuerySet is not null && packet.Safety is not null, ReviewFailure.InvalidScope);
            var target = packet.Target;
            Need(CanonicalGuid(target.RunId), ReviewFailure.WrongTarget);
            var run = Guid.Parse(target.RunId);
            var database = "PersonalFinanceBillPaymentTests_" + run.ToString("N");
            var directory = @"D:\PersonalFinanceBillPaymentSqlTests\" + target.RunId;
            Need(!string.Equals(target.Database, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase)
                && target.Database == database && target.State == "absent"
                && target.DataPath == directory + "\\" + database + ".mdf"
                && target.LogPath == directory + "\\" + database + "_log.ldf", ReviewFailure.WrongTarget);
            var owner = new IdentityDeclaration(HistoricalComputer, HistoricalAccount, HistoricalSid);
            Need(packet.WindowsIdentity == owner && packet.InstanceOwner == owner
                && packet.InstanceName == "MSSQLLocalDB" && packet.InstanceVersion == "17.0.4025.3",
                ReviewFailure.InvalidIdentity);
            Need(packet.Safety.WritesProhibited && packet.Safety.FallbackProhibited
                && packet.Safety.RetriesProhibited && packet.Safety.AutomaticProgressionProhibited,
                ReviewFailure.InvalidScope);
            Need(now.Offset == TimeSpan.Zero && packet.IssuedUtc.Offset == TimeSpan.Zero
                && packet.NotBeforeUtc.Offset == TimeSpan.Zero && packet.ExpiresUtc.Offset == TimeSpan.Zero
                && packet.IssuedUtc <= packet.NotBeforeUtc && packet.NotBeforeUtc <= now
                && now < packet.ExpiresUtc && packet.ExpiresUtc - packet.IssuedUtc <= TimeSpan.FromHours(1),
                ReviewFailure.InvalidWindow);
            Need(packet.ConnectTimeoutSeconds == 10 && packet.CommandTimeoutSeconds == 10
                && packet.OverallTimeoutSeconds is >= 10 and <= 60, ReviewFailure.InvalidScope);
            Need(IsHash(parsed.PacketSha256) && IsHash(packet.ManifestSha256)
                && ValidArtifacts(packet.Artifacts) && packet.Command.Host.AbsolutePath == HostPath
                && packet.Command.Host.ByteLength is > 0 and <= 1_073_741_824
                && IsHash(packet.Command.Host.Sha256), ReviewFailure.InvalidArtifacts);
            Need(IsHash(packet.QuerySet.Sha256) && packet.QuerySet.Name ==
                (packet.Operation == Discovery ? "discovery-no-sql-v1" : "absent-target-metadata-v1"),
                ReviewFailure.InvalidScope);
            if (packet.Operation == Discovery)
                Need(packet.Endpoint is null, ReviewFailure.InvalidEndpoint);
            else
            {
                Need(packet.Endpoint is not null, ReviewFailure.InvalidEndpoint);
                var match = Regex.Match(packet.Endpoint.NamedPipe ?? "",
                    @"\Anp:\\\\\.\\pipe\\LOCALDB#([A-Za-z0-9]{1,64})\\tsql\\query\z",
                    RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
                Need(match.Success && packet.Endpoint.InternalServer ==
                    HistoricalComputer + @"\LOCALDB#" + match.Groups[1].Value, ReviewFailure.InvalidEndpoint);
            }
            var expectedArguments = ImmutableArray.Create(
                OutputDirectory + "\\" + FileName, "--operation", packet.Operation,
                "--request-id", packet.RequestId, "--run-id", target.RunId,
                "--manifest-sha256", packet.ManifestSha256);
            Need(packet.Command.ExecutablePath == OutputDirectory + "\\" + FileName
                && packet.Command.WorkingDirectory == OutputDirectory
                && !packet.Command.Arguments.IsDefault
                && packet.Command.Arguments.SequenceEqual(expectedArguments, StringComparer.Ordinal),
                ReviewFailure.CommandMismatch);
            Need(IsHash(baseline.PacketSha256) && baseline.PacketSha256 == parsed.PacketSha256
                && baseline.RequestId == packet.RequestId && baseline.RunId == target.RunId
                && baseline.ManifestSha256 == packet.ManifestSha256
                && SameArtifacts(baseline.Artifacts, packet.Artifacts)
                && baseline.Host == packet.Command.Host && baseline.Endpoint == packet.Endpoint
                && baseline.QuerySet == packet.QuerySet, ReviewFailure.BaselineMismatch);
            Need(observed.ManifestSha256 == packet.ManifestSha256
                && SameArtifacts(observed.Artifacts, packet.Artifacts)
                && observed.Host == packet.Command.Host && observed.Endpoint == packet.Endpoint
                && observed.QuerySet == packet.QuerySet, ReviewFailure.ObservationMismatch);
            return new(true, ReviewFailure.None,
                "Consistency only; human approval remains external; no execution authorized.", false, false);
        }
        catch (ReviewPacketException ex) { return Rejected(ex.Failure); }
        catch (Exception) { return Rejected(ReviewFailure.MalformedPacket); }
    }

    private static bool CanonicalGuid(string value) =>
        Guid.TryParseExact(value, "D", out var guid) && guid != Guid.Empty && value == guid.ToString("D");

    private static bool IsHash(string value) => value is { Length: 64 }
        && value.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F');

    private static bool ValidArtifacts(ImmutableArray<ArtifactIdentity> artifacts)
    {
        if (artifacts.IsDefault || artifacts.Length != 3) return false;
        var expected = new[] { FileName, "PersonalFinance.BillPaymentSqlLivePreflight.deps.json",
            "PersonalFinance.BillPaymentSqlLivePreflight.runtimeconfig.json" };
        return artifacts.Select(a => a?.RelativePath).SequenceEqual(expected, StringComparer.Ordinal)
            && artifacts.All(a => a is not null && a.ByteLength is > 0 and <= 1_073_741_824 && IsHash(a.Sha256));
    }

    private static bool SameArtifacts(ImmutableArray<ArtifactIdentity> a, ImmutableArray<ArtifactIdentity> b) =>
        !a.IsDefault && !b.IsDefault && a.SequenceEqual(b);

    private static ConsistencyResult Rejected(ReviewFailure failure) =>
        new(false, failure, "Review consistency rejected: " + failure + ".", false, false);

    private static void Need([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition, ReviewFailure failure)
    {
        if (!condition) throw new ReviewPacketException(failure);
    }
}
