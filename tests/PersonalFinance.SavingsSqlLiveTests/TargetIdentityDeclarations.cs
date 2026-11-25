using System.Collections.Immutable;
using System.Text.Json.Serialization;

namespace PersonalFinance.SavingsSqlLiveTests;

// Immutable declarations, not observations, verified bindings, approval proofs or capabilities.
// No type implements ISavingsSqlVerifiedTarget. No SQL or production assembly is referenced.
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record WindowsIdentityDeclaration(string ComputerName, string AccountName, string Sid);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record FrozenServerDeclaration(string NamedPipe, string InternalSqlIdentity);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record DatabaseFileDeclaration(int FileId, string LogicalName, string Kind, string PhysicalPath);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record BoundDatabaseDeclaration(Guid DatabaseGuid, int DatabaseId, DateTimeOffset CreatedAtUtc,
    ImmutableArray<DatabaseFileDeclaration> ActualFiles, Guid RunMarker,
    ImmutableArray<string> MigrationIds, string ManifestSha256, string ExecutableSha256);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record TargetIdentityDeclaration(Guid RunId, string SqlServerInstance,
    WindowsIdentityDeclaration WindowsOwner, WindowsIdentityDeclaration LocalDbOwner,
    string ExpectedSqlServerVersion, string DatabaseName, string ApprovedDataPath, string ApprovedLogPath,
    FrozenServerDeclaration? FrozenServer, BoundDatabaseDeclaration? Database);

// Distinct future phase-capability declarations. The enum cannot be used to acquire or execute
// a capability: no capability factories, implementations or live dispatchers exist in this project.
public enum DeclaredPhase
{
    OfflineValidation,
    LiveReadOnlyPreflight,
    ProvisionDatabase,
    ApplyBoundedMigrations,
    RunDeterministicTests,
    RunCoordinatedConcurrencyTests,
    CleanupDatabase
}

// Separate capability contracts for future reviewed implementations. There are deliberately
// no implementers, acquisition APIs or conversions from a parsed declaration to these types.
internal interface IOfflineValidationCapability { }
internal interface ILiveReadOnlyPreflightCapability { }
internal interface IProvisionDatabaseCapability { }
internal interface IBoundedMigrationCapability { }
internal interface IDeterministicSqlTestsCapability { }
internal interface ICoordinatedConcurrencyCapability { }
internal interface ISeparatelyApprovedCleanupCapability { }

public enum DeclaredStartingState
{
    UnverifiedTarget,
    AbsentDatabase,
    BoundEmptyDatabase,
    MigrationPrefix,
    ReadyForDeterministicTests,
    ReadyForConcurrencyTests,
    RetainedDatabase
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ArtifactFingerprintDeclaration(string Role, string FileName, string Sha256);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record StartingStateDeclaration(DeclaredStartingState State, ImmutableArray<string> MigrationIds);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ExpirationDeclaration(DateTimeOffset IssuedAtUtc, DateTimeOffset NotBeforeUtc,
    DateTimeOffset ExpiresAtUtc);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record CommandDeclaration(string ExecutableFileName, string WorkingDirectory,
    ImmutableArray<string> Arguments);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record PhaseAuthorizationDeclaration(int SchemaVersion, DeclaredPhase Phase, Guid RunId,
    string ManifestSha256, TargetIdentityDeclaration ApprovedTargetIdentity,
    ImmutableArray<ArtifactFingerprintDeclaration> ReviewedArtifacts,
    StartingStateDeclaration ExpectedStartingState, ExpirationDeclaration Expiration,
    CommandDeclaration ApprovedCommand);
