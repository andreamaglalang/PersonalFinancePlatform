using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal enum BindingOperation { DiscoveryOnlyReview, EndpointBoundMetadataReview }
// There is deliberately no independently-observed or authenticated-live member.
internal enum BindingEvidenceSource { SyntheticOnly }
internal enum BindingObservationFailure { None, Missing, Cancelled, TimedOut, ObserverFailed }
internal enum BindingFailure
{
    None, MalformedInput, UnsupportedVersion, InvalidScope, WrongTarget, InvalidIdentity,
    InvalidEndpoint, InvalidInventory, InvalidTiming, InvalidInvocation, ChainMismatch,
    AnchorMismatch, ObservationMismatch, SyntheticPromotion, IncompleteEvidence, CleanupFailed
}
internal sealed record BindingByteIdentity(long ByteLength, string Sha256);
internal sealed record BindingExpectedWindows(IdentityDeclaration Identity, bool RequireNonElevated,
    bool RequireNonImpersonated);
internal sealed record BindingExpectedInstance(string Name, IdentityDeclaration Owner, string Version,
    bool RequireRunning, bool RequirePrivate);
internal sealed record BindingFrozenEndpoint(EndpointDeclaration Endpoint, ProcessGeneration Generation);
internal sealed record BindingExpectedFile(string Role, string AbsolutePath, long ByteLength,
    string Sha256, string VolumeId, string FileId);
internal sealed record BindingRuntimeProfile(string Name, string Version, string Sha256,
    ImmutableArray<string> DependencyPaths);
internal sealed record BindingCommand(string HostPath, string EntryPoint, string WorkingDirectory,
    ImmutableArray<string> Arguments);
internal sealed record BindingWindow(DateTimeOffset IssuedUtc, DateTimeOffset NotBeforeUtc,
    DateTimeOffset ExpiresUtc, int MaximumEvidenceAgeSeconds);
internal sealed record BindingBudgets(int OverallSeconds, int DiscoverySeconds,
    int ConnectSeconds, int CommandSeconds);
internal sealed record BindingProhibitions(bool Writes, bool Retries, bool Fallback, bool Startup,
    bool Adoption, bool AutomaticProgression);
internal sealed record BindingHumanReview(string Procedure, string ReferenceId,
    bool IndependentReviewRequired, bool ManualInvocationRequired, bool OneInvocationRequired);
internal sealed record BindingMetadataLineage(string DiscoveryInvocationId, BindingByteIdentity DiscoveryReport,
    string DiscoveryReviewReferenceId, string SeparateReviewReferenceId, QuerySetIdentity QuerySet);
internal sealed record ReviewScopeV2(int SchemaVersion, string PolicyProfile, string RequestId,
    string RunId, string InvocationId, string Nonce, BindingOperation Operation, TargetDeclaration Target,
    BindingExpectedWindows Windows, BindingExpectedInstance Instance, BindingFrozenEndpoint? Endpoint,
    string ManifestSha256, ImmutableArray<BindingExpectedFile> Inventory, BindingRuntimeProfile Runtime,
    BindingCommand Command, BindingWindow Window, BindingBudgets Budgets,
    BindingProhibitions Prohibitions, BindingHumanReview HumanReview, BindingMetadataLineage? MetadataLineage);
internal sealed record InvocationDescriptorV1(int SchemaVersion, string PolicyProfile,
    string InvocationId, string RequestId, string RunId, string Nonce, BindingOperation Operation,
    BindingByteIdentity ScopeIdentity, BindingCommand Command, string ManifestSha256,
    string RuntimeProfileSha256, BindingWindow Window, string ExternalReviewReferenceId);

// Observed identity/file/runtime/launch values have different types from declarations.
internal sealed record BindingObservedWindows(string Computer, string Account, string Sid,
    bool Elevated, bool Impersonated, bool Complete);
internal sealed record BindingObservedInstance(string Name, string OwnerComputer, string OwnerAccount,
    string OwnerSid, string Version, bool Running, bool Shared, ProcessGeneration Generation,
    EndpointDeclaration Endpoint, ProcessGeneration RecheckedGeneration,
    EndpointDeclaration RecheckedEndpoint, bool Complete);
internal sealed record BindingObservedFile(string Role, string AbsolutePath, long ByteLength,
    string Sha256, string VolumeId, string FileId, bool FixedLocalVolume, bool AncestorsVerified,
    bool RetainedHandle, bool ReparseFound, bool AliasFound, int LinkCount, bool Complete);
internal sealed record BindingObservedRuntime(string ProfileName, string Version, string ProfileSha256,
    ImmutableArray<string> DependencyPaths, bool Complete, bool UnexpectedEnvironment);
internal sealed record BindingObservedLaunch(string HostPath, string EntryPoint, string WorkingDirectory,
    ImmutableArray<string> Arguments, string Nonce, bool Complete);
internal sealed record BindingTerminal(DateTimeOffset Utc, long ElapsedTicks, bool Disposed,
    bool CleanupSucceeded, bool Cancelled, bool DeadlineSignaled, bool LateCompletion);
internal sealed record ObservationEnvelopeV1(int SchemaVersion, string PolicyProfile,
    BindingEvidenceSource Source, BindingByteIdentity ScopeIdentity, BindingByteIdentity DescriptorIdentity,
    string InvocationId, BindingOperation Operation, ProcessGeneration InvocationGeneration,
    DateTimeOffset StartedUtc, DateTimeOffset EndedUtc, long StartedElapsedTicks, long EndedElapsedTicks,
    BindingObservedWindows Windows, BindingObservedInstance Instance, EndpointDeclaration Endpoint,
    string ManifestSha256, ImmutableArray<BindingObservedFile> Inventory, BindingObservedRuntime Runtime,
    BindingObservedLaunch Launch, bool Complete, ImmutableArray<string> UnsupportedFields,
    ImmutableArray<string> MissingFields, BindingObservationFailure Failure, BindingTerminal Terminal);

// Externally fixed consistency anchor. Its provenance is NOT authenticated by this model.
internal sealed record BindingReviewAnchor(BindingByteIdentity ScopeIdentity,
    BindingByteIdentity DescriptorIdentity, string InvocationId, string Nonce, string ExternalReviewReferenceId);
internal sealed class BindingResult
{
    private BindingResult(BindingFailure failure) => Failure = failure;
    public BindingFailure Failure { get; }
    public bool Consistent => Failure == BindingFailure.None;
    public bool HumanApprovalAuthenticated => false;
    public bool LiveExecutionAuthorized => false;
    public bool SyntheticOnly => true;
    public string Diagnostic => "Synthetic binding: " + Failure + ".";
    internal static BindingResult Matched() => new(BindingFailure.None);
    internal static BindingResult Rejected(BindingFailure failure)
    {
        if (failure == BindingFailure.None || !Enum.IsDefined(failure))
            throw new ArgumentOutOfRangeException(nameof(failure));
        return new(failure);
    }
}
internal sealed class BindingException(BindingFailure failure) : Exception
{
    internal BindingFailure Failure { get; } = failure;
}
