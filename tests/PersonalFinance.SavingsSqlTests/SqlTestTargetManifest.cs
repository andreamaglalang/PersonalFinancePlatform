using System.Text.Json.Serialization;

namespace PersonalFinance.SavingsSqlTests;

// An offline declaration, never a live-operation authorization or verified database identity.
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class SqlTestTargetManifest
{
    public required int SchemaVersion { get; init; }
    public required string RunId { get; init; }
    public required string SqlServerInstance { get; init; }
    public required SqlTestOwnerManifest Owner { get; init; }
    public required string ExpectedSqlServerVersion { get; init; }
    public required string DatabaseName { get; init; }
    public required string DataPath { get; init; }
    public required string LogPath { get; init; }
    public required SqlTestConnectionPolicy ConnectionPolicy { get; init; }
    public required string[] AllowedPhases { get; init; }
    public required string[] MigrationIds { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class SqlTestOwnerManifest
{
    public required string ComputerName { get; init; }
    public required string AccountName { get; init; }
    public required string Sid { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class SqlTestConnectionPolicy
{
    public required string Authentication { get; init; }
    public required string Transport { get; init; }
    public required bool Encrypt { get; init; }
    public required bool TrustServerCertificate { get; init; }
    public required bool Pooling { get; init; }
    public required bool Enlist { get; init; }
    public required bool MultipleActiveResultSets { get; init; }
    public required bool AllowAttachFile { get; init; }
    public required bool AllowFailover { get; init; }
    public required bool AllowFallback { get; init; }
    public required bool AllowImplicitLocalDbStartup { get; init; }
    public required int ConnectTimeoutSeconds { get; init; }
    public required int CommandTimeoutSeconds { get; init; }
    public required int ConnectRetryCount { get; init; }
}
