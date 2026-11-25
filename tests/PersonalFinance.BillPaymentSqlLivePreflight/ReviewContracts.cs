using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal sealed record IdentityDeclaration(string Computer, string Account, string Sid);
internal sealed record TargetDeclaration(string RunId, string Database, string DataPath, string LogPath, string State);
internal sealed record EndpointDeclaration(string NamedPipe, string InternalServer);
internal sealed record ArtifactIdentity(string RelativePath, long ByteLength, string Sha256);
internal sealed record HostIdentity(string AbsolutePath, long ByteLength, string Sha256);
internal sealed record CommandDeclaration(string ExecutablePath, HostIdentity Host,
    string WorkingDirectory, ImmutableArray<string> Arguments);
internal sealed record SafetyDeclaration(bool WritesProhibited, bool FallbackProhibited,
    bool RetriesProhibited, bool AutomaticProgressionProhibited);
internal sealed record QuerySetIdentity(string Name, string Sha256);
internal sealed record ProposedReviewPacket(
    int SchemaVersion, string RequestId, string Operation, TargetDeclaration Target,
    string ManifestSha256, ImmutableArray<ArtifactIdentity> Artifacts, CommandDeclaration Command,
    IdentityDeclaration WindowsIdentity, IdentityDeclaration InstanceOwner,
    string InstanceName, string InstanceVersion, EndpointDeclaration? Endpoint,
    QuerySetIdentity QuerySet, DateTimeOffset IssuedUtc, DateTimeOffset NotBeforeUtc,
    DateTimeOffset ExpiresUtc, int ConnectTimeoutSeconds, int CommandTimeoutSeconds,
    int OverallTimeoutSeconds, SafetyDeclaration Safety);
internal sealed record ReviewedConsistencyBaseline(string RequestId, string RunId,
    string PacketSha256, string ManifestSha256, ImmutableArray<ArtifactIdentity> Artifacts,
    HostIdentity Host, EndpointDeclaration? Endpoint, QuerySetIdentity QuerySet);
internal sealed record ObservedConsistency(string ManifestSha256,
    ImmutableArray<ArtifactIdentity> Artifacts, HostIdentity Host,
    EndpointDeclaration? Endpoint, QuerySetIdentity QuerySet);
internal enum ReviewFailure
{
    None, MalformedPacket, InvalidScope, WrongTarget, InvalidIdentity, InvalidEndpoint,
    InvalidArtifacts, CommandMismatch, InvalidWindow, BaselineMismatch, ObservationMismatch
}
internal sealed record ConsistencyResult(bool Consistent, ReviewFailure Failure,
    string Diagnostic, bool HumanApprovalAuthenticated, bool LiveExecutionAuthorized);

internal sealed class ReviewPacketException(ReviewFailure failure) : Exception
{
    internal ReviewFailure Failure { get; } = failure;
}
