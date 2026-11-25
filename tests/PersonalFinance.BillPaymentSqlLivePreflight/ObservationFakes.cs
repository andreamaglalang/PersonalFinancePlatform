using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal sealed class ObservationFake<T>(string name, List<string> trace, T initial) : ISimulatedObserver<T>
{
    internal T Value { get; set; } = initial;
    internal T? SecondValue { get; set; }
    internal bool UseSecondValue { get; set; }
    internal Action? OnRead { get; set; }
    internal bool ThrowOnRead { get; set; }
    internal bool ThrowOnDispose { get; set; }
    internal Action? OnDispose { get; set; }
    internal TaskCompletionSource<T>? Pending { get; set; }
    internal int Reads { get; private set; }
    internal bool Disposed { get; private set; }
    internal CancellationToken ObservedToken { get; private set; }
    public Task<T> ObserveAsync(CancellationToken ct)
    {
        if (Disposed) throw new InvalidOperationException("Fake disposed.");
        trace.Add(name);
        Reads++;
        ObservedToken = ct;
        OnRead?.Invoke();
        if (ThrowOnRead) throw new InvalidOperationException("UNTRUSTED_SECRET");
        // Pending deliberately ignores cancellation to model an uncooperative observer.
        return Pending?.Task ?? Task.FromResult(Reads > 1 && UseSecondValue ? SecondValue! : Value);
    }
    public void Dispose()
    {
        trace.Add("dispose:" + name);
        if (Disposed) throw new InvalidOperationException("Duplicate disposal.");
        Disposed = true;
        OnDispose?.Invoke();
        if (ThrowOnDispose) throw new InvalidOperationException("UNTRUSTED_SECRET");
    }
}

internal sealed class ObservationFakeBudget(List<string> trace) : ISimulatedBudget
{
    internal static readonly DateTimeOffset Start = new(2026, 11, 7, 0, 0, 0, TimeSpan.Zero);
    public DateTimeOffset UtcNow { get; set; } = Start;
    public TimeSpan Elapsed { get; set; } = TimeSpan.Zero;
    private readonly TaskCompletionSource<bool> expired = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public Task Expired => expired.Task;
    internal void Expire() => expired.TrySetResult(true);
    internal Action? OnDispose { get; set; }
    internal bool Disposed { get; private set; }
    public SimulatedTiming Capture() => new(UtcNow, Elapsed, expired.Task.IsCompleted, Disposed);
    public void Dispose()
    {
        trace.Add("dispose:budget");
        Disposed = true;
        OnDispose?.Invoke();
    }
}

internal sealed class ObservationFixture
{
    internal List<string> Trace { get; } = [];
    internal DiscoveryExpectation Expected { get; set; }
    internal ObservationFake<SimulatedWindows> Windows { get; }
    internal ObservationFake<SimulatedInstance> Instance { get; }
    internal ObservationFake<SimulatedArtifacts> Artifacts { get; }
    internal ObservationFake<SimulatedLaunch> Launch { get; }
    internal ObservationFakeBudget Budget { get; }
    internal DiscoveryCoordinator Coordinator { get; }

    internal ObservationFixture()
    {
        // Deliberately synthetic: never the historical machine, SID, runtime or real pipe.
        var owner = new IdentityDeclaration("FAKE-HOST", @"FAKE-HOST\tester", "S-1-5-21-1-2-3-1001");
        var closure = ImmutableArray.Create(
            new ExpectedFile(@"X:\FAKE\host.exe", 10, new string('A', 64), "FAKE-VOLUME", "HOST"),
            new ExpectedFile(@"X:\FAKE\entry.dll", 20, new string('B', 64), "FAKE-VOLUME", "ENTRY"),
            new ExpectedFile(@"X:\FAKE\runtime.dll", 30, new string('C', 64), "FAKE-VOLUME", "RUNTIME"));
        var launch = new ExpectedLaunch(closure[0].Path, closure[1].Path, @"X:\FAKE",
            [closure[1].Path, "--offline-fake"], "FAKE-RUNTIME-1", closure);
        Expected = new("offline-discovery-simulation", owner, "FAKE-INSTANCE", "FAKE-VERSION-1", launch,
            ObservationFakeBudget.Start.AddMinutes(-1), ObservationFakeBudget.Start,
            ObservationFakeBudget.Start.AddMinutes(5), TimeSpan.FromSeconds(30));
        Windows = new("windows", Trace, new(owner, true, false, false));
        Instance = new("instance", Trace, new(Expected.Instance, owner, Expected.Version, true, false, true,
            new(@"np:\\.\pipe\LOCALDB#FAKEONLY\tsql\query", @"FAKE-HOST\LOCALDB#FAKEONLY"),
            new(123, 456, owner.Sid)));
        Artifacts = new("artifacts", Trace, new(closure.Select(f => new SimulatedFile(
            new(f.Path, f.Length, f.Sha256, f.Volume, f.FileId), true, true,
            true, false, false, 1)).ToImmutableArray(), true));
        Launch = new("launch", Trace, new(launch.HostPath, launch.EntryPath, launch.WorkingDirectory,
            launch.Arguments, launch.RuntimeVersion, true, false, true));
        Budget = new(Trace);
        Coordinator = new(Windows, Instance, Artifacts, Launch, Budget);
    }
    internal Task<DiscoveryResult> RunAsync(CancellationToken ct = default) => Coordinator.RunAsync(Expected, ct);
    internal void ReleasePending()
    {
        Windows.Pending?.TrySetCanceled();
        Instance.Pending?.TrySetCanceled();
        Artifacts.Pending?.TrySetCanceled();
        Launch.Pending?.TrySetCanceled();
    }
}
