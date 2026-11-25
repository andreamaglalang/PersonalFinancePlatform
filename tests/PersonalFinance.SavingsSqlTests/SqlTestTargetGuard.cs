using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.SavingsSqlTests;

public static class SqlTestTargetGuard
{
    public const int MaximumManifestBytes = 65_536;
    public const string OfflinePhase = "validate-offline";

    // Independently pinned policy; values in a manifest cannot authorize another instance/owner.
    private const string ApprovedInstance = @"(localdb)\MSSQLLocalDB";
    private const string ApprovedComputer = "OWLI-FIXTURE";
    private const string ApprovedAccount = @"OWLI-FIXTURE\developer";
    private const string ApprovedSid = "S-1-5-21-111111111-222222222-333333333-1001";
    private const string ApprovedVersion = "17.0.4025.3";
    private const string ProposedTestRoot = @"D:\PersonalFinanceSavingsSqlTests";

    private static readonly string[] ApprovedMigrations =
    [
        "20261031023901_InitialFinanceSchema",
        "20261031115500_AddCategoryNameUniqueness",
        "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions"
    ];

    public static SqlTestTargetManifest ParseAndValidate(ReadOnlyMemory<byte> utf8Json)
    {
        Require(utf8Json.Length is > 0 and <= MaximumManifestBytes, "Manifest size is invalid.");
        using var document = JsonDocument.Parse(utf8Json, new JsonDocumentOptions
        {
            AllowTrailingCommas = false,
            CommentHandling = JsonCommentHandling.Disallow,
            MaxDepth = 8
        });
        Require(document.RootElement.ValueKind == JsonValueKind.Object, "Manifest must be a JSON object.");
        RejectDuplicateProperties(document.RootElement);

        var manifest = JsonSerializer.Deserialize<SqlTestTargetManifest>(utf8Json.Span, new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = false,
            UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
            NumberHandling = JsonNumberHandling.Strict,
            MaxDepth = 8
        });
        Require(manifest is not null, "Manifest is required.");
        Validate(manifest!);
        return manifest!;
    }

    public static void Validate(SqlTestTargetManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        Require(manifest.SchemaVersion == 1, "Unsupported manifest schema version.");
        Require(Guid.TryParseExact(manifest.RunId, "D", out var runId)
            && runId != Guid.Empty
            && string.Equals(manifest.RunId, runId.ToString("D"), StringComparison.Ordinal),
            "Run ID must be a nonempty canonical lowercase D-format GUID.");

        Exact(manifest.SqlServerInstance, ApprovedInstance, "SQL Server instance is not allowed.");
        Require(manifest.Owner is not null, "Owner identity is required.");
        Exact(manifest.Owner!.ComputerName, ApprovedComputer, "Owner computer is not allowed.");
        Exact(manifest.Owner.AccountName, ApprovedAccount, "Owner account is not allowed.");
        Exact(manifest.Owner.Sid, ApprovedSid, "Owner SID is not allowed.");
        Exact(manifest.ExpectedSqlServerVersion, ApprovedVersion, "SQL Server version is not allowed.");

        Require(!string.Equals(manifest.DatabaseName, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase),
            "The development database is prohibited.");
        var expectedName = "PersonalFinanceSavingsTests_" + runId.ToString("N");
        Exact(manifest.DatabaseName, expectedName, "Database must exactly match the run-bound disposable name.");

        // Literal Windows path equality rejects relative paths, traversal, UNC/device aliases,
        // alternate streams and wildcards without probing or creating any directory.
        var runDirectory = ProposedTestRoot + "\\" + runId.ToString("D");
        Exact(manifest.DataPath, runDirectory + "\\" + expectedName + ".mdf", "Data path is not allowed.");
        Exact(manifest.LogPath, runDirectory + "\\" + expectedName + "_log.ldf", "Log path is not allowed.");

        var policy = manifest.ConnectionPolicy;
        Require(policy is not null, "Connection policy is required.");
        Exact(policy!.Authentication, "WindowsIntegrated", "Authentication method is not allowed.");
        Exact(policy.Transport, "VerifiedExistingNamedPipe", "Transport policy is not allowed.");
        Require(policy.Encrypt && policy.TrustServerCertificate,
            "The pinned LocalDB encryption policy is required.");
        Require(!policy.Pooling && !policy.Enlist && !policy.MultipleActiveResultSets,
            "Pooling, ambient enlistment and multiple active result sets are prohibited.");
        Require(!policy.AllowAttachFile && !policy.AllowFailover && !policy.AllowFallback
            && !policy.AllowImplicitLocalDbStartup,
            "Attach files, failover, fallback and implicit LocalDB startup are prohibited.");
        Require(policy.ConnectTimeoutSeconds == 10 && policy.CommandTimeoutSeconds == 10
            && policy.ConnectRetryCount == 0, "Timeout and retry policy is not allowed.");

        Require(manifest.AllowedPhases is { Length: 1 }
            && string.Equals(manifest.AllowedPhases[0], OfflinePhase, StringComparison.Ordinal),
            "Only the validate-offline phase is supported.");
        Require(manifest.MigrationIds is not null
            && manifest.MigrationIds.SequenceEqual(ApprovedMigrations, StringComparer.Ordinal),
            "The exact approved migration sequence is required.");
    }

    private static void RejectDuplicateProperties(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                Require(names.Add(property.Name), "Duplicate JSON properties are prohibited.");
                RejectDuplicateProperties(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray()) RejectDuplicateProperties(item);
        }
    }

    private static void Exact(string? actual, string expected, string message) =>
        Require(string.Equals(actual, expected, StringComparison.Ordinal), message);

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new ManifestPolicyException(message);
    }
}

public sealed class ManifestPolicyException(string message) : Exception(message)
{
}
