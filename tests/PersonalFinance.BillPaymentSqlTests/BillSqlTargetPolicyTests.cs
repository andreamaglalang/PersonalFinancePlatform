using System.Text;
using System.Text.Json.Nodes;

namespace PersonalFinance.BillPaymentSqlTests;

public static class BillSqlTargetPolicyTests
{
    public static int Run(TextWriter output, TextWriter error)
    {
        var passed = 0;
        var failed = 0;
        void Check(string name, Action test)
        {
            try { test(); passed++; output.WriteLine("PASS " + name); }
            catch (Exception ex)
            {
                failed++;
                error.WriteLine("FAIL " + name + ": " + ex.GetType().Name);
            }
        }
        void Require(bool condition)
        {
            if (!condition) throw new InvalidOperationException("Offline test assertion failed.");
        }
        void Rejected(Action action)
        {
            try { action(); }
            catch (BillSqlPolicyException) { return; }
            throw new InvalidOperationException("Expected policy rejection.");
        }
        void RejectJson(string text) => Rejected(() => BillSqlTargetPolicy.ParseAndValidate(Encoding.UTF8.GetBytes(text)));
        void Mutate(string name, Action<JsonObject> mutate) => Check(name, () =>
        {
            var node = Valid();
            mutate(node);
            RejectJson(node.ToJsonString());
        });

        foreach (var run in new[] { "a1234567-89ab-4cde-8fab-0123456789ab", "b7654321-cdef-4123-9abc-fedcba987654" })
            Check("valid run-bound declaration " + run, () =>
            {
                var expected = Guid.Parse(run);
                var node = Valid(run);
                var result = BillSqlTargetPolicy.ParseAndValidate(Encoding.UTF8.GetBytes(node.ToJsonString()));
                Require(result.DatabaseName == "PersonalFinanceBillPaymentTests_" + expected.ToString("N"));
                Require(result.DataPath == @"D:\PersonalFinanceBillPaymentSqlTests\" + run + "\\" + result.DatabaseName + ".mdf");
                Require(result.LogPath == @"D:\PersonalFinanceBillPaymentSqlTests\" + run + "\\" + result.DatabaseName + "_log.ldf");
            });

        foreach (var item in new (string Name, string Json)[]
        {
            ("empty input", ""), ("incomplete object", "{"), ("null root", "null"),
            ("array root", "[]"), ("string root", "\"text\""), ("number root", "1"),
            ("boolean root", "true"), ("trailing comma", "{\"schemaVersion\":1,}"),
            ("JSON comment", "{/*comment*/\"schemaVersion\":1}"),
            ("duplicate root in incomplete manifest", "{\"schemaVersion\":1,\"schemaVersion\":1}")
        })
            Check("malformed/root JSON: " + item.Name, () => RejectJson(item.Json));
        Check("oversized manifest", () => Rejected(() =>
            BillSqlTargetPolicy.ParseAndValidate(new byte[BillSqlTargetPolicy.MaximumManifestBytes + 1])));
        Check("invalid UTF-8", () => Rejected(() => BillSqlTargetPolicy.ParseAndValidate(new byte[] { 0xff, 0xfe, 0xff })));
        Check("over-depth array: general rejection only", () => RejectJson(new string('[', 12) + "0" + new string(']', 12)));
        Check("nested duplicate JSON property", () =>
        {
            var text = Valid().ToJsonString().Replace("\"encrypt\":true", "\"encrypt\":true,\"encrypt\":true", StringComparison.Ordinal);
            RejectJson(text);
        });

        Check("over-depth object-root manifest: general rejection only, not isolated depth enforcement", () =>
        {
            var node = Valid();
            JsonNode nested = JsonValue.Create(0)!;
            for (var level = 0; level < BillSqlTargetPolicy.MaximumDepth + 1; level++)
                nested = new JsonObject { ["nested"] = nested };
            node["unexpectedDepthProbe"] = nested;
            RejectJson(node.ToJsonString());
        });
        Check("duplicate root property in otherwise valid manifest", () =>
        {
            var text = Valid().ToJsonString();
            _ = BillSqlTargetPolicy.ParseAndValidate(Encoding.UTF8.GetBytes(text));
            RejectJson(text.Insert(1, "\"schemaVersion\":1,"));
        });
        Check("valid manifest at exact 65536-byte parser boundary", () =>
        {
            var original = Encoding.UTF8.GetBytes(Valid().ToJsonString());
            Require(original.Length < BillSqlTargetPolicy.MaximumManifestBytes);
            var padded = original.Concat(Enumerable.Repeat((byte)' ',
                BillSqlTargetPolicy.MaximumManifestBytes - original.Length)).ToArray();
            Require(padded.Length == 65_536);
            var manifest = BillSqlTargetPolicy.ParseAndValidate(padded);
            Require(manifest.RunId == "a1234567-89ab-4cde-8fab-0123456789ab"
                && manifest.DatabaseName == "PersonalFinanceBillPaymentTests_a123456789ab4cde8fab0123456789ab");
        });
        Check("Validate directly rejects a null manifest", () =>
            Rejected(() => BillSqlTargetPolicy.Validate(null!)));

        var template = Valid();
        var locations = new List<(string? Parent, string Name)>();
        foreach (var field in template)
        {
            locations.Add((null, field.Key));
            if (field.Value is JsonObject nested)
                foreach (var child in nested) locations.Add((field.Key, child.Key));
        }
        foreach (var field in locations)
        {
            var label = (field.Parent is null ? "" : field.Parent + ".") + field.Name;
            Mutate("missing " + label, node => ObjectAt(node, field.Parent).Remove(field.Name));
            Mutate("null " + label, node => ObjectAt(node, field.Parent)[field.Name] = null);
            Mutate("wrong type " + label, node =>
            {
                var target = ObjectAt(node, field.Parent);
                target[field.Name] = target[field.Name] is JsonValue value && value.TryGetValue<bool>(out _)
                    ? JsonValue.Create("false") : JsonValue.Create(false);
            });
        }
        foreach (var parent in new string?[] { null, "owner", "connectionPolicy" })
            Mutate("unknown property " + (parent ?? "root"), node => ObjectAt(node, parent)["unexpected"] = "untrusted");
        Mutate("case-sensitive property names", node =>
        {
            node["SchemaVersion"] = node["schemaVersion"]!.DeepClone();
            node.Remove("schemaVersion");
        });
        Mutate("unsupported schema version", node => node["schemaVersion"] = 2);
        foreach (var field in new[] { "computerName", "accountName", "sid" })
            Mutate("incorrect owner " + field, node => ((JsonObject)node["owner"]!)[field] = "incorrect");
        Mutate("wrong instance", node => node["sqlServerInstance"] = @"(localdb)\OtherInstance");
        Mutate("case-variant instance", node => node["sqlServerInstance"] = @"(LOCALDB)\MSSQLLocalDB");
        Mutate("wrong version", node => node["expectedSqlServerVersion"] = "17.0.0.0");

        foreach (var (run, caseIndex) in new[] { "", Guid.Empty.ToString("D"), "A1234567-89AB-4CDE-8FAB-0123456789AB",
            "a123456789ab4cde8fab0123456789ab", "{a1234567-89ab-4cde-8fab-0123456789ab}", "not-a-guid" }.Select((value, index) => (value, index)))
            Mutate("noncanonical GUID case " + caseIndex, node => node["runId"] = run);
        foreach (var name in new[] { "PersonalFinanceDb", "personalfinancedb", "master", "model", "msdb", "tempdb",
            "PersonalFinanceSavingsTests_a123456789ab4cde8fab0123456789ab", "PersonalFinanceBillPaymentTests_",
            "PersonalFinanceBillPaymentTests_b7654321cdef41239abcfedcba987654",
            "personalfinancebillpaymenttests_a123456789ab4cde8fab0123456789ab" })
            Mutate("prohibited database " + name, node => node["databaseName"] = name);

        foreach (var field in new[] { "dataPath", "logPath" })
        {
            foreach (var path in new[] { @"..\target.mdf", @"\\server\share\target.mdf", @"\\?\D:\target.mdf",
                @"\\.\D:\target.mdf", @"D:\root\..\target.mdf", @"D:\root\*.mdf", @"D:\root\?.mdf",
                @"D:\target.mdf:stream", @"D:/target.mdf", @"C:\target.mdf", @"D:\target.ldf",
                @"D:\PersonalFinanceBillPaymentSqlTests\b7654321-cdef-4123-9abc-fedcba987654\target.mdf" })
                Mutate("dangerous/mismatched " + field + " " + path, node => node[field] = path);
            Mutate("wrong extension " + field, node => node[field] = node[field]!.GetValue<string>() + ".bak");
            Mutate("path case variant " + field, node => node[field] = node[field]!.GetValue<string>().ToLowerInvariant());
        }
        foreach (var field in new[] { "pooling", "enlist", "multipleActiveResultSets", "allowAttachFile",
            "allowFallback", "allowFailover", "allowImplicitLocalDbStartup" })
            Mutate("forbidden flag " + field, node => ((JsonObject)node["connectionPolicy"]!)[field] = true);
        foreach (var field in new[] { "encrypt", "trustServerCertificate" })
            Mutate("required flag " + field, node => ((JsonObject)node["connectionPolicy"]!)[field] = false);
        foreach (var field in new[] { "connectTimeoutSeconds", "commandTimeoutSeconds" })
            foreach (var value in new[] { -1, 0, 9, 11 })
                Mutate("wrong timeout " + field + " " + value, node => ((JsonObject)node["connectionPolicy"]!)[field] = value);
        Mutate("retries prohibited", node => ((JsonObject)node["connectionPolicy"]!)["connectRetryCount"] = 1);
        Mutate("SQL authentication prohibited", node => ((JsonObject)node["connectionPolicy"]!)["authentication"] = "SqlPassword");
        Mutate("wrong transport", node => ((JsonObject)node["connectionPolicy"]!)["transport"] = "TCP");
        Mutate("verified target state prohibited", node => node["databaseState"] = "verified-target");
        Mutate("claimed pipe is not authority", node => node["namedPipe"] = @"np:\\.\pipe\unverified");
        Mutate("claimed binding is not authority", node => node["databaseGuid"] = Guid.NewGuid().ToString("D"));
        Mutate("connection string prohibited", node => node["connectionString"] = "untrusted-secret");
        Mutate("empty phases", node => node["allowedPhases"] = new JsonArray());
        Mutate("extra phase", node => ((JsonArray)node["allowedPhases"]!).Add("provision-database"));
        Mutate("live phase", node => node["allowedPhases"] = new JsonArray("cleanup-database"));
        Mutate("case-variant phase", node => node["allowedPhases"] = new JsonArray("VALIDATE-OFFLINE"));
        Mutate("null phase item", node => node["allowedPhases"] = new JsonArray((JsonNode?)null));
        Mutate("missing migration", node => ((JsonArray)node["migrationIds"]!).RemoveAt(3));
        Mutate("extra migration", node => ((JsonArray)node["migrationIds"]!).Add("Unexpected"));
        Mutate("duplicate migration", node => ((JsonArray)node["migrationIds"]!)[3] = node["migrationIds"]![0]!.DeepClone());
        Mutate("reordered migrations", node =>
        {
            var array = (JsonArray)node["migrationIds"]!;
            var first = array[0]!.DeepClone();
            array[0] = array[1]!.DeepClone();
            array[1] = first;
        });
        Mutate("altered migration", node => ((JsonArray)node["migrationIds"]!)[3] = "20261107054048_Unexpected");
        Mutate("null migration item", node => ((JsonArray)node["migrationIds"]!)[0] = null);

        foreach (var arguments in new[]
        {
            Array.Empty<string>(), new[] { "--mode" }, new[] { "--mode", "unknown" },
            new[] { "--mode", "self-test-offline", "extra" },
            new[] { "--mode", "self-test-offline", "--mode", "self-test-offline" },
            new[] { "--mode", "validate-offline" },
            new[] { "--manifest", @"D:\manifest.json", "--mode", "validate-offline" },
            new[] { "--mode", "validate-offline", "--manifest", "" },
            new[] { "--mode", "validate-offline", "--manifest", "--mode" },
            new[] { "--mode", "validate-offline", "--manifest", @"D:\manifest.json", "extra" },
            new[] { "--mode", "validate-offline", "--manifest", @"D:\manifest.json", "--manifest", @"D:\other.json" }
        }.Concat(new[] { "live-read-only-preflight", "provision-database", "apply-bounded-migrations",
            "run-deterministic-tests", "run-coordinated-concurrency-tests", "cleanup-database" }
            .Select(mode => new[] { "--mode", mode, "--manifest", @"D:\manifest.json" })))
            Check("reject malformed/live command " + string.Join(" ", arguments), () =>
            {
                var calls = 0;
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                var status = BillSqlOfflineEntry.Run(arguments, standard, diagnostic, _ => { calls++; return []; });
                Require(status == 2 && calls == 0 && standard.ToString().Length == 0);
            });

        Check("valid command exits zero with safe offline diagnostics", () =>
        {
            using var standard = new StringWriter();
            using var diagnostic = new StringWriter();
            var bytes = Encoding.UTF8.GetBytes(Valid().ToJsonString());
            var calls = 0;
            Require(BillSqlOfflineEntry.Run(["--mode", "validate-offline", "--manifest", @"D:\manifest.json"],
                standard, diagnostic, _ => { calls++; return bytes; }) == 0);
            Require(calls == 1 && diagnostic.ToString().Length == 0
                && standard.ToString().Contains("offline declarations only", StringComparison.Ordinal)
                && !standard.ToString().Contains("OWLI-FIXTURE", StringComparison.Ordinal));
        });
        foreach (var category in new[] { "policy", "read", "unexpected" })
            Check("safe diagnostics and exit classification " + category, () =>
            {
                const string secret = "UNTRUSTED_SENTINEL_MUST_NOT_APPEAR";
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                var status = BillSqlOfflineEntry.Run(["--mode", "validate-offline", "--manifest", secret],
                    standard, diagnostic, _ => category switch
                    {
                        "policy" => Encoding.UTF8.GetBytes("{\"connectionString\":\"" + secret + "\"}"),
                        "read" => throw new IOException(secret),
                        _ => throw new InvalidOperationException(secret)
                    });
                Require(status == (category == "unexpected" ? 1 : 2));
                Require(!standard.ToString().Contains(secret, StringComparison.Ordinal)
                    && !diagnostic.ToString().Contains(secret, StringComparison.Ordinal));
            });
        Check("negative helper never accepts unrelated exception", () =>
        {
            var expected = new InvalidOperationException("synthetic");
            try { Rejected(() => throw expected); }
            catch (InvalidOperationException actual) when (ReferenceEquals(actual, expected)) { return; }
            throw new InvalidOperationException("Unrelated exception was accepted.");
        });
        foreach (var path in new[] { "relative.json", @"\\host\share\manifest.json", @"\\?\D:\manifest.json",
            @"D:\folder\..\manifest.json", @"D:\folder\.\manifest.json", @"D:\manifest.json:stream",
            @"D:\*.json", @"D:\?.json", "D:/manifest.json", @"D:\folder\\manifest.json", @"D:\trailing.\manifest.json" })
            Check("manifest path syntax rejects " + path, () => Rejected(() => BillSqlOfflineEntry.ValidateManifestPathSyntax(path)));
        Check("ordinary manifest path syntax", () => BillSqlOfflineEntry.ValidateManifestPathSyntax(@"D:\review\manifest.json"));
        foreach (var length in new[] { 0, BillSqlTargetPolicy.MaximumManifestBytes + 1 })
            Check("bounded reader rejects length " + length, () =>
            {
                using var stream = new MemoryStream(new byte[length]);
                Rejected(() => BillSqlOfflineEntry.ReadBounded(stream));
            });
        Check("bounded reader accepts maximum size without writing", () =>
        {
            using var stream = new MemoryStream(new byte[BillSqlTargetPolicy.MaximumManifestBytes]);
            Require(BillSqlOfflineEntry.ReadBounded(stream).Length == BillSqlTargetPolicy.MaximumManifestBytes);
        });
        var shortReadPayload = Encoding.UTF8.GetBytes(Valid().ToJsonString());
        foreach (var item in new[]
        {
            (Name: "one-byte chunks", Bytes: shortReadPayload, Chunk: 1),
            (Name: "seven-byte chunks", Bytes: shortReadPayload, Chunk: 7),
            (Name: "127-byte chunks", Bytes: shortReadPayload, Chunk: 127),
            (Name: "maximum-size input", Bytes: new byte[BillSqlTargetPolicy.MaximumManifestBytes], Chunk: 31)
        })
            Check("bounded reader handles short reads: " + item.Name, () =>
            {
                using var stream = new ShortReadStream(item.Bytes, item.Chunk);
                var actual = BillSqlOfflineEntry.ReadBounded(stream);
                Require(actual.SequenceEqual(item.Bytes) && stream.ReadCalls > 1);
            });
        foreach (var length in new[] { 0, BillSqlTargetPolicy.MaximumManifestBytes + 1 })
            Check("short-read stream rejects invalid length " + length, () =>
            {
                using var stream = new ShortReadStream(new byte[length], 7);
                Rejected(() => BillSqlOfflineEntry.ReadBounded(stream));
            });
        foreach (var item in new (string Name, Func<Exception> Create)[]
        {
            ("IOException", () => new IOException("UNTRUSTED_FILE_DETAIL")),
            ("UnauthorizedAccessException", () => new UnauthorizedAccessException("UNTRUSTED_FILE_DETAIL")),
            ("ArgumentException", () => new ArgumentException("UNTRUSTED_FILE_DETAIL")),
            ("NotSupportedException", () => new NotSupportedException("UNTRUSTED_FILE_DETAIL")),
            ("SecurityException", () => new System.Security.SecurityException("UNTRUSTED_FILE_DETAIL"))
        })
            Check("injected reader exception maps safely to exit 2: " + item.Name, () =>
            {
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                var calls = 0;
                var status = BillSqlOfflineEntry.Run(
                    ["--mode", "validate-offline", "--manifest", @"D:\manifest.json"], standard, diagnostic,
                    _ => { calls++; throw item.Create(); });
                Require(status == 2 && calls == 1 && standard.ToString().Length == 0
                    && diagnostic.ToString().Trim() == "REJECTED: manifest could not be read safely."
                    && !diagnostic.ToString().Contains("UNTRUSTED_FILE_DETAIL", StringComparison.Ordinal));
            });
        output.WriteLine($"Offline policy tests: {passed} passed, {failed} failed, 0 skipped. No files or database connections used.");
        return failed == 0 ? 0 : 1;
    }

    private sealed class ShortReadStream(byte[] bytes, int maximumChunk) : MemoryStream(bytes, writable: false)
    {
        public int ReadCalls { get; private set; }

        public override int Read(byte[] buffer, int offset, int count)
        {
            ReadCalls++;
            return base.Read(buffer, offset, Math.Min(count, maximumChunk));
        }
    }

    private static JsonObject ObjectAt(JsonObject node, string? parent) =>
        parent is null ? node : (JsonObject)node[parent]!;

    private static JsonObject Valid(string run = "a1234567-89ab-4cde-8fab-0123456789ab")
    {
        var database = "PersonalFinanceBillPaymentTests_" + Guid.Parse(run).ToString("N");
        var directory = @"D:\PersonalFinanceBillPaymentSqlTests\" + run;
        return new JsonObject
        {
            ["schemaVersion"] = 1,
            ["runId"] = run,
            ["sqlServerInstance"] = @"(localdb)\MSSQLLocalDB",
            ["owner"] = new JsonObject
            {
                ["computerName"] = "OWLI-FIXTURE",
                ["accountName"] = @"OWLI-FIXTURE\developer",
                ["sid"] = "S-1-5-21-111111111-222222222-333333333-1001"
            },
            ["expectedSqlServerVersion"] = "17.0.4025.3",
            ["databaseName"] = database,
            ["dataPath"] = directory + "\\" + database + ".mdf",
            ["logPath"] = directory + "\\" + database + "_log.ldf",
            ["databaseState"] = "unverified-target",
            ["connectionPolicy"] = new JsonObject
            {
                ["authentication"] = "WindowsIntegrated",
                ["transport"] = "VerifiedExistingNamedPipe",
                ["encrypt"] = true,
                ["trustServerCertificate"] = true,
                ["pooling"] = false,
                ["enlist"] = false,
                ["multipleActiveResultSets"] = false,
                ["allowAttachFile"] = false,
                ["allowFallback"] = false,
                ["allowFailover"] = false,
                ["allowImplicitLocalDbStartup"] = false,
                ["connectTimeoutSeconds"] = 10,
                ["commandTimeoutSeconds"] = 10,
                ["connectRetryCount"] = 0
            },
            ["allowedPhases"] = new JsonArray("validate-offline"),
            ["migrationIds"] = new JsonArray(
                "20261031023901_InitialFinanceSchema",
                "20261031115500_AddCategoryNameUniqueness",
                "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions",
                "20261107054048_AddBillPaymentReceiptsAndGuards")
        };
    }
}
