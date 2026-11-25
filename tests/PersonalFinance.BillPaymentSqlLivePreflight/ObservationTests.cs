using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class ObservationTests
{
    private const string Cleanup = "dispose:launch,dispose:artifacts,dispose:instance,dispose:windows,dispose:budget";
    private const string Full = "windows,instance,artifacts,launch,windows,instance,artifacts,launch";

    internal static void Register(Action<string, Action> check)
    {
        void Case(string name, Action<ObservationFixture> mutate, ObservationFailure failure, string reads) =>
            check("discovery: " + name, () =>
            {
                var f = new ObservationFixture();
                mutate(f);
                Assert(Await(f, f.RunAsync()), failure);
                Trace(f, reads);
            });
        Case("complete synthetic evidence", _ => { }, ObservationFailure.None, Full);
        foreach (var seconds in new[] { 10, 60 })
            Case("valid budget boundary " + seconds, f => f.Expected = f.Expected with
                { Budget = TimeSpan.FromSeconds(seconds) }, ObservationFailure.None, Full);
        Case("metadata operation rejected", f => f.Expected = f.Expected with { Operation = ReviewPacketPolicy.Metadata },
            ObservationFailure.InvalidExpectation, "");
        Case("null expectation", f => f.Expected = null!, ObservationFailure.InvalidExpectation, "");
        Case("empty expected SID", f => f.Expected = f.Expected with
            { Identity = f.Expected.Identity with { Sid = "" } }, ObservationFailure.InvalidExpectation, "");
        Case("empty closure", f => f.Expected = f.Expected with
            { Launch = f.Expected.Launch with { Closure = [] } }, ObservationFailure.InvalidExpectation, "");
        foreach (var path in new[] { @"X:\FAKE\.\host.exe", @"X:\FAKE\host.exe.", @"X:\FAKE\host.exe:stream", @"\\remote\host.exe" })
            Case("noncanonical expected path " + path, f => f.Expected = f.Expected with
                { Launch = f.Expected.Launch with { HostPath = path, Closure = f.Expected.Launch.Closure.SetItem(0,
                    f.Expected.Launch.Closure[0] with { Path = path }) } }, ObservationFailure.InvalidExpectation, "");
        Case("duplicate physical file", f => f.Expected = f.Expected with
            { Launch = f.Expected.Launch with { Closure = f.Expected.Launch.Closure.SetItem(1,
                f.Expected.Launch.Closure[1] with { Volume = f.Expected.Launch.Closure[0].Volume,
                    FileId = f.Expected.Launch.Closure[0].FileId }) } },
            ObservationFailure.InvalidExpectation, "");
        foreach (var seconds in new[] { 9, 61 })
            Case("invalid budget " + seconds, f => f.Expected = f.Expected with { Budget = TimeSpan.FromSeconds(seconds) },
                ObservationFailure.InvalidExpectation, "");
        Case("expired review", f => f.Budget.UtcNow = f.Expected.ExpiresUtc, ObservationFailure.InvalidWindow, "");
        Case("not yet valid", f => f.Budget.UtcNow = f.Expected.NotBeforeUtc.AddTicks(-1), ObservationFailure.InvalidWindow, "");
        Case("non UTC window", f => f.Expected = f.Expected with
            { IssuedUtc = f.Expected.IssuedUtc.ToOffset(TimeSpan.FromHours(1)) }, ObservationFailure.InvalidExpectation, "");
        Case("oversized review window", f => f.Expected = f.Expected with
            { ExpiresUtc = f.Expected.IssuedUtc.AddHours(2) }, ObservationFailure.InvalidExpectation, "");
        Case("elapsed budget boundary", f => f.Budget.Elapsed = f.Expected.Budget, ObservationFailure.BudgetExpired, "");
        Case("deadline signal", f => f.Budget.Expire(), ObservationFailure.BudgetExpired, "");
        Case("wall clock rollback", f => f.Windows.OnRead = () => f.Budget.UtcNow = f.Budget.UtcNow.AddTicks(-1),
            ObservationFailure.ClockRollback, "windows");
        Case("monotonic rollback", f => f.Windows.OnRead = () => f.Budget.Elapsed = TimeSpan.FromTicks(-1),
            ObservationFailure.ClockRollback, "windows");
        Case("expiry during observation", f => f.Windows.OnRead = () => f.Budget.UtcNow = f.Expected.ExpiresUtc,
            ObservationFailure.InvalidWindow, "windows");

        foreach (var field in new[] { "computer", "account", "sid", "impersonated", "elevated", "incomplete", "null" })
            Case("Windows " + field, f => f.Windows.Value = field switch
            {
                "computer" => f.Windows.Value with { Identity = f.Expected.Identity with { Computer = "OTHER" } },
                "account" => f.Windows.Value with { Identity = f.Expected.Identity with { Account = "OTHER" } },
                "sid" => f.Windows.Value with { Identity = f.Expected.Identity with { Sid = "OTHER" } },
                "impersonated" => f.Windows.Value with { Impersonated = true },
                "elevated" => f.Windows.Value with { Elevated = true },
                "incomplete" => f.Windows.Value with { Complete = false },
                _ => null!
            }, ObservationFailure.WindowsMismatch, "windows");
        foreach (var field in new[] { "name", "owner", "version", "stopped", "shared", "incomplete", "pid", "generation", "processOwner", "null" })
            Case("instance " + field, f => f.Instance.Value = field switch
            {
                "name" => f.Instance.Value with { Name = "OTHER" },
                "owner" => f.Instance.Value with { Owner = f.Expected.Identity with { Sid = "OTHER" } },
                "version" => f.Instance.Value with { Version = "OTHER" },
                "stopped" => f.Instance.Value with { Running = false },
                "shared" => f.Instance.Value with { Shared = true },
                "incomplete" => f.Instance.Value with { Complete = false },
                "pid" => f.Instance.Value with { Process = f.Instance.Value.Process with { Pid = 0 } },
                "generation" => f.Instance.Value with { Process = f.Instance.Value.Process with { CreationMarker = 0 } },
                "processOwner" => f.Instance.Value with { Process = f.Instance.Value.Process with { OwnerSid = "OTHER" } },
                _ => null!
            }, ObservationFailure.InstanceMismatch, "windows,instance");
        foreach (var pipe in new[] { @"np:\\remote\pipe\LOCALDB#FAKEONLY\tsql\query", @"(localdb)\FAKE", "", "*" })
            Case("bad pipe " + pipe, f => f.Instance.Value = f.Instance.Value with
                { Endpoint = f.Instance.Value.Endpoint with { NamedPipe = pipe } },
                ObservationFailure.EndpointMismatch, "windows,instance");
        Case("internal identity contradiction", f => f.Instance.Value = f.Instance.Value with
            { Endpoint = f.Instance.Value.Endpoint with { InternalServer = "OTHER" } },
            ObservationFailure.EndpointMismatch, "windows,instance");
        Case("restart with same pipe and PID", f =>
        {
            f.Instance.UseSecondValue = true;
            f.Instance.SecondValue = f.Instance.Value with { Process = f.Instance.Value.Process with { CreationMarker = 999 } };
        }, ObservationFailure.Drift, "windows,instance,artifacts,launch,windows,instance");
        Case("changed valid endpoint", f =>
        {
            f.Instance.UseSecondValue = true;
            f.Instance.SecondValue = f.Instance.Value with
                { Endpoint = new(@"np:\\.\pipe\LOCALDB#OTHER\tsql\query", @"FAKE-HOST\LOCALDB#OTHER") };
        }, ObservationFailure.Drift, "windows,instance,artifacts,launch,windows,instance");

        foreach (var field in new[] { "hash", "length", "path", "volume", "fileId", "incomplete", "fixed", "ancestors", "reparse", "alias", "links" })
            Case("artifact " + field, f =>
            {
                var file = f.Artifacts.Value.Files[0];
                file = field switch
                {
                    "hash" => file with { Identity = file.Identity with { Sha256 = new string('F', 64) } },
                    "length" => file with { Identity = file.Identity with { Length = 11 } },
                    "path" => file with { Identity = file.Identity with { Path = @"X:\OTHER" } },
                    "volume" => file with { Identity = file.Identity with { Volume = "OTHER" } },
                    "fileId" => file with { Identity = file.Identity with { FileId = "OTHER" } },
                    "incomplete" => file with { Complete = false },
                    "fixed" => file with { FixedLocalVolume = false },
                    "ancestors" => file with { AncestorsVerified = false },
                    "reparse" => file with { ReparseFound = true },
                    "alias" => file with { AliasFound = true },
                    _ => file with { LinkCount = 2 }
                };
                f.Artifacts.Value = f.Artifacts.Value with { Files = f.Artifacts.Value.Files.SetItem(0, file) };
            }, ObservationFailure.ArtifactMismatch, "windows,instance,artifacts");
        Case("missing artifacts", f => f.Artifacts.Value = f.Artifacts.Value with { Files = default },
            ObservationFailure.ArtifactMismatch, "windows,instance,artifacts");
        foreach (var field in new[] { "host", "entry", "cwd", "arguments", "runtime", "environment", "closure", "incomplete" })
            Case("launch " + field, f => f.Launch.Value = field switch
            {
                "host" => f.Launch.Value with { HostPath = @"X:\OTHER" },
                "entry" => f.Launch.Value with { EntryPath = @"X:\OTHER" },
                "cwd" => f.Launch.Value with { WorkingDirectory = @"X:\OTHER" },
                "arguments" => f.Launch.Value with { Arguments = f.Launch.Value.Arguments.Reverse().ToImmutableArray() },
                "runtime" => f.Launch.Value with { RuntimeVersion = "OTHER" },
                "environment" => f.Launch.Value with { UnexpectedEnvironment = true },
                "closure" => f.Launch.Value with { ClosureVerified = false },
                _ => f.Launch.Value with { Complete = false }
            }, ObservationFailure.LaunchMismatch, "windows,instance,artifacts,launch");
        Case("artifact recheck", f =>
        {
            f.Artifacts.UseSecondValue = true;
            f.Artifacts.SecondValue = f.Artifacts.Value with { Complete = false };
        }, ObservationFailure.ArtifactMismatch, "windows,instance,artifacts,launch,windows,instance,artifacts");
        Case("identity recheck", f =>
        {
            f.Windows.UseSecondValue = true;
            f.Windows.SecondValue = f.Windows.Value with { Elevated = true };
        }, ObservationFailure.WindowsMismatch, "windows,instance,artifacts,launch,windows");
        Case("launch recheck", f =>
        {
            f.Launch.UseSecondValue = true;
            f.Launch.SecondValue = f.Launch.Value with { UnexpectedEnvironment = true };
        }, ObservationFailure.LaunchMismatch, Full);

        var stages = new[] { "windows", "instance", "artifacts", "launch" };
        foreach (var stage in stages)
        {
            var prefix = string.Join(",", stages.Take(Array.IndexOf(stages, stage) + 1));
            Case("exception at " + stage, f => Configure(f, stage, () => throw new InvalidOperationException("UNTRUSTED_SECRET")),
                ObservationFailure.ObserverFailed, prefix);
            Case("deadline at " + stage, f => Configure(f, stage, f.Budget.Expire),
                ObservationFailure.BudgetExpired, prefix);
            check("discovery: cancellation at " + stage, () =>
            {
                using var cts = new CancellationTokenSource();
                var f = new ObservationFixture();
                Configure(f, stage, cts.Cancel);
                Assert(Await(f, f.RunAsync(cts.Token)), ObservationFailure.Cancelled);
                Trace(f, prefix);
            });
        }
        check("discovery: pre-cancellation", () =>
        {
            using var cts = new CancellationTokenSource();
            cts.Cancel();
            var f = new ObservationFixture();
            Assert(Await(f, f.RunAsync(cts.Token)), ObservationFailure.Cancelled);
            Trace(f, "");
        });
        foreach (var fault in new[] { false, true })
            check("discovery: ignore late completion fault=" + fault, () =>
            {
                var f = new ObservationFixture();
                var pending = new TaskCompletionSource<SimulatedWindows>(TaskCreationOptions.RunContinuationsAsynchronously);
                f.Windows.Pending = pending;
                var task = f.RunAsync();
                f.Budget.Expire();
                Assert(Await(f, task), ObservationFailure.BudgetExpired);
                Trace(f, "windows");
                Require(f.Windows.ObservedToken.IsCancellationRequested && f.Windows.Disposed);
                if (fault) pending.SetException(new InvalidOperationException("UNTRUSTED_SECRET"));
                else pending.SetResult(f.Windows.Value);
                Assert(Await(f, task), ObservationFailure.BudgetExpired);
                Trace(f, "windows");
            });
        Case("disposal failure continues cleanup", f => f.Launch.ThrowOnDispose = true,
            ObservationFailure.DisposalFailed, Full);
        Case("expiry during cleanup", f => f.Launch.OnDispose = f.Budget.Expire,
            ObservationFailure.BudgetExpired, Full);
        Case("review expiry during cleanup", f => f.Launch.OnDispose = () => f.Budget.UtcNow = f.Expected.ExpiresUtc,
            ObservationFailure.InvalidWindow, Full);
        check("discovery: pending cancellation and late result", () =>
        {
            using var cts = new CancellationTokenSource();
            var f = new ObservationFixture();
            var pending = new TaskCompletionSource<SimulatedWindows>(TaskCreationOptions.RunContinuationsAsynchronously);
            f.Windows.Pending = pending;
            var task = f.RunAsync(cts.Token);
            cts.Cancel();
            Assert(Await(f, task), ObservationFailure.Cancelled);
            Trace(f, "windows");
            pending.SetResult(f.Windows.Value);
            Assert(Await(f, task), ObservationFailure.Cancelled);
            Trace(f, "windows");
        });
        check("discovery: single use", () =>
        {
            var f = new ObservationFixture();
            Assert(Await(f, f.RunAsync()), ObservationFailure.None);
            Assert(Await(f, f.RunAsync()), ObservationFailure.AlreadyUsed);
            Trace(f, Full);
        });
        check("discovery: no implicit observer", () =>
        {
            var f = new ObservationFixture();
            try { _ = new DiscoveryCoordinator(null!, f.Instance, f.Artifacts, f.Launch, f.Budget); }
            catch (ArgumentNullException) { Require(f.Trace.Count == 0); return; }
            throw new InvalidOperationException("Expected explicit dependency requirement.");
        });
        RegisterHardening(check);
    }

    private static void RegisterHardening(Action<string, Action> check)
    {
        foreach (var (name, change, expected) in new (string, Func<SimulatedTiming, SimulatedTiming>, ObservationFailure)[]
        {
            ("valid", s => s, ObservationFailure.None),
            ("deadline", s => s with { DeadlineSignaled = true }, ObservationFailure.BudgetExpired),
            ("elapsed boundary", s => s with { Elapsed = TimeSpan.FromSeconds(30) }, ObservationFailure.BudgetExpired),
            ("elapsed overrun", s => s with { Elapsed = TimeSpan.FromSeconds(31) }, ObservationFailure.BudgetExpired),
            ("UTC expiry", s => s with { UtcNow = ObservationFakeBudget.Start.AddMinutes(5) }, ObservationFailure.InvalidWindow),
            ("UTC rollback", s => s with { UtcNow = s.UtcNow.AddTicks(-1) }, ObservationFailure.ClockRollback),
            ("elapsed rollback", s => s with { Elapsed = TimeSpan.FromTicks(-1) }, ObservationFailure.ClockRollback),
            ("not disposed", s => s with { Disposed = false }, ObservationFailure.ObserverFailed)
        })
            check("hardening: independent terminal budget " + name, () =>
            {
                var f = new ObservationFixture();
                var budget = new TerminalBudget(f.Trace) { DuringDispose = change };
                var coordinator = new DiscoveryCoordinator(f.Windows, f.Instance, f.Artifacts, f.Launch, budget);
                var result = Await(f, coordinator.RunAsync(f.Expected, CancellationToken.None));
                Assert(result, expected);
                Trace(f, Full);
                Require(budget.TerminalReads == 1);
                if (result.Validated)
                    Require(result.Evidence == new DiscoveryEvidence(f.Instance.Value, ObservationFakeBudget.Start));
            });
        check("hardening: original budget disposal expiry", () =>
        {
            var f = new ObservationFixture();
            f.Budget.OnDispose = f.Budget.Expire;
            Assert(Await(f, f.RunAsync()), ObservationFailure.BudgetExpired);
            Trace(f, Full);
        });
        check("hardening: independent budget disposal cancellation", () =>
        {
            using var cts = new CancellationTokenSource();
            var f = new ObservationFixture();
            var budget = new TerminalBudget(f.Trace) { DuringDispose = s => { cts.Cancel(); return s; } };
            var coordinator = new DiscoveryCoordinator(f.Windows, f.Instance, f.Artifacts, f.Launch, budget);
            Assert(Await(f, coordinator.RunAsync(f.Expected, cts.Token)), ObservationFailure.Cancelled);
            Trace(f, Full);
        });
        foreach (var stage in new[] { "launch", "artifacts", "instance", "windows", "budget" })
            check("hardening: disposal exception " + stage, () =>
            {
                var f = new ObservationFixture();
                switch (stage)
                {
                    case "launch": f.Launch.ThrowOnDispose = true; break;
                    case "artifacts": f.Artifacts.ThrowOnDispose = true; break;
                    case "instance": f.Instance.ThrowOnDispose = true; break;
                    case "windows": f.Windows.ThrowOnDispose = true; break;
                    case "budget": f.Budget.OnDispose = () => throw new InvalidOperationException("UNTRUSTED_SECRET"); break;
                }
                Assert(Await(f, f.RunAsync()), ObservationFailure.DisposalFailed);
                Trace(f, Full);
            });
        check("hardening: multiple disposal exceptions", () =>
        {
            var f = new ObservationFixture();
            f.Launch.ThrowOnDispose = f.Instance.ThrowOnDispose = f.Windows.ThrowOnDispose = true;
            Assert(Await(f, f.RunAsync()), ObservationFailure.DisposalFailed);
            Trace(f, Full);
        });
        check("hardening: throwing lifetime cancellation callback", () =>
        {
            var f = new ObservationFixture();
            CancellationTokenRegistration callback = default;
            f.Windows.OnRead = () =>
            {
                if (f.Windows.Reads == 1) callback = f.Windows.ObservedToken.Register(
                    () => throw new InvalidOperationException("UNTRUSTED_SECRET"));
            };
            try
            {
                Assert(Await(f, f.RunAsync()), ObservationFailure.DisposalFailed);
                Trace(f, Full);
            }
            finally { callback.Dispose(); }
        });
        foreach (var stage in new[] { "observer", "budget" })
            check("hardening: cancellation during " + stage + " cleanup", () =>
            {
                using var cts = new CancellationTokenSource();
                var f = new ObservationFixture();
                if (stage == "observer") f.Launch.OnDispose = cts.Cancel;
                else f.Budget.OnDispose = cts.Cancel;
                Assert(Await(f, f.RunAsync(cts.Token)), ObservationFailure.Cancelled);
                Trace(f, Full);
            });
        foreach (var cancelFirst in new[] { false, true })
            check("hardening: deadline cancellation tie " + cancelFirst, () =>
            {
                using var cts = new CancellationTokenSource();
                var f = new ObservationFixture();
                f.Launch.OnRead = () =>
                {
                    if (cancelFirst) { cts.Cancel(); f.Budget.Expire(); }
                    else { f.Budget.Expire(); cts.Cancel(); }
                };
                Assert(Await(f, f.RunAsync(cts.Token)), ObservationFailure.Cancelled);
                Trace(f, "windows,instance,artifacts,launch");
            });
        foreach (var (name, mutate, expected, reads) in new (string, Action<ObservationFixture>, ObservationFailure, string)[]
        {
            ("null owner", f => f.Instance.Value = f.Instance.Value with { Owner = null! }, ObservationFailure.InstanceMismatch, "windows,instance"),
            ("null process", f => f.Instance.Value = f.Instance.Value with { Process = null! }, ObservationFailure.InstanceMismatch, "windows,instance"),
            ("null endpoint", f => f.Instance.Value = f.Instance.Value with { Endpoint = null! }, ObservationFailure.EndpointMismatch, "windows,instance"),
            ("null artifacts", f => f.Artifacts.Value = null!, ObservationFailure.ArtifactMismatch, "windows,instance,artifacts"),
            ("null file", f => f.Artifacts.Value = f.Artifacts.Value with { Files = f.Artifacts.Value.Files.SetItem(0, null!) }, ObservationFailure.ArtifactMismatch, "windows,instance,artifacts"),
            ("null file identity", f => f.Artifacts.Value = f.Artifacts.Value with { Files = f.Artifacts.Value.Files.SetItem(0, f.Artifacts.Value.Files[0] with { Identity = null! }) }, ObservationFailure.ArtifactMismatch, "windows,instance,artifacts"),
            ("null launch", f => f.Launch.Value = null!, ObservationFailure.LaunchMismatch, "windows,instance,artifacts,launch")
        })
            check("hardening: " + name, () =>
            {
                var f = new ObservationFixture();
                mutate(f);
                Assert(Await(f, f.RunAsync()), expected);
                Trace(f, reads);
            });
        foreach (var stage in new[] { "instance", "artifacts", "launch" })
            foreach (var fault in new[] { false, true })
                check("hardening: later pending/late " + stage + " fault=" + fault, () =>
                {
                    var f = new ObservationFixture();
                    Action complete;
                    if (stage == "instance") complete = PreparePending(f.Instance, fault);
                    else if (stage == "artifacts") complete = PreparePending(f.Artifacts, fault);
                    else complete = PreparePending(f.Launch, fault);
                    var task = f.RunAsync();
                    f.Budget.Expire();
                    Assert(Await(f, task), ObservationFailure.BudgetExpired);
                    var reads = stage == "instance" ? "windows,instance" : stage == "artifacts"
                        ? "windows,instance,artifacts" : "windows,instance,artifacts,launch";
                    Trace(f, reads);
                    complete();
                    Assert(Await(f, task), ObservationFailure.BudgetExpired);
                    Trace(f, reads);
                });
        foreach (var sid in new[] { "S-1-5-21-0-0-0-0", "S-1-5-21-4294967295-4294967295-4294967295-4294967295" })
            check("hardening: accepted SID boundary " + sid, () =>
            {
                var f = new ObservationFixture();
                SetIdentity(f, f.Expected.Identity with { Sid = sid });
                Assert(Await(f, f.RunAsync()), ObservationFailure.None);
                Trace(f, Full);
            });
        foreach (var sid in new[] { "S-1-5-21-4294967296-2-3-4", "S-1-5-21-01-2-3-4",
            "S-1-5-21-+1-2-3-4", "S-1-5-21-1-2-3", "s-1-5-21-1-2-3-4",
            "S-1-5-21-1-2-3-4 ", "S-1-5-21-\u0661-2-3-4", "S-1-5-21-99999999999-2-3-4" })
            check("hardening: rejected SID boundary " + sid, () =>
            {
                var f = new ObservationFixture();
                SetIdentity(f, f.Expected.Identity with { Sid = sid });
                Assert(Await(f, f.RunAsync()), ObservationFailure.InvalidExpectation);
                Trace(f, "");
            });
        foreach (var suffix in new[] { "", " ", "tester\\extra", "tester\n" })
            check("hardening: rejected account suffix length " + suffix.Length, () =>
            {
                var f = new ObservationFixture();
                SetIdentity(f, f.Expected.Identity with { Account = f.Expected.Identity.Computer + "\\" + suffix });
                Assert(Await(f, f.RunAsync()), ObservationFailure.InvalidExpectation);
                Trace(f, "");
            });
        check("hardening: path uniqueness independent of physical identity", () =>
        {
            var f = new ObservationFixture();
            f.Expected = f.Expected with { Launch = f.Expected.Launch with { Closure = f.Expected.Launch.Closure
                .SetItem(2, f.Expected.Launch.Closure[2] with { Path = f.Expected.Launch.Closure[0].Path }) } };
            Assert(Await(f, f.RunAsync()), ObservationFailure.InvalidExpectation);
            Trace(f, "");
        });
        check("hardening: aliased dependencies rejected", () =>
        {
            var f = new ObservationFixture();
            var alias = new AliasedObserver();
            try { _ = new DiscoveryCoordinator(f.Windows, f.Instance, alias, alias, f.Budget); }
            catch (ArgumentException) { Require(f.Trace.Count == 0 && !alias.Called); return; }
            throw new InvalidOperationException("Expected alias rejection.");
        });
        check("hardening: result factories reject inconsistent inputs", () =>
        {
            ExpectArgument(() => DiscoveryResult.Rejected(ObservationFailure.None));
            ExpectArgument(() => DiscoveryResult.Rejected((ObservationFailure)999));
            ExpectArgument(() => DiscoveryResult.Success(null!));
            ExpectArgument(() => DiscoveryResult.Success(new(null!, ObservationFakeBudget.Start)));
            var f = new ObservationFixture();
            ExpectArgument(() => DiscoveryResult.Success(new(f.Instance.Value,
                ObservationFakeBudget.Start.ToOffset(TimeSpan.FromHours(1)))));
        });
        check("hardening: independent watchdog reports timeout and releases promise", () =>
        {
            var pending = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
            var released = false;
            try { _ = Bounded(pending.Task, () => { released = true; pending.TrySetResult(1); }, TimeSpan.FromMilliseconds(20)); }
            catch (TimeoutException) { Require(released && pending.Task.IsCompletedSuccessfully); return; }
            throw new InvalidOperationException("Expected independent watchdog timeout.");
        });
    }

    private static Action PreparePending<T>(ObservationFake<T> observer, bool fault)
    {
        var pending = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        observer.Pending = pending;
        return () =>
        {
            if (fault) pending.TrySetException(new InvalidOperationException("UNTRUSTED_SECRET"));
            else pending.TrySetResult(observer.Value);
        };
    }
    private static void SetIdentity(ObservationFixture f, IdentityDeclaration identity)
    {
        f.Expected = f.Expected with { Identity = identity };
        f.Windows.Value = f.Windows.Value with { Identity = identity };
        f.Instance.Value = f.Instance.Value with { Owner = identity,
            Process = f.Instance.Value.Process with { OwnerSid = identity.Sid } };
    }
    private static void ExpectArgument(Action action)
    {
        try { action(); }
        catch (ArgumentException) { return; }
        throw new InvalidOperationException("Expected invalid factory input rejection.");
    }

    // Independent implementation, not derived from ObservationFakeBudget. Models
    // disposal changing terminal timing and requires post-disposal reads explicitly.
    private sealed class TerminalBudget(List<string> trace) : ISimulatedBudget
    {
        private SimulatedTiming state = new(ObservationFakeBudget.Start, TimeSpan.Zero, false, false);
        private readonly TaskCompletionSource<bool> deadline = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal Func<SimulatedTiming, SimulatedTiming> DuringDispose { get; init; } = s => s;
        internal int TerminalReads { get; private set; }
        public Task Expired => deadline.Task;
        public SimulatedTiming Capture()
        {
            if (disposed) TerminalReads++;
            return state;
        }
        private bool disposed;
        public void Dispose()
        {
            trace.Add("dispose:budget");
            disposed = true;
            state = DuringDispose(state with { Disposed = true });
            if (state.DeadlineSignaled) deadline.TrySetResult(true);
        }
    }
    private sealed class AliasedObserver : ISimulatedObserver<SimulatedArtifacts>, ISimulatedObserver<SimulatedLaunch>
    {
        internal bool Called { get; private set; }
        Task<SimulatedArtifacts> ISimulatedObserver<SimulatedArtifacts>.ObserveAsync(CancellationToken ct)
        { Called = true; throw new InvalidOperationException("Unexpected call."); }
        Task<SimulatedLaunch> ISimulatedObserver<SimulatedLaunch>.ObserveAsync(CancellationToken ct)
        { Called = true; throw new InvalidOperationException("Unexpected call."); }
        public void Dispose() { Called = true; }
    }

    private static DiscoveryResult Await(ObservationFixture f, Task<DiscoveryResult> task) =>
        Bounded(task, f.ReleasePending, TimeSpan.FromSeconds(5));

    // Harness wall-clock watchdog is independent of ISimulatedBudget. No Task.Run,
    // process termination or background worker is used. Synchronous entry/cleanup
    // blocking before a Task is returned cannot be interrupted by this harness.
    private static T Bounded<T>(Task<T> task, Action releasePending, TimeSpan timeout)
    {
        try { return task.WaitAsync(timeout).GetAwaiter().GetResult(); }
        catch (TimeoutException)
        {
            releasePending(); // Complete owned fake promises; do not abandon injected pending work.
            _ = task.ContinueWith(t => { _ = t.Exception; }, CancellationToken.None,
                TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);
            throw; // Existing Check wrapper reports a failed test, never a pass/skip.
        }
    }

    private static void Configure(ObservationFixture f, string stage, Action action)
    {
        switch (stage)
        {
            case "windows": f.Windows.OnRead = action; break;
            case "instance": f.Instance.OnRead = action; break;
            case "artifacts": f.Artifacts.OnRead = action; break;
            case "launch": f.Launch.OnRead = action; break;
            default: throw new InvalidOperationException("Unknown fake stage.");
        }
    }
    private static void Trace(ObservationFixture f, string reads) =>
        Require(string.Join(",", f.Trace) == (reads.Length == 0 ? Cleanup : reads + "," + Cleanup));
    private static void Assert(DiscoveryResult result, ObservationFailure failure)
    {
        Require(result.Failure == failure && result.Validated == (failure == ObservationFailure.None)
            && (result.Evidence is not null) == result.Validated && result.OfflineSimulationOnly
            && !result.HumanApprovalAuthenticated && !result.LiveExecutionAuthorized
            && result.Diagnostic == "Offline discovery: " + failure + "." && result.Diagnostic.Length <= 128);
    }
    private static void Require(bool value)
    {
        if (!value) throw new InvalidOperationException("Offline observation assertion failed.");
    }
}
