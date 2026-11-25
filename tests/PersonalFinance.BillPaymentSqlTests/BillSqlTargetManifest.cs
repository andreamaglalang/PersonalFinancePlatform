namespace PersonalFinance.BillPaymentSqlTests;

public sealed record BillSqlTargetManifest
{
    public required int SchemaVersion { get; init; }
    public required string RunId { get; init; }
    public required string SqlServerInstance { get; init; }
    public required BillSqlOwnerDeclaration Owner { get; init; }
    public required string ExpectedSqlServerVersion { get; init; }
    public required string DatabaseName { get; init; }
    public required string DataPath { get; init; }
    public required string LogPath { get; init; }
    public required string DatabaseState { get; init; }
    public required BillSqlConnectionPolicy ConnectionPolicy { get; init; }
    public required string[] AllowedPhases { get; init; }
    public required string[] MigrationIds { get; init; }
}

public sealed record BillSqlOwnerDeclaration
{
    public required string ComputerName { get; init; }
    public required string AccountName { get; init; }
    public required string Sid { get; init; }
}

public sealed record BillSqlConnectionPolicy
{
    public required string Authentication { get; init; }
    public required string Transport { get; init; }
    public required bool Encrypt { get; init; }
    public required bool TrustServerCertificate { get; init; }
    public required bool Pooling { get; init; }
    public required bool Enlist { get; init; }
    public required bool MultipleActiveResultSets { get; init; }
    public required bool AllowAttachFile { get; init; }
    public required bool AllowFallback { get; init; }
    public required bool AllowFailover { get; init; }
    public required bool AllowImplicitLocalDbStartup { get; init; }
    public required int ConnectTimeoutSeconds { get; init; }
    public required int CommandTimeoutSeconds { get; init; }
    public required int ConnectRetryCount { get; init; }
}
