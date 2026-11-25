using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class BindingTests
{
    internal static void Register(Action<string, Action> check)
    {
        void Reject(string name, Action<BindingFixture> mutate, BindingFailure expected,
            BindingOperation operation = BindingOperation.DiscoveryOnlyReview) => check("binding: " + name, () =>
        {
            var f = new BindingFixture(operation);
            mutate(f);
            try { Result(f.Validate(), expected); }
            catch (BindingException ex) when (ex.Failure == expected) { return; }
        });
        foreach (var operation in Enum.GetValues<BindingOperation>())
            check("binding: valid synthetic " + operation, () => Result(new BindingFixture(operation).Validate(), BindingFailure.None));

        foreach (var kind in new[] { "scope", "descriptor", "envelope" })
        {
            check("binding: exact bytes and immutable snapshot " + kind, () =>
            {
                var bytes = Document(new BindingFixture(), kind);
                var hash = Convert.ToHexString(SHA256.HashData(bytes));
                var parsed = Parse(kind, bytes);
                var identity = Identity(parsed);
                var valueBefore = ParsedJson(parsed);
                var padded = bytes.Concat(new byte[] { 32, 10 }).ToArray();
                var paddedIdentity = Identity(Parse(kind, padded));
                Require(identity == new BindingByteIdentity(bytes.Length, hash)
                    && paddedIdentity.Sha256 == Convert.ToHexString(SHA256.HashData(padded))
                    && identity.Sha256 != paddedIdentity.Sha256);
                Array.Fill(bytes, (byte)'x');
                Require(Identity(parsed).Sha256 == hash && ParsedJson(parsed) == valueBefore);
            });
            var source = JsonNode.Parse(Document(new BindingFixture(), kind))!.AsObject();
            foreach (var key in source.Select(p => p.Key).ToArray())
            {
                check("binding: required root " + kind + "." + key, () =>
                {
                    var node = (JsonObject)source.DeepClone(); node.Remove(key);
                    Throws(() => Parse(kind, Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
                });
                // Nullable fields are required to be present, but explicit null is permitted.
                if (kind != "scope" || (key is not "endpoint" and not "metadataLineage"))
                    check("binding: null root " + kind + "." + key, () =>
                    {
                        var node = (JsonObject)source.DeepClone(); node[key] = null;
                        Throws(() => Parse(kind, Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
                    });
            }
            check("binding: unknown property " + kind, () =>
            {
                var node = (JsonObject)source.DeepClone(); node["humanApproved"] = true;
                Throws(() => Parse(kind, Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
            });
            check("binding: duplicate property " + kind, () =>
                Throws(() => Parse(kind, Encoding.UTF8.GetBytes(source.ToJsonString().Insert(1, "\"schemaVersion\":1,"))), BindingFailure.MalformedInput));
            foreach (var version in new[] { 0, 99 })
                check("binding: unsupported version " + kind + version, () =>
                {
                    var node = (JsonObject)source.DeepClone(); node["schemaVersion"] = version;
                    Throws(() => Parse(kind, Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.UnsupportedVersion);
                });
            check("binding: unsupported profile " + kind, () =>
            {
                var node = (JsonObject)source.DeepClone(); node["policyProfile"] = "OTHER";
                Throws(() => Parse(kind, Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.UnsupportedVersion);
            });
            foreach (var json in new[] { "", "[]", "null", "{}", "{\"schemaVersion\":\"2\"}", "{/*comment*/}", "{\"schemaVersion\":2,}" })
                check("binding: malformed " + kind + " length=" + json.Length, () =>
                    Throws(() => Parse(kind, Encoding.UTF8.GetBytes(json)), BindingFailure.MalformedInput));
            foreach (var size in new[] { BindingParserV2.MaximumBytes, BindingParserV2.MaximumBytes + 1 })
                check("binding: byte boundary " + kind + size, () =>
                {
                    var original = Document(new BindingFixture(), kind);
                    Require(original.Length < size);
                    var padded = original.Concat(Enumerable.Repeat((byte)' ', size - original.Length)).ToArray();
                    if (size == BindingParserV2.MaximumBytes) Require(Identity(Parse(kind, padded)).ByteLength == size);
                    else Throws(() => Parse(kind, padded), BindingFailure.MalformedInput);
                });
        }
        check("binding: nested duplicate and unknown fields", () =>
        {
            var json = BindingFixture.Json(new BindingFixture().Scope);
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(json.Replace("\"requireNonElevated\":true",
                "\"requireNonElevated\":true,\"requireNonElevated\":true", StringComparison.Ordinal))), BindingFailure.MalformedInput);
            var node = JsonNode.Parse(json)!.AsObject(); node["windows"]!["extra"] = true;
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
        });
        check("binding: nested required property", () =>
        {
            var node = JsonNode.Parse(BindingFixture.Json(new BindingFixture().Scope))!.AsObject();
            node["windows"]!.AsObject().Remove("requireNonElevated");
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
        });
        check("binding: nested collection and string limits", () =>
        {
            var f = new BindingFixture();
            var node = JsonNode.Parse(BindingFixture.Json(f.Scope))!.AsObject();
            node["command"]!["arguments"] = new JsonArray(Enumerable.Range(0, 65).Select(_ => (JsonNode?)JsonValue.Create("x")).ToArray());
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
            node = JsonNode.Parse(BindingFixture.Json(f.Scope))!.AsObject(); node["runtime"]!["version"] = new string('x', 2049);
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
            // Generic malformed-schema coverage only: this does not independently
            // isolate MaxDepth, because the unknown property also rejects.
            var deep = "{\"unknown\":" + new string('[', 13) + "0" + new string(']', 13) + "}";
            Throws(() => BindingParserV2.Scope(Encoding.UTF8.GetBytes(deep)), BindingFailure.MalformedInput);
        });
        check("binding: accepted string limit", () =>
        {
            var f = new BindingFixture();
            var value = f.Scope with { Runtime = f.Scope.Runtime with { Version = new string('x', 2048) } };
            Require(BindingParserV2.Scope(BindingFixture.Bytes(value)).Value.Runtime.Version.Length == 2048);
        });
        foreach (var count in new[] { 32, 33 })
            check("binding: independent inventory boundary " + count, () =>
            {
                var scope = ScopeWithFileCount(count);
                Require(scope.Inventory.Length == count);
                foreach (var role in new[] { "host", "entry", "deps", "config", "runtime" })
                    Require(scope.Inventory.Count(f => f.Role == role) == 1);
                Require(scope.Inventory.Select(f => f.AbsolutePath).Distinct(StringComparer.OrdinalIgnoreCase).Count() == count
                    && scope.Inventory.Select(f => (f.VolumeId, f.FileId)).Distinct().Count() == count
                    && scope.Inventory.All(f => f.ByteLength is > 0 and <= 1_073_741_824
                        && f.Sha256.Length == 64 && f.Sha256.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F')
                        && !string.IsNullOrWhiteSpace(f.VolumeId) && !string.IsNullOrWhiteSpace(f.FileId))
                    && scope.Runtime.DependencyPaths.Length == count - 4
                    && scope.Runtime.DependencyPaths.Length <= 32);
                RequireDependencyReferences(scope);
                var bytes = BindingFixture.Bytes(scope);
                Require(bytes.Length < BindingParserV2.MaximumBytes && count < 64);
                // Positive control: dropping only the last dependency (and its reference)
                // yields an accepted scope. No descriptor/envelope/anchor is validated here.
                var control = WithInventory(scope, scope.Inventory.RemoveAt(count - 1));
                Require(BindingParserV2.Scope(BindingFixture.Bytes(control)).Value.Inventory.Length == count - 1);
                if (count == 32)
                {
                    var parsed = BindingParserV2.Scope(bytes);
                    Require(parsed.Value.Inventory.SequenceEqual(scope.Inventory)
                        && parsed.Value.Runtime.DependencyPaths.SequenceEqual(scope.Runtime.DependencyPaths));
                }
                else Throws(() => BindingParserV2.Scope(bytes), BindingFailure.InvalidInventory);
            });
        check("binding: duplicate paths with distinct physical identities", () =>
        {
            var scope = ScopeWithFileCount(7);
            _ = BindingParserV2.Scope(BindingFixture.Bytes(scope));
            scope = WithInventory(scope, scope.Inventory.SetItem(6,
                scope.Inventory[6] with { AbsolutePath = scope.Inventory[5].AbsolutePath }));
            Require(scope.Inventory.Select(f => (f.VolumeId, f.FileId)).Distinct().Count() == 7
                && scope.Inventory.Select(f => f.AbsolutePath).Distinct(StringComparer.OrdinalIgnoreCase).Count() == 6);
            RequireDependencyReferences(scope);
            Throws(() => BindingParserV2.Scope(BindingFixture.Bytes(scope)), BindingFailure.InvalidInventory);
        });
        check("binding: duplicate physical identities with distinct paths", () =>
        {
            var scope = ScopeWithFileCount(7);
            _ = BindingParserV2.Scope(BindingFixture.Bytes(scope));
            scope = WithInventory(scope, scope.Inventory.SetItem(6, scope.Inventory[6] with
                { VolumeId = scope.Inventory[5].VolumeId, FileId = scope.Inventory[5].FileId }));
            Require(scope.Inventory.Select(f => f.AbsolutePath).Distinct(StringComparer.OrdinalIgnoreCase).Count() == 7
                && scope.Inventory.Select(f => (f.VolumeId, f.FileId)).Distinct().Count() == 6);
            RequireDependencyReferences(scope);
            Throws(() => BindingParserV2.Scope(BindingFixture.Bytes(scope)), BindingFailure.InvalidInventory);
        });
        check("binding: descriptor argument typed limit", () =>
        {
            var f = new BindingFixture();
            Throws(() => BindingParserV2.Descriptor(BindingFixture.Bytes(f.Descriptor with
                { Command = f.Descriptor.Command with { Arguments = Enumerable.Repeat("x", 33).ToImmutableArray() } })), BindingFailure.InvalidInvocation);
        });
        foreach (var id in new[] { "", Guid.Empty.ToString("D"), "A1234567-89AB-4CDE-8FAB-0123456789AB", "invalid" })
            Reject("invalid request " + id, f => f.Scope = f.Scope with { RequestId = id }, BindingFailure.InvalidScope);
        foreach (var hash in new[] { "bad", BindingFixture.H('a'), BindingFixture.H('G'), new string('A', 63) })
            Reject("invalid hash " + hash, f => f.Scope = f.Scope with { ManifestSha256 = hash }, BindingFailure.InvalidInventory);
        foreach (var target in new[] { "PersonalFinanceDb", "personalfinancedb", "master", "OTHER" })
            Reject("wrong target " + target, f => f.Scope = f.Scope with { Target = f.Scope.Target with { Database = target } }, BindingFailure.WrongTarget);
        Reject("target path replacement", f => f.Scope = f.Scope with { Target = f.Scope.Target with { DataPath = @"X:\OTHER.mdf" } }, BindingFailure.WrongTarget);
        Reject("adoption forbidden", f => f.Scope = f.Scope with { Target = f.Scope.Target with { State = "existing" } }, BindingFailure.WrongTarget);
        Reject("Windows expectation change", f => f.Scope = f.Scope with { Windows = f.Scope.Windows with { RequireNonElevated = false } }, BindingFailure.InvalidIdentity);
        Reject("instance owner expectation change", f => f.Scope = f.Scope with { Instance = f.Scope.Instance with { Owner = f.Scope.Instance.Owner with { Sid = "OTHER" } } }, BindingFailure.InvalidIdentity);
        Reject("write prohibition missing", f => f.Scope = f.Scope with { Prohibitions = f.Scope.Prohibitions with { Writes = false } }, BindingFailure.InvalidScope);
        foreach (var field in new[] { "retries", "fallback", "startup", "adoption", "progression" })
            Reject("prohibition " + field, f => f.Scope = f.Scope with { Prohibitions = field switch
            {
                "retries" => f.Scope.Prohibitions with { Retries = false },
                "fallback" => f.Scope.Prohibitions with { Fallback = false },
                "startup" => f.Scope.Prohibitions with { Startup = false },
                "adoption" => f.Scope.Prohibitions with { Adoption = false },
                _ => f.Scope.Prohibitions with { AutomaticProgression = false }
            } }, BindingFailure.InvalidScope);
        Reject("manual review requirement missing", f => f.Scope = f.Scope with { HumanReview = f.Scope.HumanReview with { ManualInvocationRequired = false } }, BindingFailure.InvalidScope);
        Reject("scope hash substitution", f => f.Descriptor = f.Descriptor with { ScopeIdentity = f.Descriptor.ScopeIdentity with { Sha256 = BindingFixture.H('F') } }, BindingFailure.ChainMismatch);
        Reject("scope length substitution", f => f.Descriptor = f.Descriptor with { ScopeIdentity = f.Descriptor.ScopeIdentity with { ByteLength = f.Descriptor.ScopeIdentity.ByteLength + 1 } }, BindingFailure.ChainMismatch);
        Reject("descriptor hash substitution", f => f.Envelope = f.Envelope with { DescriptorIdentity = f.Envelope.DescriptorIdentity with { Sha256 = BindingFixture.H('F') } }, BindingFailure.ChainMismatch);
        Reject("otherwise-consistent replacement bundle", f =>
        {
            f.Scope = f.Scope with { ManifestSha256 = BindingFixture.H('F') };
            f.RechainForTest();
        }, BindingFailure.AnchorMismatch);
        check("binding: replacing the supplied anchor still authenticates nobody", () =>
        {
            var f = new BindingFixture(); f.Scope = f.Scope with { ManifestSha256 = BindingFixture.H('F') };
            f.RechainForTest();
            f.Anchor = f.Anchor with { ScopeIdentity = f.Envelope.ScopeIdentity, DescriptorIdentity = f.Envelope.DescriptorIdentity };
            Result(f.Validate(), BindingFailure.None); // Consistent synthetic data only, never authority.
        });
        check("binding: whitespace replacement changes scope commitment", () =>
        {
            var f = new BindingFixture();
            var scope = BindingParserV2.Scope(BindingFixture.Bytes(f.Scope).Concat(new byte[] { 32 }).ToArray());
            Result(BindingPolicyV2.Validate(scope, BindingParserV2.Descriptor(BindingFixture.Bytes(f.Descriptor)),
                BindingParserV2.Envelope(BindingFixture.Bytes(f.Envelope)), f.Anchor, BindingFixture.Now), BindingFailure.ChainMismatch);
        });

        foreach (var (name, change) in new (string, Func<InvocationDescriptorV1, InvocationDescriptorV1>)[]
        {
            ("invocation ID", d => d with { InvocationId = "f1234567-89ab-4cde-8fab-0123456789ab" }),
            ("nonce", d => d with { Nonce = "f1234567-89ab-4cde-8fab-0123456789ab" }),
            ("operation escalation", d => d with { Operation = BindingOperation.EndpointBoundMetadataReview }),
            ("manifest", d => d with { ManifestSha256 = BindingFixture.H('F') }),
            ("runtime identity", d => d with { RuntimeProfileSha256 = BindingFixture.H('F') }),
            ("working directory", d => d with { Command = d.Command with { WorkingDirectory = @"X:\OTHER" } }),
            ("entry point", d => d with { Command = d.Command with { EntryPoint = @"X:\OTHER.dll" } }),
            ("host", d => d with { Command = d.Command with { HostPath = @"X:\OTHER.exe" } }),
            ("argument reordering", d => d with { Command = d.Command with { Arguments = d.Command.Arguments.Reverse().ToImmutableArray() } }),
            ("argument duplication", d => d with { Command = d.Command with { Arguments = d.Command.Arguments.Add(d.Command.Arguments[1]) } }),
            ("argument boundary", d => d with { Command = d.Command with { Arguments = d.Command.Arguments.SetItem(1, "--operation " + d.Command.Arguments[2]).RemoveAt(2) } }),
            ("shell quoting", d => d with { Command = d.Command with { Arguments = d.Command.Arguments.SetItem(0, "\"" + d.Command.EntryPoint + "\"") } })
        })
            check("binding: isolated descriptor " + name, () =>
            {
                var f = new BindingFixture(); var d = change(f.Descriptor);
                InvocationBindingPolicy.Descriptor(d);
                Throws(() => InvocationBindingPolicy.Bind(f.Scope, BindingParserV2.Scope(BindingFixture.Bytes(f.Scope)).Identity,
                    d, f.Envelope.Launch), BindingFailure.InvalidInvocation);
            });

        Reject("observed invocation mismatch", f => f.Envelope = f.Envelope with { InvocationId = "f1234567-89ab-4cde-8fab-0123456789ab" }, BindingFailure.ObservationMismatch);
        Reject("observed nonce mismatch", f => f.Envelope = f.Envelope with { Launch = f.Envelope.Launch with { Nonce = "f1234567-89ab-4cde-8fab-0123456789ab" } }, BindingFailure.ObservationMismatch);
        Reject("observed entry mismatch", f => f.Envelope = f.Envelope with { Launch = f.Envelope.Launch with { EntryPoint = @"X:\OTHER.dll" } }, BindingFailure.ObservationMismatch);
        Reject("observed launch argument mismatch", f => f.Envelope = f.Envelope with { Launch = f.Envelope.Launch with { Arguments = f.Envelope.Launch.Arguments.Add("--extra") } }, BindingFailure.ObservationMismatch);
        Reject("artifact hash replacement", f => f.Envelope = f.Envelope with { Inventory = f.Envelope.Inventory.SetItem(0, f.Envelope.Inventory[0] with { Sha256 = BindingFixture.H('F') }) }, BindingFailure.ObservationMismatch);
        Reject("physical file replacement", f => f.Envelope = f.Envelope with { Inventory = f.Envelope.Inventory.SetItem(0, f.Envelope.Inventory[0] with { FileId = "OTHER" }) }, BindingFailure.ObservationMismatch);
        Reject("retained handle missing", f => f.Envelope = f.Envelope with { Inventory = f.Envelope.Inventory.SetItem(0, f.Envelope.Inventory[0] with { RetainedHandle = false }) }, BindingFailure.ObservationMismatch);
        Reject("observed manifest mismatch", f => f.Envelope = f.Envelope with { ManifestSha256 = BindingFixture.H('F') }, BindingFailure.ObservationMismatch);
        Reject("runtime mismatch", f => f.Envelope = f.Envelope with { Runtime = f.Envelope.Runtime with { Version = "OTHER" } }, BindingFailure.ObservationMismatch);
        Reject("dependency mismatch", f => f.Envelope = f.Envelope with { Runtime = f.Envelope.Runtime with { DependencyPaths = [@"X:\OTHER.dll"] } }, BindingFailure.ObservationMismatch);
        Reject("runtime environment mismatch", f => f.Envelope = f.Envelope with { Runtime = f.Envelope.Runtime with { UnexpectedEnvironment = true } }, BindingFailure.ObservationMismatch);
        Reject("elevated observed identity", f => f.Envelope = f.Envelope with { Windows = f.Envelope.Windows with { Elevated = true } }, BindingFailure.ObservationMismatch);
        Reject("stopped instance", f => f.Envelope = f.Envelope with { Instance = f.Envelope.Instance with { Running = false } }, BindingFailure.ObservationMismatch);
        Reject("shared instance", f => f.Envelope = f.Envelope with { Instance = f.Envelope.Instance with { Shared = true } }, BindingFailure.ObservationMismatch);
        Reject("wrong observed instance owner", f => f.Envelope = f.Envelope with { Instance = f.Envelope.Instance with { OwnerSid = "OTHER" } }, BindingFailure.ObservationMismatch);
        Reject("wrong observed Windows SID", f => f.Envelope = f.Envelope with { Windows = f.Envelope.Windows with { Sid = "OTHER" } }, BindingFailure.ObservationMismatch);
        Reject("generation drift", f => f.Envelope = f.Envelope with { Instance = f.Envelope.Instance with
            { RecheckedGeneration = f.Envelope.Instance.Generation with { CreationMarker = 999 } } }, BindingFailure.InvalidEndpoint);
        Reject("metadata PID reuse", f => f.Envelope = f.Envelope with { Instance = f.Envelope.Instance with
            { Generation = f.Envelope.Instance.Generation with { CreationMarker = 999 },
              RecheckedGeneration = f.Envelope.Instance.Generation with { CreationMarker = 999 } } }, BindingFailure.InvalidEndpoint,
            BindingOperation.EndpointBoundMetadataReview);
        Reject("endpoint substitution", f => f.Envelope = f.Envelope with { Endpoint = new(
            @"np:\\.\pipe\LOCALDB#OTHER\tsql\query", ReviewPacketPolicy.HistoricalComputer + @"\LOCALDB#OTHER") }, BindingFailure.InvalidEndpoint);
        Reject("all metadata observations agree but differ from frozen endpoint", f =>
        {
            Result(f.Validate(), BindingFailure.None);
            var replacement = new EndpointDeclaration(@"np:\\.\pipe\LOCALDB#SYNTHETIC9\tsql\query",
                ReviewPacketPolicy.HistoricalComputer + @"\LOCALDB#SYNTHETIC9");
            f.Envelope = f.Envelope with { Endpoint = replacement, Instance = f.Envelope.Instance with
                { Endpoint = replacement, RecheckedEndpoint = replacement } };
            Require(f.Envelope.Endpoint == f.Envelope.Instance.Endpoint
                && f.Envelope.Endpoint == f.Envelope.Instance.RecheckedEndpoint
                && f.Scope.Endpoint is not null && f.Envelope.Endpoint != f.Scope.Endpoint.Endpoint
                && f.Envelope.Instance.Generation == f.Scope.Endpoint.Generation
                && f.Envelope.Instance.RecheckedGeneration == f.Scope.Endpoint.Generation);
        }, BindingFailure.InvalidEndpoint, BindingOperation.EndpointBoundMetadataReview);
        Reject("discovery cannot freeze endpoint", f => f.Scope = f.Scope with { Endpoint = new(f.Envelope.Endpoint, f.Envelope.Instance.Generation) }, BindingFailure.InvalidEndpoint);
        Reject("metadata requires lineage", f => f.Scope = f.Scope with { MetadataLineage = null }, BindingFailure.InvalidScope, BindingOperation.EndpointBoundMetadataReview);
        Reject("metadata remote pipe rejected", f => f.Scope = f.Scope with { Endpoint = f.Scope.Endpoint! with
            { Endpoint = f.Scope.Endpoint!.Endpoint with { NamedPipe = @"np:\\remote\pipe\LOCALDB#SYNTHETIC7\tsql\query" } } },
            BindingFailure.InvalidEndpoint, BindingOperation.EndpointBoundMetadataReview);
        Reject("metadata review must be separate", f => f.Scope = f.Scope with { MetadataLineage = f.Scope.MetadataLineage! with
            { DiscoveryReviewReferenceId = f.Scope.HumanReview.ReferenceId } }, BindingFailure.InvalidScope, BindingOperation.EndpointBoundMetadataReview);
        Reject("envelope escalation", f => f.Envelope = f.Envelope with { Operation = BindingOperation.EndpointBoundMetadataReview }, BindingFailure.ObservationMismatch);
        Reject("expired review", f => f.ObservationTime = f.Scope.Window.ExpiresUtc, BindingFailure.InvalidTiming);
        Reject("stale evidence", f => f.ObservationTime = BindingFixture.Now.AddSeconds(20), BindingFailure.InvalidTiming);
        Reject("UTC rollback", f => f.Envelope = f.Envelope with { EndedUtc = f.Envelope.StartedUtc.AddTicks(-1) }, BindingFailure.InvalidTiming);
        Reject("elapsed rollback", f => f.Envelope = f.Envelope with { EndedElapsedTicks = -1 }, BindingFailure.InvalidTiming);
        Reject("terminal time rollback", f => f.Envelope = f.Envelope with { Terminal = f.Envelope.Terminal with { Utc = f.Envelope.StartedUtc } }, BindingFailure.InvalidTiming);
        Reject("elapsed versus UTC contradiction", f => f.Envelope = f.Envelope with { EndedElapsedTicks = 0 }, BindingFailure.InvalidTiming);
        Reject("future acquisition", f => f.ObservationTime = f.Envelope.StartedUtc.AddTicks(-1), BindingFailure.InvalidTiming);
        Reject("missing evidence", f => f.Envelope = f.Envelope with { MissingFields = ["windows"] }, BindingFailure.IncompleteEvidence);
        Reject("too many missing-field labels", f => f.Envelope = f.Envelope with
            { Complete = false, MissingFields = Enumerable.Repeat("missing", 17).ToImmutableArray() }, BindingFailure.IncompleteEvidence);
        Reject("unsupported evidence", f => f.Envelope = f.Envelope with { UnsupportedFields = ["pipe-owner"] }, BindingFailure.IncompleteEvidence);
        Reject("incomplete observations", f => f.Envelope = f.Envelope with { Complete = false }, BindingFailure.IncompleteEvidence);
        Reject("incomplete runtime", f => f.Envelope = f.Envelope with { Runtime = f.Envelope.Runtime with { Complete = false } }, BindingFailure.IncompleteEvidence);
        foreach (var failure in Enum.GetValues<BindingObservationFailure>().Where(x => x != BindingObservationFailure.None))
            Reject("observation failure " + failure, f => f.Envelope = f.Envelope with { Failure = failure }, BindingFailure.IncompleteEvidence);
        foreach (var (name, change) in new (string, Func<BindingTerminal, BindingTerminal>)[]
        {
            ("cleanup", t => t with { CleanupSucceeded = false }), ("disposal", t => t with { Disposed = false }),
            ("cancellation", t => t with { Cancelled = true }), ("deadline", t => t with { DeadlineSignaled = true }),
            ("late completion", t => t with { LateCompletion = true })
        }) Reject("terminal " + name, f => f.Envelope = f.Envelope with { Terminal = change(f.Envelope.Terminal) }, BindingFailure.CleanupFailed);
        check("binding: synthetic promotion cannot enter parsed model", () =>
        {
            var f = new BindingFixture();
            foreach (var claim in new[] { "independentlyObserved", "authenticatedLive", "SyntheticOnly", "1" })
            {
                var node = JsonNode.Parse(BindingFixture.Json(f.Envelope))!.AsObject(); node["source"] = claim;
                Throws(() => BindingParserV2.Envelope(Encoding.UTF8.GetBytes(node.ToJsonString())), BindingFailure.MalformedInput);
            }
            var numeric = JsonNode.Parse(BindingFixture.Json(f.Envelope))!.AsObject(); numeric["source"] = 0;
            Throws(() => BindingParserV2.Envelope(Encoding.UTF8.GetBytes(numeric.ToJsonString())), BindingFailure.MalformedInput);
            Throws(() => BindingPolicyV2.EnvelopeShape(f.Envelope with { Source = (BindingEvidenceSource)99 }), BindingFailure.SyntheticPromotion);
        });
        check("binding: result invariants", () =>
        {
            Result(BindingResult.Matched(), BindingFailure.None);
            foreach (var failure in Enum.GetValues<BindingFailure>().Where(x => x != BindingFailure.None))
                Result(BindingResult.Rejected(failure), failure);
            foreach (var failure in new[] { BindingFailure.None, (BindingFailure)999 })
            {
                try { _ = BindingResult.Rejected(failure); }
                catch (ArgumentOutOfRangeException) { continue; }
                throw new InvalidOperationException("Expected invalid rejection factory input.");
            }
        });
        foreach (var operation in new[] { "discovery-only-review", "endpoint-bound-metadata-review", "live-discovery", "migrate" })
            check("binding: CLI still rejects " + operation, () =>
            {
                using var output = new StringWriter(); using var error = new StringWriter();
                Require(ReviewOfflineEntry.Run(["--operation", operation, "--scope-sha256", BindingFixture.H('A')], output, error) == 2
                    && output.ToString().Length == 0 && error.ToString().Trim() == "REJECTED: only --mode info or --mode self-test-offline is supported.");
            });
    }

    private static ReviewScopeV2 ScopeWithFileCount(int count)
    {
        var scope = new BindingFixture().Scope;
        Require(count >= scope.Inventory.Length && count <= 33);
        var files = scope.Inventory.ToBuilder();
        while (files.Count < count)
        {
            var suffix = files.Count.ToString("D2", System.Globalization.CultureInfo.InvariantCulture);
            files.Add(new BindingExpectedFile("dependency", @"X:\FAKE\BOUNDARY\dependency_" + suffix + ".dll",
                100 + files.Count, BindingFixture.H('A'), "FAKE-VOLUME", "BOUNDARY-" + suffix));
        }
        return WithInventory(scope, files.ToImmutable());
    }
    private static ReviewScopeV2 WithInventory(ReviewScopeV2 scope, ImmutableArray<BindingExpectedFile> files) =>
        scope with { Inventory = files, Runtime = scope.Runtime with { DependencyPaths = files
            .Where(f => f.Role is "runtime" or "dependency").Select(f => f.AbsolutePath).ToImmutableArray() } };
    private static void RequireDependencyReferences(ReviewScopeV2 scope) => Require(scope.Runtime.DependencyPaths
        .SequenceEqual(scope.Inventory.Where(f => f.Role is "runtime" or "dependency").Select(f => f.AbsolutePath),
            StringComparer.Ordinal));

    private static byte[] Document(BindingFixture f, string kind) => kind switch
    {
        "scope" => BindingFixture.Bytes(f.Scope), "descriptor" => BindingFixture.Bytes(f.Descriptor),
        "envelope" => BindingFixture.Bytes(f.Envelope), _ => throw new InvalidOperationException()
    };
    private static object Parse(string kind, byte[] bytes) => kind switch
    {
        "scope" => BindingParserV2.Scope(bytes), "descriptor" => BindingParserV2.Descriptor(bytes),
        "envelope" => BindingParserV2.Envelope(bytes), _ => throw new InvalidOperationException()
    };
    private static BindingByteIdentity Identity(object parsed) => parsed switch
    {
        ParsedBinding<ReviewScopeV2> s => s.Identity, ParsedBinding<InvocationDescriptorV1> d => d.Identity,
        ParsedBinding<ObservationEnvelopeV1> e => e.Identity, _ => throw new InvalidOperationException()
    };
    private static string ParsedJson(object parsed) => parsed switch
    {
        ParsedBinding<ReviewScopeV2> s => BindingFixture.Json(s.Value),
        ParsedBinding<InvocationDescriptorV1> d => BindingFixture.Json(d.Value),
        ParsedBinding<ObservationEnvelopeV1> e => BindingFixture.Json(e.Value),
        _ => throw new InvalidOperationException()
    };
    private static void Throws(Action action, BindingFailure expected)
    {
        try { action(); }
        catch (BindingException ex) when (ex.Failure == expected) { return; }
        throw new InvalidOperationException("Expected exact binding rejection.");
    }
    private static void Result(BindingResult result, BindingFailure expected) => Require(result.Failure == expected
        && result.Consistent == (expected == BindingFailure.None) && result.SyntheticOnly
        && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized
        && result.Diagnostic == "Synthetic binding: " + expected + "." && result.Diagnostic.Length <= 128);
    private static void Require(bool condition)
    {
        if (!condition) throw new InvalidOperationException("Offline binding assertion failed.");
    }
}
