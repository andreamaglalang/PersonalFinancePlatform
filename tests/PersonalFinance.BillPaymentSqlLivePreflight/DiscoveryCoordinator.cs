namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal sealed class DiscoveryCoordinator
{
    private readonly ISimulatedObserver<SimulatedWindows> windows;
    private readonly ISimulatedObserver<SimulatedInstance> instance;
    private readonly ISimulatedObserver<SimulatedArtifacts> artifacts;
    private readonly ISimulatedObserver<SimulatedLaunch> launch;
    private readonly ISimulatedBudget budget;
    private int used;

    internal DiscoveryCoordinator(ISimulatedObserver<SimulatedWindows> windows,
        ISimulatedObserver<SimulatedInstance> instance, ISimulatedObserver<SimulatedArtifacts> artifacts,
        ISimulatedObserver<SimulatedLaunch> launch, ISimulatedBudget budget)
    {
        this.windows = windows ?? throw new ArgumentNullException(nameof(windows));
        this.instance = instance ?? throw new ArgumentNullException(nameof(instance));
        this.artifacts = artifacts ?? throw new ArgumentNullException(nameof(artifacts));
        this.launch = launch ?? throw new ArgumentNullException(nameof(launch));
        this.budget = budget ?? throw new ArgumentNullException(nameof(budget));
        var resources = new object[] { windows, instance, artifacts, launch, budget };
        if (resources.Distinct(ReferenceEqualityComparer.Instance).Count() != resources.Length)
            throw new ArgumentException("Distinct exclusively owned dependencies required.");
    }

    internal async Task<DiscoveryResult> RunAsync(DiscoveryExpectation expected, CancellationToken ct)
    {
        if (Interlocked.Exchange(ref used, 1) != 0) return Result(ObservationFailure.AlreadyUsed);
        var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var cancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var registration = ct.Register(() => cancelled.TrySetResult(true));
        var failure = ObservationFailure.None;
        DiscoveryEvidence? evidence = null;
        var previous = DateTimeOffset.MinValue;
        var elapsed = TimeSpan.Zero;
        try
        {
            ObservationPolicy.Expectation(expected);
            void Gate()
            {
                ct.ThrowIfCancellationRequested();
                var timing = budget.Capture();
                ObservationPolicy.Need(!timing.Disposed, ObservationFailure.ObserverFailed);
                ObservationPolicy.Need(!timing.DeadlineSignaled && !budget.Expired.IsCompleted,
                    ObservationFailure.BudgetExpired);
                var now = timing.UtcNow;
                var next = timing.Elapsed;
                ObservationPolicy.Window(expected, now, previous, next, elapsed);
                previous = now;
                elapsed = next;
            }
            async Task<T> Read<T>(ISimulatedObserver<T> observer)
            {
                Gate();
                var pending = observer.ObserveAsync(lifetime.Token);
                // Observe late faults without accepting late evidence or invoking any next stage.
                _ = pending.ContinueWith(t => { _ = t.Exception; }, CancellationToken.None,
                    TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously,
                    TaskScheduler.Default);
                await Task.WhenAny(pending, budget.Expired, cancelled.Task);
                Gate();
                return await pending;
            }
            var w = await Read(windows);
            ObservationPolicy.Windows(expected, w);
            var first = await Read(instance);
            ObservationPolicy.Instance(expected, first);
            ObservationPolicy.Artifacts(expected, await Read(artifacts));
            ObservationPolicy.Launch(expected, await Read(launch));
            ObservationPolicy.Windows(expected, await Read(windows));
            var last = await Read(instance);
            ObservationPolicy.Instance(expected, last);
            ObservationPolicy.Need(first == last, ObservationFailure.Drift);
            ObservationPolicy.Artifacts(expected, await Read(artifacts));
            ObservationPolicy.Launch(expected, await Read(launch));
            Gate();
            evidence = new(last, previous);
        }
        catch (ObservationRejection ex) { failure = ex.Failure; }
        catch (OperationCanceledException) { failure = ct.IsCancellationRequested
            ? ObservationFailure.Cancelled : ObservationFailure.ObserverFailed; }
        catch (Exception) { failure = ObservationFailure.ObserverFailed; }
        finally
        {
            try { lifetime.Cancel(); }
            catch (Exception) { failure = ObservationFailure.DisposalFailed; }
            foreach (var resource in new IDisposable[] { launch, artifacts, instance, windows })
            {
                try { resource.Dispose(); }
                catch (Exception) { failure = ObservationFailure.DisposalFailed; }
            }
            try { budget.Dispose(); }
            catch (Exception) { failure = ObservationFailure.DisposalFailed; }
            try { registration.Dispose(); }
            catch (Exception) { failure = ObservationFailure.DisposalFailed; }
            try { lifetime.Dispose(); }
            catch (Exception) { failure = ObservationFailure.DisposalFailed; }
            if (failure == ObservationFailure.None)
            {
                try
                {
                    ct.ThrowIfCancellationRequested();
                    // Completion decision point is this post-cleanup timing capture.
                    // Validation and a conservative second cancellation sample follow;
                    // later deadline events do not revoke point-in-time evidence.
                    var terminal = budget.Capture();
                    ObservationPolicy.Need(terminal.Disposed, ObservationFailure.ObserverFailed);
                    ObservationPolicy.Need(!terminal.DeadlineSignaled, ObservationFailure.BudgetExpired);
                    ObservationPolicy.Window(expected, terminal.UtcNow, previous, terminal.Elapsed, elapsed);
                    ct.ThrowIfCancellationRequested();
                }
                catch (ObservationRejection ex) { failure = ex.Failure; }
                catch (OperationCanceledException) { failure = ObservationFailure.Cancelled; }
                catch (Exception) { failure = ObservationFailure.ObserverFailed; }
            }
        }
        return failure == ObservationFailure.None ? DiscoveryResult.Success(evidence!) : Result(failure);
    }

    private static DiscoveryResult Result(ObservationFailure failure) => DiscoveryResult.Rejected(failure);
}
