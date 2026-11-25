using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlPreflight;

internal sealed record IdentityDeclaration(string Computer, string Account, string Sid);
internal sealed record EndpointDeclaration(string Pipe, string InternalServer);
internal sealed record ExpectedDatabaseTarget(string RunId, string Name, string DataPath, string LogPath, string State);
internal sealed record ArtifactDeclaration(string ManifestSha256, string ExecutableName, string ExecutableSha256);
internal sealed record ApprovedPreflightScope(
    ExpectedDatabaseTarget Target, IdentityDeclaration WindowsOwner, IdentityDeclaration InstanceOwner,
    string Instance, string Version, EndpointDeclaration Endpoint, ArtifactDeclaration Artifacts,
    string Phase, DateTimeOffset IssuedUtc, DateTimeOffset NotBeforeUtc, DateTimeOffset ExpiresUtc,
    int TimeoutMilliseconds, ImmutableArray<string> MigrationIds);
internal sealed record OfflineApprovalObservation(
    ApprovedPreflightScope Scope, string ProvenanceKind, string ApprovalId);
internal sealed record PreflightRequest(
    string Mode, ApprovedPreflightScope Scope, OfflineApprovalObservation? Approval,
    ArtifactDeclaration ObservedArtifacts);
internal sealed record WindowsIdentityObservation(string Computer, string Account, string Sid, bool Impersonated);
internal sealed record NamedPipeObservation(string Pipe, string InternalServer);
internal sealed record LocalDbObservation(
    string Instance, string OwnerAccount, string OwnerSid, string Version,
    bool Running, bool Shared, NamedPipeObservation Endpoint);
internal sealed record FilesystemObservation(
    string DataPath, string LogPath, bool Complete, bool ExistingAncestorsChecked,
    bool FixedLocalVolume, bool ReparseFound, bool AliasFound,
    bool DataFileExists, bool LogFileExists, bool AncestorPhysicalIdentityEstablished);
internal sealed record TargetAbsenceEvidence(string DatabaseName, bool Exists, bool CompleteVisibility);
internal sealed record SqlMetadataObservation(
    string InternalServer, string Version, bool IsLocalDb, string DatabaseContext,
    string Login, string OriginalLogin, string LoginSid, bool WindowsAuthentication,
    string NetworkTransport, bool CanInspectTargetMetadata, int TransactionCount,
    int TransactionState, TargetAbsenceEvidence Target, ImmutableArray<string> TargetMigrationIds);
internal enum PreflightFailure
{
    None, PolicyRejected, ApprovalRejected, ArtifactMismatch, WindowsMismatch,
    InstanceMismatch, EndpointMismatch, FilesystemMismatch, SqlIdentityMismatch,
    VisibilityInsufficient, TargetExists, ContradictoryEvidence, Cancelled, TimedOut, ObservationFailed
}
internal sealed record PreflightResult(
    bool Succeeded, PreflightFailure Failure, string Diagnostic, bool OfflineSimulationOnly);

internal interface IWindowsIdentityObserver
{
    Task<WindowsIdentityObservation> ObserveAsync(CancellationToken cancellationToken);
}
internal interface ILocalDbObserver
{
    Task<LocalDbObservation> ObserveAsync(CancellationToken cancellationToken);
}
internal interface IFilesystemObserver
{
    Task<FilesystemObservation> ObserveAsync(ExpectedDatabaseTarget target, CancellationToken cancellationToken);
}
internal interface IReadOnlyMetadataProbe
{
    Task<SqlMetadataObservation> ObserveAsync(EndpointDeclaration endpoint,
        ExpectedDatabaseTarget target, CancellationToken cancellationToken);
}
