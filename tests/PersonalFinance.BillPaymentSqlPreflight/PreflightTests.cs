using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlPreflight;

internal static class PreflightTests
{
    internal static async Task<int> RunAsync(TextWriter output, TextWriter error)
    {
        var passed = 0;
        var failed = 0;
        async Task Check(string name, Func<Task> action)
        {
            try { await action(); passed++; output.WriteLine("PASS " + name); }
            catch (Exception ex) { failed++; error.WriteLine("FAIL " + name + ": " + ex.GetType().Name); }
        }
        void Require(bool value)
        {
            if (!value) throw new InvalidOperationException("Offline assertion failed.");
        }
        Task Reject(string name, Action<OfflineRig> arrange, PreflightFailure expected, params string[] calls) =>
            Check(name, async () =>
            {
                var rig = new OfflineRig();
                arrange(rig);
                var result = await rig.RunAsync();
                Require(!result.Succeeded && result.OfflineSimulationOnly && result.Failure == expected
                    && rig.Calls.SequenceEqual(calls, StringComparer.Ordinal)
                    && result.Diagnostic.Length <= 128
                    && !result.Diagnostic.Contains("UNTRUSTED_INTERNAL_SECRET", StringComparison.Ordinal));
            });
        void Scope(OfflineRig rig, Func<ApprovedPreflightScope, ApprovedPreflightScope> change) =>
            rig.Request = rig.Request with { Scope = change(rig.Request.Scope) };

        await Check("valid absent-target simulation has exactly five observation calls and no follow-on dispatch", async () =>
        {
            var rig = new OfflineRig();
            var result = await rig.RunAsync();
            Require(result.Succeeded && result.Failure == PreflightFailure.None && result.OfflineSimulationOnly
                && result.Diagnostic == "Offline simulated absent-target evidence matched."
                && rig.Calls.SequenceEqual(new[] { "windows", "instance", "filesystem", "metadata", "instance" }));
        });
        await Reject("missing simulated approval blocks every observer", r => r.Request = r.Request with { Approval = null },
            PreflightFailure.ApprovalRejected);
        await Reject("invalid approval ID", r => r.Request = r.Request with
            { Approval = r.Request.Approval! with { ApprovalId = Guid.Empty.ToString("D") } }, PreflightFailure.ApprovalRejected);
        await Reject("a claimed real approval does not grant Stage 1 authority", r => r.Request = r.Request with
            { Approval = r.Request.Approval! with { ProvenanceKind = "human-approved" } }, PreflightFailure.ApprovalRejected);
        await Reject("expired scope", r => Scope(r, s => s with { ExpiresUtc = OfflineClock.Fixed }), PreflightFailure.PolicyRejected);
        await Reject("not yet valid scope", r => Scope(r, s => s with
            { NotBeforeUtc = OfflineClock.Fixed.AddMinutes(1) }), PreflightFailure.PolicyRejected);
        await Reject("oversized approval window", r => Scope(r, s => s with
            { ExpiresUtc = OfflineClock.Fixed.AddHours(2) }), PreflightFailure.PolicyRejected);
        await Reject("mismatched approval scope", r => r.Request = r.Request with
            { Approval = r.Request.Approval! with { Scope = r.Request.Scope with { TimeoutMilliseconds = 500 } } },
            PreflightFailure.ApprovalRejected);
        await Reject("changed observed manifest hash", r => r.Request = r.Request with
            { ObservedArtifacts = r.Request.ObservedArtifacts with { ManifestSha256 = new string('C', 64) } },
            PreflightFailure.ArtifactMismatch);
        await Reject("changed observed executable hash", r => r.Request = r.Request with
            { ObservedArtifacts = r.Request.ObservedArtifacts with { ExecutableSha256 = new string('C', 64) } },
            PreflightFailure.ArtifactMismatch);
        await Reject("malformed artifact hash", r => Scope(r, s => s with
            { Artifacts = s.Artifacts with { ManifestSha256 = "invalid" } }), PreflightFailure.PolicyRejected);
        await Reject("unexpected executable name", r => Scope(r, s => s with
            { Artifacts = s.Artifacts with { ExecutableName = "Other.dll" } }), PreflightFailure.PolicyRejected);
        foreach (var run in new[] { "", Guid.Empty.ToString("D"), "A1234567-89AB-4CDE-8FAB-0123456789AB", "invalid" }
            .Select((value, index) => (value, index)))
            await Reject("invalid run case " + run.index, r => Scope(r, s => s with
                { Target = s.Target with { RunId = run.value } }), PreflightFailure.PolicyRejected);
        foreach (var name in new[] { "PersonalFinanceDb", "personalfinancedb", "master", "OtherTests" })
            await Reject("forbidden database " + name, r => Scope(r, s => s with
                { Target = s.Target with { Name = name } }), PreflightFailure.PolicyRejected);
        foreach (var path in new[] { @"..\target.mdf", @"\\host\share\target.mdf", @"D:\target.mdf:stream", @"D:\*.mdf" })
            await Reject("invalid data path " + path, r => Scope(r, s => s with
                { Target = s.Target with { DataPath = path } }), PreflightFailure.PolicyRejected);
        await Reject("invalid log path", r => Scope(r, s => s with
            { Target = s.Target with { LogPath = @"C:\wrong.ldf" } }), PreflightFailure.PolicyRejected);
        await Reject("existing-target policy prohibited", r => Scope(r, s => s with
            { Target = s.Target with { State = "existing-target" } }), PreflightFailure.PolicyRejected);
        await Reject("unsupported request mode", r => r.Request = r.Request with { Mode = "provision" }, PreflightFailure.PolicyRejected);
        await Reject("write phase prohibited", r => Scope(r, s => s with { Phase = "cleanup" }), PreflightFailure.PolicyRejected);
        await Reject("wrong expected instance", r => Scope(r, s => s with { Instance = "Other" }), PreflightFailure.PolicyRejected);
        await Reject("wrong expected owner", r => Scope(r, s => s with
            { InstanceOwner = s.InstanceOwner with { Sid = "wrong" } }), PreflightFailure.PolicyRejected);
        await Reject("invalid expected pipe", r => Scope(r, s => s with
            { Endpoint = s.Endpoint with { Pipe = @"np:\\remote\pipe\invalid" } }), PreflightFailure.PolicyRejected);
        await Reject("inconsistent expected pipe identity", r => Scope(r, s => s with
            { Endpoint = s.Endpoint with { InternalServer = "wrong" } }), PreflightFailure.PolicyRejected);
        await Reject("missing migrations", r => Scope(r, s => s with { MigrationIds = default }), PreflightFailure.PolicyRejected);
        await Reject("altered migration sequence", r => Scope(r, s => s with
            { MigrationIds = s.MigrationIds.SetItem(3, "unexpected") }), PreflightFailure.PolicyRejected);
        await Reject("invalid timeout", r => Scope(r, s => s with { TimeoutMilliseconds = 0 }), PreflightFailure.PolicyRejected);
        await Reject("wrong Windows SID", r => r.Windows = r.Windows with { Sid = "wrong" },
            PreflightFailure.WindowsMismatch, "windows");
        await Reject("missing Windows observation", r => r.Windows = null!,
            PreflightFailure.WindowsMismatch, "windows");
        await Reject("missing LocalDB observation", r => r.Instance = null!,
            PreflightFailure.InstanceMismatch, "windows", "instance");
        await Reject("missing filesystem observation", r => r.Files = null!,
            PreflightFailure.FilesystemMismatch, "windows", "instance", "filesystem");
        await Reject("missing SQL observation", r => r.Sql = null!,
            PreflightFailure.SqlIdentityMismatch, "windows", "instance", "filesystem", "metadata");
        await Reject("missing target absence evidence", r => r.Sql = r.Sql with { Target = null! },
            PreflightFailure.ContradictoryEvidence, "windows", "instance", "filesystem", "metadata");
        await Reject("wrong Windows account", r => r.Windows = r.Windows with { Account = "wrong" },
            PreflightFailure.WindowsMismatch, "windows");
        await Reject("impersonation", r => r.Windows = r.Windows with { Impersonated = true },
            PreflightFailure.WindowsMismatch, "windows");
        foreach (var kind in new[] { "owner", "sid", "version", "stopped", "shared" })
            await Reject("LocalDB mismatch " + kind, r => r.Instance = kind switch
            {
                "owner" => r.Instance with { OwnerAccount = "wrong" },
                "sid" => r.Instance with { OwnerSid = "wrong" },
                "version" => r.Instance with { Version = "wrong" },
                "stopped" => r.Instance with { Running = false },
                _ => r.Instance with { Shared = true }
            }, PreflightFailure.InstanceMismatch, "windows", "instance");
        await Reject("unexpected observed endpoint", r => r.Instance = r.Instance with
            { Endpoint = r.Instance.Endpoint with { Pipe = @"np:\\remote\pipe\wrong" } },
            PreflightFailure.EndpointMismatch, "windows", "instance");
        foreach (var kind in new[] { "missing", "reparse", "alias", "file-exists", "path", "physical-identity" })
            await Reject("filesystem mismatch " + kind, r => r.Files = kind switch
            {
                "missing" => r.Files with { Complete = false },
                "reparse" => r.Files with { ReparseFound = true },
                "alias" => r.Files with { AliasFound = true },
                "file-exists" => r.Files with { DataFileExists = true },
                "path" => r.Files with { DataPath = "wrong" },
                _ => r.Files with { AncestorPhysicalIdentityEstablished = false }
            }, PreflightFailure.FilesystemMismatch, "windows", "instance", "filesystem");
        await Reject("existing target is never adopted", r => r.Sql = r.Sql with
            { Target = r.Sql.Target with { Exists = true } }, PreflightFailure.TargetExists,
            "windows", "instance", "filesystem", "metadata");
        await Reject("insufficient server metadata visibility", r => r.Sql = r.Sql with { CanInspectTargetMetadata = false },
            PreflightFailure.VisibilityInsufficient, "windows", "instance", "filesystem", "metadata");
        await Reject("incomplete absence evidence", r => r.Sql = r.Sql with
            { Target = r.Sql.Target with { CompleteVisibility = false } }, PreflightFailure.VisibilityInsufficient,
            "windows", "instance", "filesystem", "metadata");
        await Reject("metadata refers to another target", r => r.Sql = r.Sql with
            { Target = r.Sql.Target with { DatabaseName = "PersonalFinanceDb" } }, PreflightFailure.ContradictoryEvidence,
            "windows", "instance", "filesystem", "metadata");
        await Reject("history contradicts absent target", r => r.Sql = r.Sql with
            { TargetMigrationIds = ImmutableArray.Create(PreflightPolicy.Migrations[0]) }, PreflightFailure.ContradictoryEvidence,
            "windows", "instance", "filesystem", "metadata");
        foreach (var kind in new[] { "principal", "original-login", "sid", "server", "database", "version", "transaction" })
            await Reject("SQL metadata mismatch " + kind, r => r.Sql = kind switch
            {
                "principal" => r.Sql with { Login = "wrong" },
                "original-login" => r.Sql with { OriginalLogin = "wrong" },
                "sid" => r.Sql with { LoginSid = "wrong" },
                "server" => r.Sql with { InternalServer = "wrong" },
                "database" => r.Sql with { DatabaseContext = "PersonalFinanceDb" },
                "version" => r.Sql with { Version = "wrong" },
                _ => r.Sql with { TransactionCount = 1 }
            }, PreflightFailure.SqlIdentityMismatch, "windows", "instance", "filesystem", "metadata");
        await Reject("endpoint replaced at completion", r => r.FinalInstance = r.Instance with
            { Endpoint = r.Instance.Endpoint with { Pipe = @"np:\\.\pipe\LOCALDB#REPLACED\tsql\query" } },
            PreflightFailure.EndpointMismatch, "windows", "instance", "filesystem", "metadata", "instance");
        foreach (var stage in new[] { "windows", "instance", "filesystem", "metadata" })
        {
            var order = new[] { "windows", "instance", "filesystem", "metadata" };
            var expected = order.Take(Array.IndexOf(order, stage) + 1).ToArray();
            await Reject("sanitized fake exception at " + stage, r => r.ThrowAt = stage,
                PreflightFailure.ObservationFailed, expected);
        }
        await Reject("pre-cancelled request invokes no observer", r => r.Cancellation.Cancel(), PreflightFailure.Cancelled);
        await Reject("cancellation during metadata prevents completion discovery", r => r.CancelAt = "metadata",
            PreflightFailure.Cancelled, "windows", "instance", "filesystem", "metadata");
        await Reject("noncompleting fake is bounded by timeout", r =>
        {
            var scope = r.Request.Scope with { TimeoutMilliseconds = 20 };
            r.Request = r.Request with { Scope = scope, Approval = r.Request.Approval! with { Scope = scope } };
            r.HangAt = "metadata";
        }, PreflightFailure.TimedOut, "windows", "instance", "filesystem", "metadata");
        foreach (var mode in new[] { "live-read-only-preflight", "provision", "migrate", "cleanup",
            "Server=forbidden", "SELECT 1", "--endpoint" })
            await Check("CLI rejects unsupported input " + mode, async () =>
            {
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                Require(await OfflineEntry.RunAsync(["--mode", mode], standard, diagnostic) == 2
                    && standard.ToString().Length == 0
                    && diagnostic.ToString().Trim() == "REJECTED: only offline information and self-tests are available.");
            });
        foreach (var args in new[] { Array.Empty<string>(), new[] { "--mode", "info" } })
            await Check("informational entry " + args.Length, async () =>
            {
                using var standard = new StringWriter();
                using var diagnostic = new StringWriter();
                Require(await OfflineEntry.RunAsync(args, standard, diagnostic) == 0
                    && standard.ToString().Contains("No live adapters exist.", StringComparison.Ordinal)
                    && diagnostic.ToString().Length == 0);
            });
        output.WriteLine($"Fake preflight definitions: {passed} passed, {failed} failed, 0 skipped. No live identity or SQL behavior verified.");
        return failed == 0 ? 0 : 1;
    }
}
