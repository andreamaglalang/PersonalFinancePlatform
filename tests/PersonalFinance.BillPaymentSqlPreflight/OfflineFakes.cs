using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlPreflight;

internal sealed class OfflineClock : TimeProvider
{
    internal static readonly DateTimeOffset Fixed = new(2026, 11, 7, 0, 0, 0, TimeSpan.Zero);
    internal DateTimeOffset Value { get; set; } = Fixed;
    public override DateTimeOffset GetUtcNow() => Value;
}

internal sealed class OfflineRig : IWindowsIdentityObserver, ILocalDbObserver,
    IFilesystemObserver, IReadOnlyMetadataProbe
{
    internal PreflightRequest Request { get; set; }
    internal WindowsIdentityObservation Windows { get; set; }
    internal LocalDbObservation Instance { get; set; }
    internal LocalDbObservation? FinalInstance { get; set; }
    internal FilesystemObservation Files { get; set; }
    internal SqlMetadataObservation Sql { get; set; }
    internal OfflineClock Clock { get; } = new();
    internal List<string> Calls { get; } = [];
    internal string? ThrowAt { get; set; }
    internal string? HangAt { get; set; }
    internal string? CancelAt { get; set; }
    internal CancellationTokenSource Cancellation { get; } = new();
    private int instanceReads;

    internal OfflineRig()
    {
        var run = Guid.Parse("a1234567-89ab-4cde-8fab-0123456789ab");
        var name = "PersonalFinanceBillPaymentTests_" + run.ToString("N");
        var root = @"D:\PersonalFinanceBillPaymentSqlTests\" + run.ToString("D");
        var target = new ExpectedDatabaseTarget(run.ToString("D"), name, root + "\\" + name + ".mdf",
            root + "\\" + name + "_log.ldf", "absent-target");
        var owner = new IdentityDeclaration(PreflightPolicy.HistoricalComputer,
            PreflightPolicy.HistoricalAccount, PreflightPolicy.HistoricalSid);
        var endpoint = new EndpointDeclaration(@"np:\\.\pipe\LOCALDB#TESTONLY01\tsql\query",
            @"OWLI-FIXTURE\LOCALDB#TESTONLY01");
        var artifacts = new ArtifactDeclaration(new string('A', 64), PreflightPolicy.ExecutableName, new string('B', 64));
        var scope = new ApprovedPreflightScope(target, owner, owner,
            PreflightPolicy.HistoricalInstance, PreflightPolicy.HistoricalVersion, endpoint, artifacts,
            "live-read-only-preflight", OfflineClock.Fixed.AddMinutes(-1),
            OfflineClock.Fixed.AddMinutes(-1), OfflineClock.Fixed.AddMinutes(5), 1000, PreflightPolicy.Migrations);
        Request = new PreflightRequest("simulate-read-only-preflight", scope,
            new OfflineApprovalObservation(scope, "offline-test-simulation",
                "b7654321-cdef-4123-9abc-fedcba987654"), artifacts);
        Windows = new(owner.Computer, owner.Account, owner.Sid, false);
        Instance = new(scope.Instance, owner.Account, owner.Sid, scope.Version, true, false,
            new NamedPipeObservation(endpoint.Pipe, endpoint.InternalServer));
        Files = new(target.DataPath, target.LogPath, true, true, true, false, false, false, false, true);
        Sql = new(endpoint.InternalServer, scope.Version, true, "master", owner.Account, owner.Account,
            owner.Sid, true, "Named pipe", true, 0, 0,
            new TargetAbsenceEvidence(target.Name, false, true), ImmutableArray<string>.Empty);
    }

    internal Task<PreflightResult> RunAsync() =>
        new PreflightOrchestrator(this, this, this, this, Clock).RunAsync(Request, Cancellation.Token);

    Task<WindowsIdentityObservation> IWindowsIdentityObserver.ObserveAsync(CancellationToken ct) =>
        Next("windows", Windows, ct);

    Task<LocalDbObservation> ILocalDbObserver.ObserveAsync(CancellationToken ct) =>
        Next("instance", instanceReads++ == 0 ? Instance : FinalInstance ?? Instance, ct);

    Task<FilesystemObservation> IFilesystemObserver.ObserveAsync(ExpectedDatabaseTarget target, CancellationToken ct) =>
        Next("filesystem", Files, ct);

    Task<SqlMetadataObservation> IReadOnlyMetadataProbe.ObserveAsync(EndpointDeclaration endpoint,
        ExpectedDatabaseTarget target, CancellationToken ct) =>
        endpoint == Request.Scope.Endpoint && target == Request.Scope.Target
            ? Next("metadata", Sql, ct)
            : Task.FromException<SqlMetadataObservation>(new InvalidOperationException("Unexpected fake probe arguments."));

    private Task<T> Next<T>(string stage, T value, CancellationToken ct)
    {
        Calls.Add(stage);
        if (stage == CancelAt) Cancellation.Cancel();
        if (stage == ThrowAt)
            return Task.FromException<T>(new InvalidOperationException("UNTRUSTED_INTERNAL_SECRET"));
        if (stage == HangAt)
            return new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously).Task;
        ct.ThrowIfCancellationRequested();
        return Task.FromResult(value);
    }
}
