using System.Collections.Immutable;
using System.Text;
using System.Text.Json;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

// Fixture construction only. No observer, dispatcher, approval issuer or file reader.
internal sealed class BindingFixture
{
    internal static readonly DateTimeOffset Now = new(2026, 11, 7, 0, 0, 5, TimeSpan.Zero);
    internal ReviewScopeV2 Scope { get; set; }
    internal InvocationDescriptorV1 Descriptor { get; set; }
    internal ObservationEnvelopeV1 Envelope { get; set; }
    internal BindingReviewAnchor Anchor { get; set; }
    internal DateTimeOffset ObservationTime { get; set; } = Now;
    internal static string H(char c) => new(c, 64);
    internal static byte[] Bytes<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, BindingParserV2.Options);
    internal static string Json<T>(T value) => Encoding.UTF8.GetString(Bytes(value));

    internal BindingFixture(BindingOperation operation = BindingOperation.DiscoveryOnlyReview)
    {
        const string run = "a1234567-89ab-4cde-8fab-0123456789ab";
        const string request = "b7654321-cdef-4123-9abc-fedcba987654";
        const string invocation = "c1234567-89ab-4cde-8fab-0123456789ab";
        const string nonce = "d1234567-89ab-4cde-8fab-0123456789ab";
        const string review = "e1234567-89ab-4cde-8fab-0123456789ab";
        var owner = new IdentityDeclaration(ReviewPacketPolicy.HistoricalComputer,
            ReviewPacketPolicy.HistoricalAccount, ReviewPacketPolicy.HistoricalSid);
        var name = "PersonalFinanceBillPaymentTests_" + Guid.Parse(run).ToString("N");
        var root = @"D:\PersonalFinanceBillPaymentSqlTests\" + run + "\\";
        var entry = ReviewPacketPolicy.OutputDirectory + "\\" + ReviewPacketPolicy.FileName;
        var inventory = ImmutableArray.Create(
            new BindingExpectedFile("host", ReviewPacketPolicy.HostPath, 10, H('A'), "FAKE-VOLUME", "HOST"),
            new BindingExpectedFile("entry", entry, 20, H('B'), "FAKE-VOLUME", "ENTRY"),
            new BindingExpectedFile("deps", ReviewPacketPolicy.OutputDirectory + @"\PersonalFinance.BillPaymentSqlLivePreflight.deps.json",
                30, H('C'), "FAKE-VOLUME", "DEPS"),
            new BindingExpectedFile("config", ReviewPacketPolicy.OutputDirectory + @"\PersonalFinance.BillPaymentSqlLivePreflight.runtimeconfig.json",
                40, H('D'), "FAKE-VOLUME", "CONFIG"),
            new BindingExpectedFile("runtime", @"X:\FAKE\runtime.dll", 50, H('E'), "FAKE-VOLUME", "RUNTIME"),
            new BindingExpectedFile("dependency", @"X:\FAKE\native.dll", 60, H('F'), "FAKE-VOLUME", "NATIVE"));
        // Historical strings are EXPECTATIONS. All matching observations/hashes are synthetic.
        var endpoint = new EndpointDeclaration(@"np:\\.\pipe\LOCALDB#SYNTHETIC7\tsql\query",
            ReviewPacketPolicy.HistoricalComputer + @"\LOCALDB#SYNTHETIC7");
        var generation = new ProcessGeneration(123, 456, owner.Sid);
        var frozen = new BindingFrozenEndpoint(endpoint, generation);
        Scope = new(2, BindingPolicyV2.Profile, request, run, invocation, nonce, operation,
            new(run, name, root + name + ".mdf", root + name + "_log.ldf", "absent"),
            new(owner, true, true), new("MSSQLLocalDB", owner, "17.0.4025.3", true, true),
            operation == BindingOperation.DiscoveryOnlyReview ? null : frozen, H('A'), inventory,
            new("synthetic-runtime-profile-v1", "FAKE-10", H('B'), inventory.Where(f => f.Role is "runtime" or "dependency")
                .Select(f => f.AbsolutePath).ToImmutableArray()),
            new(ReviewPacketPolicy.HostPath, entry, ReviewPacketPolicy.OutputDirectory, []),
            new(Now.AddMinutes(-1), Now.AddSeconds(-10), Now.AddMinutes(5), 10),
            new(30, 10, operation == BindingOperation.DiscoveryOnlyReview ? 0 : 10,
                operation == BindingOperation.DiscoveryOnlyReview ? 0 : 10),
            new(true, true, true, true, true, true), new("external-manual-review-v1", review, true, true, true),
            operation == BindingOperation.DiscoveryOnlyReview ? null : new(
                "f1234567-89ab-4cde-8fab-0123456789ab", new(100, H('C')),
                "a2234567-89ab-4cde-8fab-0123456789ab", review, new("absent-target-metadata-v1", H('D'))));
        Scope = Scope with { Command = Scope.Command with { Arguments = InvocationBindingPolicy.ScopeArguments(Scope) } };
        var parsed = BindingParserV2.Scope(Bytes(Scope));
        Descriptor = new(1, BindingPolicyV2.Profile, invocation, request, run, nonce, operation, parsed.Identity,
            Scope.Command with { Arguments = InvocationBindingPolicy.FinalArguments(Scope, parsed.Identity) },
            Scope.ManifestSha256, Scope.Runtime.Sha256, Scope.Window, review);
        var descriptor = BindingParserV2.Descriptor(Bytes(Descriptor));
        var start = Now.AddSeconds(-2);
        Envelope = new(1, BindingPolicyV2.Profile, BindingEvidenceSource.SyntheticOnly, parsed.Identity,
            descriptor.Identity, invocation, operation, new(789, 987, owner.Sid), start, start.AddSeconds(1),
            0, TimeSpan.FromSeconds(1).Ticks, new(owner.Computer, owner.Account, owner.Sid, false, false, true),
            new(Scope.Instance.Name, owner.Computer, owner.Account, owner.Sid, Scope.Instance.Version,
                true, false, generation, endpoint, generation, endpoint, true), endpoint, Scope.ManifestSha256,
            inventory.Select(f => new BindingObservedFile(f.Role, f.AbsolutePath, f.ByteLength, f.Sha256,
                f.VolumeId, f.FileId, true, true, true, false, false, 1, true)).ToImmutableArray(),
            new(Scope.Runtime.Name, Scope.Runtime.Version, Scope.Runtime.Sha256, Scope.Runtime.DependencyPaths, true, false),
            new(Descriptor.Command.HostPath, entry, Descriptor.Command.WorkingDirectory, Descriptor.Command.Arguments, nonce, true),
            true, [], [], BindingObservationFailure.None,
            new(start.AddSeconds(1), TimeSpan.FromSeconds(1).Ticks, true, true, false, false, false));
        Anchor = new(parsed.Identity, descriptor.Identity, invocation, nonce, review);
    }

    internal BindingResult Validate() => BindingPolicyV2.Validate(BindingParserV2.Scope(Bytes(Scope)),
        BindingParserV2.Descriptor(Bytes(Descriptor)), BindingParserV2.Envelope(Bytes(Envelope)), Anchor, ObservationTime);

    // Test-only reconstruction of a consistent replacement chain. Does NOT refresh
    // Anchor: the independently fixed fixture commitment must reject replacements.
    internal void RechainForTest()
    {
        var scope = BindingParserV2.Scope(Bytes(Scope));
        Descriptor = Descriptor with { ScopeIdentity = scope.Identity,
            Command = Scope.Command with { Arguments = InvocationBindingPolicy.FinalArguments(Scope, scope.Identity) },
            ManifestSha256 = Scope.ManifestSha256, RuntimeProfileSha256 = Scope.Runtime.Sha256 };
        var descriptor = BindingParserV2.Descriptor(Bytes(Descriptor));
        Envelope = Envelope with { ScopeIdentity = scope.Identity, DescriptorIdentity = descriptor.Identity,
            Launch = Envelope.Launch with { Arguments = Descriptor.Command.Arguments }, ManifestSha256 = Scope.ManifestSha256 };
    }
}
