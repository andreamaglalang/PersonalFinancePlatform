using System.Collections.Immutable;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class ReviewPacketTests
{
    internal static int Run(TextWriter output, TextWriter error)
    {
        var passed = 0;
        var failed = 0;
        void Require(bool condition)
        {
            if (!condition) throw new InvalidOperationException("Offline assertion failed.");
        }
        void Check(string name, Action test)
        {
            try { test(); passed++; output.WriteLine("PASS " + name); }
            catch (Exception ex) { failed++; error.WriteLine("FAIL " + name + ": " + ex.GetType().Name); }
        }
        void RejectJson(string name, string json) => Check(name, () =>
        {
            try { _ = ReviewPacketParser.Parse(Encoding.UTF8.GetBytes(json)); }
            catch (ReviewPacketException ex) when (ex.Failure == ReviewFailure.MalformedPacket) { return; }
            throw new InvalidOperationException("Expected malformed-packet rejection.");
        });
        void Reject(string name, Action<Fixture> mutate, ReviewFailure expected) => Check(name, () =>
        {
            var fixture = new Fixture();
            mutate(fixture);
            var result = fixture.Validate();
            Require(!result.Consistent && result.Failure == expected
                && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized
                && result.Diagnostic.Length <= 128
                && !result.Diagnostic.Contains("UNTRUSTED_SECRET", StringComparison.Ordinal));
        });
        void RequireFailure(ConsistencyResult result, ReviewFailure expected)
        {
            Require(!result.Consistent && result.Failure == expected
                && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized
                && result.Diagnostic == "Review consistency rejected: " + expected + "."
                && result.Diagnostic.Length <= 128);
        }
        foreach (var operation in new[] { ReviewPacketPolicy.Discovery, ReviewPacketPolicy.Metadata })
            Check("valid proposed scope: " + operation, () =>
            {
                var fixture = new Fixture(operation);
                var result = fixture.Validate();
                Require(result.Consistent && result.Failure == ReviewFailure.None
                    && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized
                    && result.Diagnostic == "Consistency only; human approval remains external; no execution authorized.");
            });
        var validNode = JsonNode.Parse(Fixture.Serialize(new Fixture().Parsed.Packet))!.AsObject();
        foreach (var property in validNode.Select(p => p.Key).ToArray())
        {
            var missing = (JsonObject)validNode.DeepClone();
            missing.Remove(property);
            RejectJson("missing root field " + property, missing.ToJsonString());
            if (property != "endpoint")
            {
                var nullField = (JsonObject)validNode.DeepClone();
                nullField[property] = null;
                RejectJson("null required root field " + property, nullField.ToJsonString());
            }
        }
        foreach (var (name, json) in new[]
        {
            ("empty", ""), ("invalid", "{"), ("array root", "[]"), ("null root", "null"),
            ("trailing comma", "{\"schemaVersion\":1,}"), ("comment", "{/*comment*/}"),
            ("root duplicate", validNode.ToJsonString().Insert(1, "\"schemaVersion\":1,"))
        })
            RejectJson("malformed JSON " + name, json);
        var unknown = (JsonObject)validNode.DeepClone();
        unknown["humanApproved"] = true;
        RejectJson("unsigned approval claim cannot grant authority", unknown.ToJsonString());
        var caseField = (JsonObject)validNode.DeepClone();
        caseField.Remove("schemaVersion");
        caseField["SchemaVersion"] = 1;
        RejectJson("case-sensitive fields", caseField.ToJsonString());
        var wrongType = (JsonObject)validNode.DeepClone();
        wrongType["schemaVersion"] = "1";
        RejectJson("wrong field type", wrongType.ToJsonString());
        var nestedDuplicate = validNode.ToJsonString().Replace("\"writesProhibited\":true",
            "\"writesProhibited\":true,\"writesProhibited\":true", StringComparison.Ordinal);
        RejectJson("nested duplicate", nestedDuplicate);
        Check("oversized parser input", () =>
        {
            try { _ = ReviewPacketParser.Parse(new byte[ReviewPacketParser.MaximumBytes + 1]); }
            catch (ReviewPacketException ex) when (ex.Failure == ReviewFailure.MalformedPacket) { return; }
            throw new InvalidOperationException("Expected size rejection.");
        });
        Reject("unsupported schema", x => x.Change(p => p with { SchemaVersion = 2 }), ReviewFailure.InvalidScope);
        Reject("malformed request ID", x => x.Change(p => p with { RequestId = "invalid" }), ReviewFailure.InvalidScope);
        foreach (var operation in new[] { "provision", "migrate", "cleanup", "live-read-only-preflight", "UNTRUSTED_SECRET" })
            Reject("unsupported operation " + operation, x => x.Change(p => p with { Operation = operation }),
                ReviewFailure.InvalidScope);
        foreach (var run in new[] { "", Guid.Empty.ToString("D"), "A1234567-89AB-4CDE-8FAB-0123456789AB", "invalid" }
            .Select((value, index) => (value, index)))
            Reject("invalid run case " + run.index, x => x.Change(p => p with
                { Target = p.Target with { RunId = run.value } }), ReviewFailure.WrongTarget);
        foreach (var target in new[] { "PersonalFinanceDb", "personalfinancedb", "master", "UnrelatedTests" })
            Reject("forbidden target " + target, x => x.Change(p => p with
                { Target = p.Target with { Database = target } }), ReviewFailure.WrongTarget);
        Reject("wrong MDF path", x => x.Change(p => p with
            { Target = p.Target with { DataPath = @"D:\wrong.mdf" } }), ReviewFailure.WrongTarget);
        Reject("wrong LDF path", x => x.Change(p => p with
            { Target = p.Target with { LogPath = @"\\server\wrong.ldf" } }), ReviewFailure.WrongTarget);
        Reject("existing target scope", x => x.Change(p => p with
            { Target = p.Target with { State = "existing" } }), ReviewFailure.WrongTarget);
        Reject("unexpected Windows SID", x => x.Change(p => p with
            { WindowsIdentity = p.WindowsIdentity with { Sid = "wrong" } }), ReviewFailure.InvalidIdentity);
        Reject("unexpected owner", x => x.Change(p => p with
            { InstanceOwner = p.InstanceOwner with { Account = "wrong" } }), ReviewFailure.InvalidIdentity);
        Reject("unexpected instance", x => x.Change(p => p with { InstanceName = "wrong" }), ReviewFailure.InvalidIdentity);
        Reject("unexpected version", x => x.Change(p => p with { InstanceVersion = "wrong" }), ReviewFailure.InvalidIdentity);
        foreach (var flag in new[] { "writes", "fallback", "retries", "progression" })
            Reject("required prohibition " + flag, x => x.Change(p => p with
            {
                Safety = flag switch
                {
                    "writes" => p.Safety with { WritesProhibited = false },
                    "fallback" => p.Safety with { FallbackProhibited = false },
                    "retries" => p.Safety with { RetriesProhibited = false },
                    _ => p.Safety with { AutomaticProgressionProhibited = false }
                }
            }), ReviewFailure.InvalidScope);
        Reject("expired review", x => x.Change(p => p with { ExpiresUtc = Fixture.Now }), ReviewFailure.InvalidWindow);
        Reject("future review", x => x.Change(p => p with { NotBeforeUtc = Fixture.Now.AddMinutes(1) }),
            ReviewFailure.InvalidWindow);
        Reject("invalid review chronology", x => x.Change(p => p with { IssuedUtc = Fixture.Now.AddMinutes(1) }),
            ReviewFailure.InvalidWindow);
        Reject("non-UTC review", x => x.Change(p => p with { IssuedUtc = p.IssuedUtc.ToOffset(TimeSpan.FromHours(1)) }),
            ReviewFailure.InvalidWindow);
        Reject("overlong review window", x => x.Change(p => p with { ExpiresUtc = Fixture.Now.AddHours(2) }),
            ReviewFailure.InvalidWindow);
        Reject("unbounded timeout", x => x.Change(p => p with { OverallTimeoutSeconds = 0 }), ReviewFailure.InvalidScope);
        Reject("malformed manifest hash", x => x.Change(p => p with { ManifestSha256 = "invalid" }),
            ReviewFailure.InvalidArtifacts);
        Reject("empty artifact inventory", x => x.Change(p => p with { Artifacts = ImmutableArray<ArtifactIdentity>.Empty }),
            ReviewFailure.InvalidArtifacts);
        Reject("artifact path traversal", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(0, p.Artifacts[0] with { RelativePath = @"..\other.dll" }) }),
            ReviewFailure.InvalidArtifacts);
        Reject("invalid artifact size", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(0, p.Artifacts[0] with { ByteLength = -1 }) }), ReviewFailure.InvalidArtifacts);
        Reject("invalid artifact hash", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(0, p.Artifacts[0] with { Sha256 = "wrong" }) }), ReviewFailure.InvalidArtifacts);
        Reject("extra artifact unsupported", x => x.Change(p => p with
            { Artifacts = p.Artifacts.Add(p.Artifacts[0]) }), ReviewFailure.InvalidArtifacts);
        Reject("arbitrary host", x => x.Change(p => p with
            { Command = p.Command with { Host = p.Command.Host with { AbsolutePath = @"C:\other.exe" } } }),
            ReviewFailure.InvalidArtifacts);
        Reject("wrong executable path", x => x.Change(p => p with
            { Command = p.Command with { ExecutablePath = @"C:\other.dll" } }), ReviewFailure.CommandMismatch);
        Reject("wrong working directory", x => x.Change(p => p with
            { Command = p.Command with { WorkingDirectory = @"C:\other" } }), ReviewFailure.CommandMismatch);
        Reject("modified command arguments", x => x.Change(p => p with
            { Command = p.Command with { Arguments = p.Command.Arguments.Add("--cleanup") } }), ReviewFailure.CommandMismatch);
        Reject("discovery cannot contain an endpoint", x => x.Change(p => p with
            { Endpoint = Fixture.TestEndpoint }), ReviewFailure.InvalidEndpoint);
        Check("metadata scope requires endpoint", () =>
        {
            var x = new Fixture(ReviewPacketPolicy.Metadata);
            x.Change(p => p with { Endpoint = null });
            RequireFailure(x.Validate(), ReviewFailure.InvalidEndpoint);
        });
        Check("metadata endpoint cannot be wildcard or remote", () =>
        {
            foreach (var pipe in new[] { @"np:\\.\pipe\LOCALDB#*\tsql\query", @"np:\\remote\pipe\LOCALDB#TESTONLY02\tsql\query" })
            {
                var x = new Fixture(ReviewPacketPolicy.Metadata);
                x.Change(p => p with { Endpoint = p.Endpoint! with { NamedPipe = pipe } });
                RequireFailure(x.Validate(), ReviewFailure.InvalidEndpoint);
            }
        });
        Reject("baseline packet identity mismatch", x => x.Baseline = x.Baseline with
            { PacketSha256 = new string('F', 64) }, ReviewFailure.BaselineMismatch);
        Reject("baseline run mismatch", x => x.Baseline = x.Baseline with { RunId = Guid.Empty.ToString("D") },
            ReviewFailure.BaselineMismatch);
        Reject("modified observed manifest", x => x.Observed = x.Observed with
            { ManifestSha256 = new string('C', 64) }, ReviewFailure.ObservationMismatch);
        Reject("modified observed executable", x => x.Observed = x.Observed with
            { Artifacts = x.Observed.Artifacts.SetItem(0, x.Observed.Artifacts[0] with { Sha256 = new string('C', 64) }) },
            ReviewFailure.ObservationMismatch);
        Reject("modified observed dependency length", x => x.Observed = x.Observed with
            { Artifacts = x.Observed.Artifacts.SetItem(1, x.Observed.Artifacts[1] with { ByteLength = 999 }) },
            ReviewFailure.ObservationMismatch);
        Check("endpoint replacement fails consistency", () =>
        {
            var x = new Fixture(ReviewPacketPolicy.Metadata);
            x.Observed = x.Observed with { Endpoint = Fixture.TestEndpoint with { NamedPipe = @"np:\\.\pipe\LOCALDB#REPLACED\tsql\query" } };
            RequireFailure(x.Validate(), ReviewFailure.ObservationMismatch);
        });
        Reject("query identity changed", x => x.Observed = x.Observed with
            { QuerySet = x.Observed.QuerySet with { Sha256 = new string('C', 64) } }, ReviewFailure.ObservationMismatch);
        foreach (var mode in new[] { "discovery-only-review", "endpoint-bound-metadata-review",
            "provision", "migrate", "cleanup", "Server=forbidden", "SELECT 1" })
            Check("CLI has no dispatch for " + mode, () =>
            {
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                Require(ReviewOfflineEntry.Run(["--mode", mode], standard, diagnostic) == 2
                    && standard.ToString().Length == 0 && diagnostic.ToString().Trim() ==
                    "REJECTED: only --mode info or --mode self-test-offline is supported.");
            });
        foreach (var args in new[] { new[] { "--mode" }, new[] { "--mode", "info", "extra" },
            new[] { "--manifest", "UNTRUSTED_SECRET" }, new[] { "--mode", "info", "--mode", "info" } })
            Check("malformed CLI argument count " + args.Length + " first " + args[0], () =>
            {
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                Require(ReviewOfflineEntry.Run(args, standard, diagnostic) == 2
                    && !diagnostic.ToString().Contains("UNTRUSTED_SECRET", StringComparison.Ordinal));
            });
        Check("informational default grants no authority", () =>
        {
            using var standard = new StringWriter();
            using var diagnostic = new StringWriter();
            Require(ReviewOfflineEntry.Run([], standard, diagnostic) == 0
                && standard.ToString().Contains("human approval is external", StringComparison.Ordinal)
                && diagnostic.ToString().Length == 0);
        });
        foreach (var length in new[] { 65_536, 65_537 })
            Check("otherwise-valid JSON size boundary " + length, () =>
            {
                var original = Encoding.UTF8.GetBytes(Fixture.Serialize(new Fixture().Parsed.Packet));
                Require(original.Length < length);
                var padded = original.Concat(Enumerable.Repeat((byte)' ', length - original.Length)).ToArray();
                Require(padded.Length == length);
                if (length == 65_536)
                {
                    var packet = ReviewPacketParser.Parse(padded);
                    Require(packet.Packet.RequestId == "b7654321-cdef-4123-9abc-fedcba987654"
                        && packet.Packet.Target.Database == "PersonalFinanceBillPaymentTests_a123456789ab4cde8fab0123456789ab");
                }
                else
                {
                    try { _ = ReviewPacketParser.Parse(padded); }
                    catch (ReviewPacketException ex) when (ex.Failure == ReviewFailure.MalformedPacket) { return; }
                    throw new InvalidOperationException("Expected oversized valid-JSON rejection.");
                }
            });
        const string knownPacket = """
        {"schemaVersion":1,"requestId":"b7654321-cdef-4123-9abc-fedcba987654","operation":"discovery-only-review","target":{"runId":"a1234567-89ab-4cde-8fab-0123456789ab","database":"PersonalFinanceBillPaymentTests_a123456789ab4cde8fab0123456789ab","dataPath":"D:\\PersonalFinanceBillPaymentSqlTests\\a1234567-89ab-4cde-8fab-0123456789ab\\PersonalFinanceBillPaymentTests_a123456789ab4cde8fab0123456789ab.mdf","logPath":"D:\\PersonalFinanceBillPaymentSqlTests\\a1234567-89ab-4cde-8fab-0123456789ab\\PersonalFinanceBillPaymentTests_a123456789ab4cde8fab0123456789ab_log.ldf","state":"absent"},"manifestSha256":"EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE","artifacts":[{"relativePath":"PersonalFinance.BillPaymentSqlLivePreflight.dll","byteLength":100,"sha256":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"},{"relativePath":"PersonalFinance.BillPaymentSqlLivePreflight.deps.json","byteLength":200,"sha256":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"},{"relativePath":"PersonalFinance.BillPaymentSqlLivePreflight.runtimeconfig.json","byteLength":300,"sha256":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"}],"command":{"executablePath":"C:\\OWLI-Fixtures\\PersonalFinancePlatform\\tests\\PersonalFinance.BillPaymentSqlLivePreflight\\bin\\Debug\\net10.0\\PersonalFinance.BillPaymentSqlLivePreflight.dll","host":{"absolutePath":"C:\\Program Files\\dotnet\\dotnet.exe","byteLength":400,"sha256":"DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"},"workingDirectory":"C:\\OWLI-Fixtures\\PersonalFinancePlatform\\tests\\PersonalFinance.BillPaymentSqlLivePreflight\\bin\\Debug\\net10.0","arguments":["C:\\OWLI-Fixtures\\PersonalFinancePlatform\\tests\\PersonalFinance.BillPaymentSqlLivePreflight\\bin\\Debug\\net10.0\\PersonalFinance.BillPaymentSqlLivePreflight.dll","--operation","discovery-only-review","--request-id","b7654321-cdef-4123-9abc-fedcba987654","--run-id","a1234567-89ab-4cde-8fab-0123456789ab","--manifest-sha256","EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"]},"windowsIdentity":{"computer":"OWLI-FIXTURE","account":"OWLI-FIXTURE\\developer","sid":"S-1-5-21-111111111-222222222-333333333-1001"},"instanceOwner":{"computer":"OWLI-FIXTURE","account":"OWLI-FIXTURE\\developer","sid":"S-1-5-21-111111111-222222222-333333333-1001"},"instanceName":"MSSQLLocalDB","instanceVersion":"17.0.4025.3","endpoint":null,"querySet":{"name":"discovery-no-sql-v1","sha256":"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"},"issuedUtc":"2026-11-06T23:59:00+00:00","notBeforeUtc":"2026-11-06T23:59:00+00:00","expiresUtc":"2026-11-07T00:05:00+00:00","connectTimeoutSeconds":10,"commandTimeoutSeconds":10,"overallTimeoutSeconds":30,"safety":{"writesProhibited":true,"fallbackProhibited":true,"retriesProhibited":true,"automaticProgressionProhibited":true}}
        """;
        const string knownHash = "28F633C0AD78FC4F7701356C1423F3242F53F6F94D1B02313E21D0C69F2EECD6";
        Check("known-vector SHA-256 hashes exact UTF-8 packet bytes", () =>
        {
            var packet = ReviewPacketParser.Parse(Encoding.UTF8.GetBytes(knownPacket));
            Require(packet.PacketSha256 == knownHash
                && packet.Packet.RequestId == "b7654321-cdef-4123-9abc-fedcba987654");
        });
        Check("caller mutation after parse cannot alter packet or hash", () =>
        {
            var callerBytes = Encoding.UTF8.GetBytes(knownPacket);
            var parsed = ReviewPacketParser.Parse(callerBytes);
            var representation = Fixture.Serialize(parsed.Packet);
            Array.Fill(callerBytes, (byte)'x');
            Require(parsed.PacketSha256 == knownHash && Fixture.Serialize(parsed.Packet) == representation
                && parsed.Packet.Target.RunId == "a1234567-89ab-4cde-8fab-0123456789ab");
        });
        foreach (var fixtureOperation in new[] { ReviewPacketPolicy.Discovery, ReviewPacketPolicy.Metadata })
        {
            var source = JsonNode.Parse(Fixture.Serialize(new Fixture(fixtureOperation).Parsed.Packet))!.AsObject();
            var paths = new[] { "target", "command", "command.host", "windowsIdentity", "instanceOwner", "querySet", "safety" }
                .Concat(fixtureOperation == ReviewPacketPolicy.Metadata ? new[] { "endpoint" } : Array.Empty<string>());
            foreach (var path in paths)
                foreach (var property in ObjectAt(source, path).Select(p => p.Key).ToArray())
                {
                    var missing = (JsonObject)source.DeepClone();
                    ObjectAt(missing, path).Remove(property);
                    RejectJson("nested missing " + fixtureOperation + " " + path + "." + property, missing.ToJsonString());
                    var nullField = (JsonObject)source.DeepClone();
                    ObjectAt(nullField, path)[property] = null;
                    RejectJson("nested null " + fixtureOperation + " " + path + "." + property, nullField.ToJsonString());
                    var wrong = (JsonObject)source.DeepClone();
                    var parent = ObjectAt(wrong, path);
                    parent[property] = parent[property] is JsonValue value && value.TryGetValue<bool>(out _)
                        ? JsonValue.Create("true") : JsonValue.Create(true);
                    RejectJson("nested wrong type " + fixtureOperation + " " + path + "." + property, wrong.ToJsonString());
                }
        }
        foreach (var property in new[] { "relativePath", "byteLength", "sha256" })
        {
            var source = JsonNode.Parse(Fixture.Serialize(new Fixture().Parsed.Packet))!.AsObject();
            var missing = (JsonObject)source.DeepClone();
            missing["artifacts"]![0]!.AsObject().Remove(property);
            RejectJson("artifact field missing " + property, missing.ToJsonString());
            var nullField = (JsonObject)source.DeepClone();
            nullField["artifacts"]![0]![property] = null;
            RejectJson("artifact field null " + property, nullField.ToJsonString());
            var wrong = (JsonObject)source.DeepClone();
            wrong["artifacts"]![0]![property] = true;
            RejectJson("artifact field wrong type " + property, wrong.ToJsonString());
        }
        Reject("null artifact entry", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(0, null!) }), ReviewFailure.InvalidArtifacts);
        Reject("missing artifact entry", x => x.Change(p => p with
            { Artifacts = p.Artifacts.RemoveAt(1) }), ReviewFailure.InvalidArtifacts);
        Reject("duplicated artifact entry", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(1, p.Artifacts[0]) }), ReviewFailure.InvalidArtifacts);
        Reject("reordered artifact entries", x => x.Change(p => p with
            { Artifacts = p.Artifacts.SetItem(0, p.Artifacts[1]).SetItem(1, p.Artifacts[0]) }), ReviewFailure.InvalidArtifacts);
        foreach (var hash in new[] { new string('a', 64), new string('A', 63), new string('A', 65), new string('G', 64) }
            .Select((value, index) => (value, index)))
            Reject("artifact hash boundary/casing case " + hash.index, x => x.Change(p => p with
                { Artifacts = p.Artifacts.SetItem(0, p.Artifacts[0] with { Sha256 = hash.value }) }), ReviewFailure.InvalidArtifacts);
        Reject("modified observed host hash", x => x.Observed = x.Observed with
            { Host = x.Observed.Host with { Sha256 = new string('C', 64) } }, ReviewFailure.ObservationMismatch);
        Reject("modified observed host length", x => x.Observed = x.Observed with
            { Host = x.Observed.Host with { ByteLength = x.Observed.Host.ByteLength + 1 } }, ReviewFailure.ObservationMismatch);
        Reject("command argument replaced", x => x.Change(p => p with
            { Command = p.Command with { Arguments = p.Command.Arguments.SetItem(1, "--cleanup") } }), ReviewFailure.CommandMismatch);
        Reject("command arguments reordered", x => x.Change(p => p with
            { Command = p.Command with { Arguments = p.Command.Arguments.SetItem(1, p.Command.Arguments[2])
                .SetItem(2, p.Command.Arguments[1]) } }), ReviewFailure.CommandMismatch);
        foreach (var request in new[] { "", Guid.Empty.ToString("D"), "B7654321-CDEF-4123-9ABC-FEDCBA987654",
            "b7654321cdef41239abcfedcba987654", "{b7654321-cdef-4123-9abc-fedcba987654}" }
            .Select((value, index) => (value, index)))
            Reject("request ID canonicalization case " + request.index, x => x.Change(p => p with
                { RequestId = request.value }), ReviewFailure.InvalidScope);
        foreach (var timeout in new[] { 9, 61 })
            Reject("overall timeout outside boundary " + timeout, x => x.Change(p => p with
                { OverallTimeoutSeconds = timeout }), ReviewFailure.InvalidScope);
        foreach (var timeout in new[] { 9, 11 })
        {
            Reject("connect timeout outside exact value " + timeout, x => x.Change(p => p with
                { ConnectTimeoutSeconds = timeout }), ReviewFailure.InvalidScope);
            Reject("command timeout outside exact value " + timeout, x => x.Change(p => p with
                { CommandTimeoutSeconds = timeout }), ReviewFailure.InvalidScope);
        }
        foreach (var timeout in new[] { 10, 60 })
            Check("overall timeout accepted boundary " + timeout, () =>
            {
                var x = new Fixture();
                x.Change(p => p with { OverallTimeoutSeconds = timeout });
                x.RefreshBaselinePacketHash();
                var result = x.Validate();
                Require(result.Consistent && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized);
            });
        foreach (var windowCase in new[] { "not-before-inclusive", "maximum-window-inclusive", "just-before-expiry" })
            Check("accepted UTC window boundary " + windowCase, () =>
            {
                var x = new Fixture();
                x.Change(p => windowCase switch
                {
                    "not-before-inclusive" => p with { IssuedUtc = Fixture.Now, NotBeforeUtc = Fixture.Now },
                    "maximum-window-inclusive" => p with
                        { IssuedUtc = Fixture.Now.AddMinutes(-30), NotBeforeUtc = Fixture.Now.AddMinutes(-30),
                          ExpiresUtc = Fixture.Now.AddMinutes(30) },
                    _ => p with { ExpiresUtc = Fixture.Now.AddTicks(1) }
                });
                x.RefreshBaselinePacketHash();
                var result = x.Validate();
                Require(result.Consistent && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized);
            });
        Reject("one tick beyond maximum review duration", x => x.Change(p => p with
            { ExpiresUtc = p.IssuedUtc.AddHours(1).AddTicks(1) }), ReviewFailure.InvalidWindow);
        Reject("one tick before not-before", x => x.Change(p => p with
            { NotBeforeUtc = Fixture.Now.AddTicks(1) }), ReviewFailure.InvalidWindow);
        Reject("non-UTC not-before", x => x.Change(p => p with
            { NotBeforeUtc = p.NotBeforeUtc.ToOffset(TimeSpan.FromHours(1)) }), ReviewFailure.InvalidWindow);
        Reject("non-UTC expiry", x => x.Change(p => p with
            { ExpiresUtc = p.ExpiresUtc.ToOffset(TimeSpan.FromHours(1)) }), ReviewFailure.InvalidWindow);
        Check("non-UTC observation time", () =>
        {
            var x = new Fixture();
            RequireFailure(ReviewPacketPolicy.Validate(x.Parsed, x.Baseline, x.Observed,
                Fixture.Now.ToOffset(TimeSpan.FromHours(1))), ReviewFailure.InvalidWindow);
        });
        Check("endpoint internal identity mismatch rejects fully", () =>
        {
            var x = new Fixture(ReviewPacketPolicy.Metadata);
            x.Change(p => p with { Endpoint = p.Endpoint! with { InternalServer = "UNTRUSTED_SECRET" } });
            RequireFailure(x.Validate(), ReviewFailure.InvalidEndpoint);
        });
        Check("explicit info mode grants no authority", () =>
        {
            using var standard = new StringWriter();
            using var diagnostic = new StringWriter();
            Require(ReviewOfflineEntry.Run(["--mode", "info"], standard, diagnostic) == 0
                && diagnostic.ToString().Length == 0
                && standard.ToString().Contains("human approval is external", StringComparison.Ordinal)
                && standard.ToString().Contains("No live adapters or dispatch exist.", StringComparison.Ordinal));
        });
        ObservationTests.Register(Check);
        BindingTests.Register(Check);
        output.WriteLine($"Offline review-packet checks: {passed} passed, {failed} failed, 0 skipped. No approval authenticated; no execution authorized.");
        return failed == 0 ? 0 : 1;
    }

    private static JsonObject ObjectAt(JsonObject root, string path)
    {
        var node = root;
        foreach (var part in path.Split('.')) node = node[part]!.AsObject();
        return node;
    }

    private sealed class Fixture
    {
        internal static readonly DateTimeOffset Now = new(2026, 11, 7, 0, 0, 0, TimeSpan.Zero);
        internal static readonly EndpointDeclaration TestEndpoint =
            new(@"np:\\.\pipe\LOCALDB#TESTONLY02\tsql\query", @"OWLI-FIXTURE\LOCALDB#TESTONLY02");
        internal ParsedReviewPacket Parsed { get; private set; }
        internal ReviewedConsistencyBaseline Baseline { get; set; }
        internal ObservedConsistency Observed { get; set; }

        internal Fixture(string operation = ReviewPacketPolicy.Discovery)
        {
            const string run = "a1234567-89ab-4cde-8fab-0123456789ab";
            const string request = "b7654321-cdef-4123-9abc-fedcba987654";
            var database = "PersonalFinanceBillPaymentTests_" + Guid.Parse(run).ToString("N");
            var root = @"D:\PersonalFinanceBillPaymentSqlTests\" + run;
            var target = new TargetDeclaration(run, database, root + "\\" + database + ".mdf",
                root + "\\" + database + "_log.ldf", "absent");
            var artifacts = ImmutableArray.Create(
                new ArtifactIdentity(ReviewPacketPolicy.FileName, 100, new string('A', 64)),
                new ArtifactIdentity("PersonalFinance.BillPaymentSqlLivePreflight.deps.json", 200, new string('B', 64)),
                new ArtifactIdentity("PersonalFinance.BillPaymentSqlLivePreflight.runtimeconfig.json", 300, new string('C', 64)));
            var host = new HostIdentity(ReviewPacketPolicy.HostPath, 400, new string('D', 64));
            var manifest = new string('E', 64);
            var command = new CommandDeclaration(ReviewPacketPolicy.OutputDirectory + "\\" + ReviewPacketPolicy.FileName,
                host, ReviewPacketPolicy.OutputDirectory, ImmutableArray.Create(
                    ReviewPacketPolicy.OutputDirectory + "\\" + ReviewPacketPolicy.FileName,
                    "--operation", operation, "--request-id", request, "--run-id", run, "--manifest-sha256", manifest));
            var identity = new IdentityDeclaration(ReviewPacketPolicy.HistoricalComputer,
                ReviewPacketPolicy.HistoricalAccount, ReviewPacketPolicy.HistoricalSid);
            var endpoint = operation == ReviewPacketPolicy.Discovery ? null : TestEndpoint;
            var query = new QuerySetIdentity(operation == ReviewPacketPolicy.Discovery
                ? "discovery-no-sql-v1" : "absent-target-metadata-v1", new string('F', 64));
            var packet = new ProposedReviewPacket(1, request, operation, target, manifest, artifacts, command,
                identity, identity, "MSSQLLocalDB", "17.0.4025.3", endpoint, query,
                Now.AddMinutes(-1), Now.AddMinutes(-1), Now.AddMinutes(5), 10, 10, 30,
                new SafetyDeclaration(true, true, true, true));
            Parsed = ReviewPacketParser.Parse(Encoding.UTF8.GetBytes(Serialize(packet)));
            Baseline = new(request, run, Parsed.PacketSha256, manifest, artifacts, host, endpoint, query);
            Observed = new(manifest, artifacts, host, endpoint, query);
        }

        internal void Change(Func<ProposedReviewPacket, ProposedReviewPacket> mutation) =>
            Parsed = ReviewPacketParser.Parse(Encoding.UTF8.GetBytes(Serialize(mutation(Parsed.Packet))));

        internal void RefreshBaselinePacketHash() =>
            Baseline = Baseline with { PacketSha256 = Parsed.PacketSha256 };

        internal ConsistencyResult Validate() => ReviewPacketPolicy.Validate(Parsed, Baseline, Observed, Now);

        internal static string Serialize(ProposedReviewPacket packet) =>
            JsonSerializer.Serialize(packet, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
    }
}
