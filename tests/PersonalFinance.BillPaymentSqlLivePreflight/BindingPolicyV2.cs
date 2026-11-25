using System.Text.RegularExpressions;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class BindingPolicyV2
{
    internal const string Profile = "offline-synthetic-binding-v1";
    internal static void Need([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition, BindingFailure failure)
    {
        if (!condition) throw new BindingException(failure);
    }
    internal static bool GuidId(string value) => Guid.TryParseExact(value, "D", out var id)
        && id != Guid.Empty && value == id.ToString("D");
    internal static bool Hash(string value) => value is { Length: 64 }
        && value.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F');
    internal static bool ByteIdentity(BindingByteIdentity i) => i is not null
        && i.ByteLength is > 0 and <= BindingParserV2.MaximumBytes && Hash(i.Sha256);
    private static bool Text(string s) => !string.IsNullOrWhiteSpace(s) && s.Length <= 2048
        && !s.Any(char.IsControl);
    private static bool Path(string p) => Text(p)
        && Regex.IsMatch(p, @"\A[A-Z]:\\[^\r\n]+\z", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1))
        && !p.Contains('/') && !p[2..].Contains(':') && !p.Contains("..", StringComparison.Ordinal)
        && !p.Any(c => "<>\"|?*".Contains(c))
        && p[3..].Split('\\').All(part => part.Length > 0 && part != "."
            && part == part.Trim() && !part.EndsWith('.'));
    private static IdentityDeclaration Owner => new(ReviewPacketPolicy.HistoricalComputer,
        ReviewPacketPolicy.HistoricalAccount, ReviewPacketPolicy.HistoricalSid);
    internal static void WindowShape(BindingWindow w) => Need(w is not null
        && w.IssuedUtc.Offset == TimeSpan.Zero && w.NotBeforeUtc.Offset == TimeSpan.Zero
        && w.ExpiresUtc.Offset == TimeSpan.Zero && w.IssuedUtc <= w.NotBeforeUtc
        && w.NotBeforeUtc < w.ExpiresUtc && w.ExpiresUtc - w.IssuedUtc <= TimeSpan.FromHours(1)
        && w.MaximumEvidenceAgeSeconds is >= 1 and <= 60, BindingFailure.InvalidTiming);
    internal static void CommandShape(BindingCommand c) => Need(c is not null && Path(c.HostPath)
        && Path(c.EntryPoint) && Path(c.WorkingDirectory) && !c.Arguments.IsDefaultOrEmpty
        && c.Arguments.Length <= 32 && c.Arguments.All(Text), BindingFailure.InvalidInvocation);
    private static bool Generation(ProcessGeneration g, string sid) => g is not null
        && g.Pid > 0 && g.CreationMarker > 0 && g.OwnerSid == sid;
    private static bool Pipe(EndpointDeclaration p) => p is not null && Text(p.NamedPipe)
        && Regex.IsMatch(p.NamedPipe, @"\Anp:\\\\\.\\pipe\\LOCALDB#([A-Za-z0-9]{1,64})\\tsql\\query\z",
            RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1))
        && p.InternalServer == ReviewPacketPolicy.HistoricalComputer + @"\" + p.NamedPipe.Split('\\')[4];

    internal static void Scope(ReviewScopeV2 s)
    {
        Need(s is not null && s.SchemaVersion == 2 && s.PolicyProfile == Profile, BindingFailure.UnsupportedVersion);
        Need(GuidId(s.RequestId) && GuidId(s.RunId) && GuidId(s.InvocationId) && GuidId(s.Nonce)
            && Enum.IsDefined(s.Operation), BindingFailure.InvalidScope);
        Need(s.Target is not null && s.Target.RunId == s.RunId, BindingFailure.WrongTarget);
        var name = "PersonalFinanceBillPaymentTests_" + Guid.Parse(s.RunId).ToString("N");
        var root = @"D:\PersonalFinanceBillPaymentSqlTests\" + s.RunId + "\\";
        Need(!string.Equals(s.Target.Database, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase)
            && s.Target.Database == name && s.Target.State == "absent"
            && s.Target.DataPath == root + name + ".mdf" && s.Target.LogPath == root + name + "_log.ldf",
            BindingFailure.WrongTarget);
        Need(s.Windows is not null && s.Windows.Identity == Owner && s.Windows.RequireNonElevated
            && s.Windows.RequireNonImpersonated && s.Instance is not null && s.Instance.Owner == Owner
            && s.Instance.Name == "MSSQLLocalDB" && s.Instance.Version == "17.0.4025.3"
            && s.Instance.RequireRunning && s.Instance.RequirePrivate, BindingFailure.InvalidIdentity);
        if (s.Operation == BindingOperation.DiscoveryOnlyReview)
            Need(s.Endpoint is null && s.MetadataLineage is null, BindingFailure.InvalidEndpoint);
        else
        {
            Need(s.Endpoint is not null && Pipe(s.Endpoint.Endpoint)
                && Generation(s.Endpoint.Generation, Owner.Sid), BindingFailure.InvalidEndpoint);
            var lineage = s.MetadataLineage;
            Need(lineage is not null && GuidId(lineage.DiscoveryInvocationId)
                && lineage.DiscoveryInvocationId != s.InvocationId && ByteIdentity(lineage.DiscoveryReport)
                && GuidId(lineage.DiscoveryReviewReferenceId)
                && GuidId(lineage.SeparateReviewReferenceId) && s.HumanReview is not null
                && lineage.DiscoveryReviewReferenceId != lineage.SeparateReviewReferenceId
                && lineage.SeparateReviewReferenceId == s.HumanReview.ReferenceId
                && lineage.QuerySet is not null && lineage.QuerySet.Name == "absent-target-metadata-v1"
                && Hash(lineage.QuerySet.Sha256), BindingFailure.InvalidScope);
        }
        Need(s.Prohibitions is not null && s.Prohibitions.Writes && s.Prohibitions.Retries
            && s.Prohibitions.Fallback && s.Prohibitions.Startup && s.Prohibitions.Adoption
            && s.Prohibitions.AutomaticProgression, BindingFailure.InvalidScope);
        Need(s.HumanReview is not null && s.HumanReview.Procedure == "external-manual-review-v1"
            && GuidId(s.HumanReview.ReferenceId) && s.HumanReview.IndependentReviewRequired
            && s.HumanReview.ManualInvocationRequired && s.HumanReview.OneInvocationRequired, BindingFailure.InvalidScope);
        WindowShape(s.Window);
        Need(s.Budgets is not null && s.Budgets.OverallSeconds is >= 10 and <= 60
            && s.Budgets.DiscoverySeconds is >= 1 and <= 10 && s.Budgets.DiscoverySeconds <= s.Budgets.OverallSeconds
            && s.Budgets.ConnectSeconds == (s.Operation == BindingOperation.DiscoveryOnlyReview ? 0 : 10)
            && s.Budgets.CommandSeconds == (s.Operation == BindingOperation.DiscoveryOnlyReview ? 0 : 10),
            BindingFailure.InvalidTiming);
        CommandShape(s.Command);
        Need(s.Command.HostPath == ReviewPacketPolicy.HostPath
            && s.Command.EntryPoint == ReviewPacketPolicy.OutputDirectory + "\\" + ReviewPacketPolicy.FileName
            && s.Command.WorkingDirectory == ReviewPacketPolicy.OutputDirectory
            && s.Command.Arguments.SequenceEqual(InvocationBindingPolicy.ScopeArguments(s), StringComparer.Ordinal),
            BindingFailure.InvalidInvocation);
        Need(Hash(s.ManifestSha256) && !s.Inventory.IsDefaultOrEmpty && s.Inventory.Length is >= 5 and <= 32
            && s.Inventory.All(f => f is not null && Text(f.Role) && Path(f.AbsolutePath)
                && f.ByteLength is > 0 and <= 1_073_741_824 && Hash(f.Sha256) && Text(f.VolumeId) && Text(f.FileId))
            && s.Inventory.Select(f => f.AbsolutePath).Distinct(StringComparer.OrdinalIgnoreCase).Count() == s.Inventory.Length
            && s.Inventory.Select(f => (f.VolumeId, f.FileId)).Distinct().Count() == s.Inventory.Length,
            BindingFailure.InvalidInventory);
        foreach (var role in new[] { "host", "entry", "deps", "config", "runtime" })
            Need(s.Inventory.Count(f => f.Role == role) == 1, BindingFailure.InvalidInventory);
        Need(s.Inventory.All(f => f.Role is "host" or "entry" or "deps" or "config" or "runtime" or "dependency")
            && s.Inventory.Single(f => f.Role == "host").AbsolutePath == s.Command.HostPath
            && s.Inventory.Single(f => f.Role == "entry").AbsolutePath == s.Command.EntryPoint
            && s.Inventory.Single(f => f.Role == "deps").AbsolutePath == ReviewPacketPolicy.OutputDirectory
                + @"\PersonalFinance.BillPaymentSqlLivePreflight.deps.json"
            && s.Inventory.Single(f => f.Role == "config").AbsolutePath == ReviewPacketPolicy.OutputDirectory
                + @"\PersonalFinance.BillPaymentSqlLivePreflight.runtimeconfig.json", BindingFailure.InvalidInventory);
        Need(s.Runtime is not null && s.Runtime.Name == "synthetic-runtime-profile-v1" && Text(s.Runtime.Version)
            && Hash(s.Runtime.Sha256) && !s.Runtime.DependencyPaths.IsDefaultOrEmpty
            && s.Runtime.DependencyPaths.Length <= 32
            && s.Runtime.DependencyPaths.SequenceEqual(s.Inventory.Where(f => f.Role is "runtime" or "dependency")
                .Select(f => f.AbsolutePath), StringComparer.Ordinal), BindingFailure.InvalidInventory);
    }

    internal static void EnvelopeShape(ObservationEnvelopeV1 e)
    {
        Need(e is not null && e.SchemaVersion == 1 && e.PolicyProfile == Profile, BindingFailure.UnsupportedVersion);
        Need(e.Source == BindingEvidenceSource.SyntheticOnly, BindingFailure.SyntheticPromotion);
        Need(ByteIdentity(e.ScopeIdentity) && ByteIdentity(e.DescriptorIdentity) && GuidId(e.InvocationId)
            && Enum.IsDefined(e.Operation) && Enum.IsDefined(e.Failure), BindingFailure.InvalidScope);
        Need(e.Windows is not null && Text(e.Windows.Computer) && Text(e.Windows.Account) && Text(e.Windows.Sid)
            && e.Instance is not null && Text(e.Instance.Name) && Text(e.Instance.OwnerComputer)
            && Text(e.Instance.OwnerAccount) && Text(e.Instance.OwnerSid) && Text(e.Instance.Version)
            && Generation(e.InvocationGeneration, Owner.Sid) && Generation(e.Instance.Generation, Owner.Sid)
            && Generation(e.Instance.RecheckedGeneration, Owner.Sid)
            && Pipe(e.Endpoint) && Pipe(e.Instance.Endpoint) && Pipe(e.Instance.RecheckedEndpoint),
            BindingFailure.ObservationMismatch);
        Need(Hash(e.ManifestSha256) && !e.Inventory.IsDefaultOrEmpty && e.Inventory.Length <= 32
            && e.Inventory.All(f => f is not null && Text(f.Role) && Path(f.AbsolutePath)
                && f.ByteLength is > 0 and <= 1_073_741_824 && Hash(f.Sha256) && Text(f.VolumeId) && Text(f.FileId))
            && e.Runtime is not null && Text(e.Runtime.ProfileName) && Text(e.Runtime.Version)
            && Hash(e.Runtime.ProfileSha256) && !e.Runtime.DependencyPaths.IsDefault
            && e.Runtime.DependencyPaths.Length <= 32 && e.Runtime.DependencyPaths.All(Path), BindingFailure.InvalidInventory);
        Need(e.Launch is not null && Path(e.Launch.HostPath) && Path(e.Launch.EntryPoint)
            && Path(e.Launch.WorkingDirectory) && GuidId(e.Launch.Nonce) && !e.Launch.Arguments.IsDefaultOrEmpty
            && e.Launch.Arguments.Length <= 32 && e.Launch.Arguments.All(Text), BindingFailure.ObservationMismatch);
        Need(!e.UnsupportedFields.IsDefault && e.UnsupportedFields.Length <= 16 && e.UnsupportedFields.All(Text)
            && !e.MissingFields.IsDefault && e.MissingFields.Length <= 16 && e.MissingFields.All(Text)
            && e.Terminal is not null, BindingFailure.IncompleteEvidence);
        Need(!e.Complete || (e.UnsupportedFields.IsEmpty && e.MissingFields.IsEmpty
            && e.Failure == BindingObservationFailure.None), BindingFailure.IncompleteEvidence);
        Need(e.StartedUtc.Offset == TimeSpan.Zero && e.EndedUtc.Offset == TimeSpan.Zero
            && e.Terminal.Utc.Offset == TimeSpan.Zero && e.StartedUtc <= e.EndedUtc
            && e.EndedUtc <= e.Terminal.Utc && e.StartedElapsedTicks == 0
            && e.EndedElapsedTicks >= 0 && e.Terminal.ElapsedTicks >= e.EndedElapsedTicks,
            BindingFailure.InvalidTiming);
    }

    internal static BindingResult Validate(ParsedBinding<ReviewScopeV2> scope,
        ParsedBinding<InvocationDescriptorV1> descriptor, ParsedBinding<ObservationEnvelopeV1> envelope,
        BindingReviewAnchor anchor, DateTimeOffset now)
    {
        try
        {
            Need(scope is not null && descriptor is not null && envelope is not null && anchor is not null,
                BindingFailure.MalformedInput);
            var s = scope.Value; var d = descriptor.Value; var e = envelope.Value;
            Scope(s); InvocationBindingPolicy.Descriptor(d); EnvelopeShape(e);
            Need(d.ScopeIdentity == scope.Identity && e.ScopeIdentity == scope.Identity
                && e.DescriptorIdentity == descriptor.Identity, BindingFailure.ChainMismatch);
            Need(anchor.ScopeIdentity == scope.Identity && anchor.DescriptorIdentity == descriptor.Identity
                && anchor.InvocationId == d.InvocationId && anchor.Nonce == d.Nonce
                && anchor.ExternalReviewReferenceId == d.ExternalReviewReferenceId, BindingFailure.AnchorMismatch);
            Need(e.InvocationId == d.InvocationId && e.Operation == s.Operation, BindingFailure.ObservationMismatch);
            InvocationBindingPolicy.Bind(s, scope.Identity, d, e.Launch);
            Need(e.Complete && e.Windows.Complete && e.Instance.Complete && e.Runtime.Complete
                && e.UnsupportedFields.IsEmpty && e.MissingFields.IsEmpty && e.Failure == BindingObservationFailure.None,
                BindingFailure.IncompleteEvidence);
            Need(e.Terminal.CleanupSucceeded && e.Terminal.Disposed && !e.Terminal.Cancelled
                && !e.Terminal.DeadlineSignaled && !e.Terminal.LateCompletion, BindingFailure.CleanupFailed);
            Need(now.Offset == TimeSpan.Zero && s.Window.NotBeforeUtc <= e.StartedUtc
                && s.Window.NotBeforeUtc <= now && e.Terminal.Utc <= now && now < s.Window.ExpiresUtc
                && now - e.StartedUtc <= TimeSpan.FromSeconds(s.Window.MaximumEvidenceAgeSeconds)
                && e.Terminal.ElapsedTicks < TimeSpan.FromSeconds(s.Budgets.OverallSeconds).Ticks
                && e.EndedElapsedTicks <= TimeSpan.FromSeconds(s.Budgets.DiscoverySeconds).Ticks
                && (e.EndedUtc - e.StartedUtc).Ticks == e.EndedElapsedTicks
                && (e.Terminal.Utc - e.StartedUtc).Ticks == e.Terminal.ElapsedTicks,
                BindingFailure.InvalidTiming);
            Need(e.Windows.Computer == s.Windows.Identity.Computer && e.Windows.Account == s.Windows.Identity.Account
                && e.Windows.Sid == s.Windows.Identity.Sid && !e.Windows.Elevated && !e.Windows.Impersonated
                && e.Instance.Name == s.Instance.Name && e.Instance.OwnerComputer == s.Instance.Owner.Computer
                && e.Instance.OwnerAccount == s.Instance.Owner.Account && e.Instance.OwnerSid == s.Instance.Owner.Sid
                && e.Instance.Version == s.Instance.Version && e.Instance.Running && !e.Instance.Shared,
                BindingFailure.ObservationMismatch);
            Need(e.Endpoint == e.Instance.Endpoint && e.Endpoint == e.Instance.RecheckedEndpoint
                && e.Instance.Generation == e.Instance.RecheckedGeneration && (s.Endpoint is null
                || (e.Endpoint == s.Endpoint.Endpoint && e.Instance.Generation == s.Endpoint.Generation)),
                BindingFailure.InvalidEndpoint);
            Need(e.ManifestSha256 == s.ManifestSha256 && e.Inventory.Length == s.Inventory.Length
                && e.Inventory.Zip(s.Inventory).All(pair =>
            {
                var o = pair.First; var x = pair.Second;
                return o.Role == x.Role && o.AbsolutePath == x.AbsolutePath && o.ByteLength == x.ByteLength
                    && o.Sha256 == x.Sha256 && o.VolumeId == x.VolumeId && o.FileId == x.FileId
                    && o.Complete && o.FixedLocalVolume && o.AncestorsVerified && o.RetainedHandle
                    && !o.ReparseFound && !o.AliasFound && o.LinkCount == 1;
            }), BindingFailure.ObservationMismatch);
            Need(e.Runtime.ProfileName == s.Runtime.Name && e.Runtime.Version == s.Runtime.Version
                && e.Runtime.ProfileSha256 == s.Runtime.Sha256 && !e.Runtime.UnexpectedEnvironment
                && e.Runtime.DependencyPaths.SequenceEqual(s.Runtime.DependencyPaths, StringComparer.Ordinal),
                BindingFailure.ObservationMismatch);
            return BindingResult.Matched();
        }
        catch (BindingException ex) { return BindingResult.Rejected(ex.Failure); }
        catch (Exception) { return BindingResult.Rejected(BindingFailure.MalformedInput); }
    }
}
