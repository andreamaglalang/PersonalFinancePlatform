using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.BillPaymentSqlTests;

public static class BillSqlTargetPolicy
{
    public const int MaximumManifestBytes = 65_536;
    public const int MaximumDepth = 8;
    public const string OfflinePhase = "validate-offline";

    private const string HistoricalInstanceDeclaration = @"(localdb)\MSSQLLocalDB";
    private const string HistoricalComputerDeclaration = "OWLI-FIXTURE";
    private const string HistoricalAccountDeclaration = @"OWLI-FIXTURE\developer";
    private const string HistoricalSidDeclaration = "S-1-5-21-111111111-222222222-333333333-1001";
    private const string HistoricalVersionDeclaration = "17.0.4025.3";
    private const string ProposedTestRoot = @"D:\PersonalFinanceBillPaymentSqlTests";

    private static readonly string[] ExpectedMigrations =
    [
        "20261031023901_InitialFinanceSchema",
        "20261031115500_AddCategoryNameUniqueness",
        "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions",
        "20261107054048_AddBillPaymentReceiptsAndGuards"
    ];

    public static BillSqlTargetManifest ParseAndValidate(ReadOnlyMemory<byte> bytes)
    {
        Require(bytes.Length is > 0 and <= MaximumManifestBytes, "Manifest size is invalid.");
        try
        {
            using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions
            {
                MaxDepth = MaximumDepth,
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow
            });
            Require(document.RootElement.ValueKind == JsonValueKind.Object, "Manifest must be an object.");
            RejectDuplicates(document.RootElement);
            var manifest = JsonSerializer.Deserialize<BillSqlTargetManifest>(bytes.Span, new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
                PropertyNameCaseInsensitive = false,
                UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
                NumberHandling = JsonNumberHandling.Strict,
                RespectNullableAnnotations = true,
                MaxDepth = MaximumDepth
            });
            Require(manifest is not null, "Manifest is required.");
            Validate(manifest!);
            return manifest!;
        }
        catch (JsonException)
        {
            throw new BillSqlPolicyException("Manifest JSON structure or field types are invalid.");
        }
    }

    public static void Validate(BillSqlTargetManifest manifest)
    {
        if (manifest is null)
            throw new BillSqlPolicyException("Manifest is required.");
        Require(manifest.SchemaVersion == 1, "Unsupported manifest version.");
        Require(Guid.TryParseExact(manifest.RunId, "D", out var runId) && runId != Guid.Empty
            && string.Equals(manifest.RunId, runId.ToString("D"), StringComparison.Ordinal),
            "Run ID must be a nonempty canonical lowercase GUID.");
        Exact(manifest.SqlServerInstance, HistoricalInstanceDeclaration, "Instance declaration is not allowed.");
        Require(manifest.Owner is not null, "Owner declaration is required.");
        Exact(manifest.Owner!.ComputerName, HistoricalComputerDeclaration, "Computer declaration is not allowed.");
        Exact(manifest.Owner.AccountName, HistoricalAccountDeclaration, "Account declaration is not allowed.");
        Exact(manifest.Owner.Sid, HistoricalSidDeclaration, "SID declaration is not allowed.");
        Exact(manifest.ExpectedSqlServerVersion, HistoricalVersionDeclaration, "Version declaration is not allowed.");
        Require(!string.Equals(manifest.DatabaseName, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase),
            "The application database is prohibited.");
        var database = "PersonalFinanceBillPaymentTests_" + runId.ToString("N");
        Exact(manifest.DatabaseName, database, "Database must match the exact run-bound name.");
        var directory = ProposedTestRoot + "\\" + runId.ToString("D");
        Exact(manifest.DataPath, directory + "\\" + database + ".mdf", "Data path must match this run.");
        Exact(manifest.LogPath, directory + "\\" + database + "_log.ldf", "Log path must match this run.");
        Exact(manifest.DatabaseState, "unverified-target", "Only an unverified target declaration is allowed.");
        Require(manifest.ConnectionPolicy is not null, "Connection policy is required.");
        var policy = manifest.ConnectionPolicy!;
        Exact(policy.Authentication, "WindowsIntegrated", "Authentication declaration is not allowed.");
        Exact(policy.Transport, "VerifiedExistingNamedPipe", "Transport declaration is not allowed.");
        Require(policy.Encrypt && policy.TrustServerCertificate, "The declared local encryption policy is required.");
        Require(!policy.Pooling && !policy.Enlist && !policy.MultipleActiveResultSets,
            "Pooling, enlistment and MARS are prohibited.");
        Require(!policy.AllowAttachFile && !policy.AllowFallback && !policy.AllowFailover
            && !policy.AllowImplicitLocalDbStartup, "Attach, fallback, failover and implicit startup are prohibited.");
        Require(policy.ConnectTimeoutSeconds == 10 && policy.CommandTimeoutSeconds == 10
            && policy.ConnectRetryCount == 0, "Timeout or retry declaration is not allowed.");
        Require(manifest.AllowedPhases is { Length: 1 }
            && string.Equals(manifest.AllowedPhases[0], OfflinePhase, StringComparison.Ordinal),
            "Only validate-offline is allowed.");
        Require(manifest.MigrationIds is not null
            && manifest.MigrationIds.SequenceEqual(ExpectedMigrations, StringComparer.Ordinal),
            "The exact four migration declarations are required.");
    }

    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                Require(names.Add(property.Name), "Duplicate JSON properties are prohibited.");
                RejectDuplicates(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray()) RejectDuplicates(item);
        }
    }

    private static void Exact(string? actual, string expected, string message) =>
        Require(string.Equals(actual, expected, StringComparison.Ordinal), message);

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new BillSqlPolicyException(message);
    }
}

public sealed class BillSqlPolicyException(string message) : Exception(message)
{
}
