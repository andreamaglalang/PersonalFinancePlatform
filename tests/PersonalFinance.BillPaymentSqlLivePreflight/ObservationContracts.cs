using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

// These types model synthetic evidence only. They are not approval capabilities.
internal sealed record ExpectedFile(string Path, long Length, string Sha256,
    string Volume, string FileId);
internal sealed record ExpectedLaunch(string HostPath, string EntryPath, string WorkingDirectory,
    ImmutableArray<string> Arguments, string RuntimeVersion, ImmutableArray<ExpectedFile> Closure);
internal sealed record DiscoveryExpectation(string Operation, IdentityDeclaration Identity,
    string Instance, string Version, ExpectedLaunch Launch, DateTimeOffset IssuedUtc,
    DateTimeOffset NotBeforeUtc, DateTimeOffset ExpiresUtc, TimeSpan Budget);
internal sealed record SimulatedWindows(IdentityDeclaration Identity, bool Complete,
    bool Impersonated, bool Elevated);
internal sealed record ProcessGeneration(int Pid, long CreationMarker, string OwnerSid);
internal sealed record SimulatedInstance(string Name, IdentityDeclaration Owner, string Version,
    bool Running, bool Shared, bool Complete, EndpointDeclaration Endpoint, ProcessGeneration Process);
internal sealed record SimulatedFileIdentity(string Path, long Length, string Sha256,
    string Volume, string FileId);
internal sealed record SimulatedFile(SimulatedFileIdentity Identity, bool Complete, bool FixedLocalVolume,
    bool AncestorsVerified, bool ReparseFound, bool AliasFound, int LinkCount);
internal sealed record SimulatedArtifacts(ImmutableArray<SimulatedFile> Files, bool Complete);
internal sealed record SimulatedLaunch(string HostPath, string EntryPath, string WorkingDirectory,
    ImmutableArray<string> Arguments, string RuntimeVersion, bool Complete,
    bool UnexpectedEnvironment, bool ClosureVerified);
internal enum ObservationFailure
{
    None, InvalidExpectation, InvalidWindow, BudgetExpired, ClockRollback, WindowsMismatch,
    InstanceMismatch, EndpointMismatch, ArtifactMismatch, LaunchMismatch, Drift,
    Cancelled, ObserverFailed, DisposalFailed, AlreadyUsed
}
internal sealed record DiscoveryEvidence(SimulatedInstance Instance, DateTimeOffset ObservedUtc);
internal sealed class DiscoveryResult
{
    private DiscoveryResult(ObservationFailure failure, DiscoveryEvidence? evidence)
    {
        Failure = failure;
        Evidence = evidence;
    }
    public bool Validated => Failure == ObservationFailure.None;
    public ObservationFailure Failure { get; }
    public DiscoveryEvidence? Evidence { get; }
    internal static DiscoveryResult Success(DiscoveryEvidence evidence)
    {
        ArgumentNullException.ThrowIfNull(evidence);
        if (evidence.Instance is null || evidence.ObservedUtc.Offset != TimeSpan.Zero)
            throw new ArgumentException("Complete UTC evidence required.", nameof(evidence));
        return new(ObservationFailure.None, evidence);
    }
    internal static DiscoveryResult Rejected(ObservationFailure failure)
    {
        if (failure == ObservationFailure.None || !Enum.IsDefined(failure))
            throw new ArgumentOutOfRangeException(nameof(failure));
        return new(failure, null);
    }
    public bool HumanApprovalAuthenticated => false;
    public bool LiveExecutionAuthorized => false;
    public bool OfflineSimulationOnly => true;
    public string Diagnostic => "Offline discovery: " + Failure + ".";
}

// Ownership transfers to the single-use coordinator. Disposal must revoke pending work.
// No interface exposes SQL, process launch, instance management or a live factory.
internal interface ISimulatedObserver<T> : IDisposable
{
    Task<T> ObserveAsync(CancellationToken cancellationToken);
}
internal interface ISimulatedBudget : IDisposable
{
    // One coherent sample; remains readable after Dispose and includes disposal-time changes.
    // Dispose releases resources, not the readable timing state. Expired is a stable task.
    SimulatedTiming Capture();
    Task Expired { get; }
}
internal sealed record SimulatedTiming(DateTimeOffset UtcNow, TimeSpan Elapsed,
    bool DeadlineSignaled, bool Disposed);
internal sealed class ObservationRejection(ObservationFailure failure) : Exception
{
    internal ObservationFailure Failure { get; } = failure;
}
