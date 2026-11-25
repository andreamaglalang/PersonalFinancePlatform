using System.Collections.Immutable;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.SavingsSqlLiveTests;

// Pure syntax/declaration consistency checks. This class does NOT authenticate an approver,
// verify a signature, inspect a target/artifact, grant authority or perform filesystem/network I/O.
public static class OfflineAuthorizationPolicy
{
    public const int MaximumBytes = 65_536;
    public const string ExecutableName = "PersonalFinance.SavingsSqlLiveTests.dll";
    private static readonly ImmutableArray<string> Migrations =
    [
        "20261031023901_InitialFinanceSchema",
        "20261031115500_AddCategoryNameUniqueness",
        "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions"
    ];

    public static PhaseAuthorizationDeclaration ParseAndValidate(ReadOnlyMemory<byte> bytes, DateTimeOffset nowUtc)
    {
        Require(bytes.Length is > 0 and <= MaximumBytes, "Policy size is invalid.");
        Require(nowUtc.Offset == TimeSpan.Zero, "Validation time must be UTC.");
        using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions
        {
            MaxDepth = 12, AllowTrailingCommas = false, CommentHandling = JsonCommentHandling.Disallow
        });
        Require(document.RootElement.ValueKind == JsonValueKind.Object, "Policy must be an object.");
        RejectDuplicates(document.RootElement);
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = false,
            UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
            NumberHandling = JsonNumberHandling.Strict,
            RespectRequiredConstructorParameters = true,
            RespectNullableAnnotations = true,
            MaxDepth = 12
        };
        options.Converters.Add(new JsonStringEnumConverter<DeclaredPhase>(JsonNamingPolicy.KebabCaseLower, false));
        options.Converters.Add(new JsonStringEnumConverter<DeclaredStartingState>(JsonNamingPolicy.KebabCaseLower, false));
        var record = JsonSerializer.Deserialize<PhaseAuthorizationDeclaration>(bytes.Span, options)
            ?? throw new PolicyDeclarationException("Policy is required.");
        Validate(record, nowUtc);
        // Enum converters can accept case variants; require the exact canonical JSON spellings too.
        Require(document.RootElement.GetProperty("phase").GetString() == WirePhase(record.Phase), "Phase spelling is invalid.");
        Require(document.RootElement.GetProperty("expectedStartingState").GetProperty("state").GetString()
            == JsonNamingPolicy.KebabCaseLower.ConvertName(record.ExpectedStartingState.State.ToString()),
            "Starting-state spelling is invalid.");
        return record; // Deliberately returns a declaration, never an execution-capability object.
    }

    private static void Validate(PhaseAuthorizationDeclaration record, DateTimeOffset nowUtc)
    {
        Require(record.SchemaVersion == 1, "Unsupported policy schema version.");
        Require(Enum.IsDefined(record.Phase) && record.RunId != Guid.Empty, "Invalid phase or run GUID.");
        Hash(record.ManifestSha256);
        var target = record.ApprovedTargetIdentity;
        Require(target is not null, "Target declaration is required.");
        Require(target!.RunId == record.RunId, "Target run GUID does not match the policy.");
        Exact(target.SqlServerInstance, @"(localdb)\MSSQLLocalDB", "Instance is not allowed.");
        Identity(target.WindowsOwner);
        Identity(target.LocalDbOwner);
        Exact(target.ExpectedSqlServerVersion, "17.0.4025.3", "Server version is not allowed.");
        var database = "PersonalFinanceSavingsTests_" + record.RunId.ToString("N");
        Require(!string.Equals(target.DatabaseName, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase),
            "The development database is prohibited.");
        Exact(target.DatabaseName, database, "Database must exactly match this run's disposable name.");
        var directory = @"D:\PersonalFinanceSavingsSqlTests\" + record.RunId.ToString("D");
        Exact(target.ApprovedDataPath, directory + "\\" + database + ".mdf", "Data path is not allowed.");
        Exact(target.ApprovedLogPath, directory + "\\" + database + "_log.ldf", "Log path is not allowed.");

        Require(!record.ReviewedArtifacts.IsDefault && record.ReviewedArtifacts.Length == 3,
            "Exactly three reviewed artifact declarations are required.");
        (string Role, string Name)[] expectedArtifacts =
        [
            ("companion-executable", ExecutableName),
            ("infrastructure", "PersonalFinance.Infrastructure.dll"),
            ("domain", "PersonalFinance.Domain.dll")
        ];
        foreach (var artifact in record.ReviewedArtifacts)
        {
            Require(artifact is not null, "Null artifact declarations are prohibited.");
            Hash(artifact!.Sha256);
        }
        foreach (var expected in expectedArtifacts)
        {
            var matches = record.ReviewedArtifacts.Where(a => a.Role == expected.Role).ToArray();
            Require(matches.Length == 1 && matches[0].FileName == expected.Name,
                "Artifact roles and filenames must exactly match the reviewed set.");
        }
        var executableHash = record.ReviewedArtifacts.Single(a => a.Role == "companion-executable").Sha256;

        Require(record.Expiration is not null, "Expiration metadata is required.");
        var expiration = record.Expiration!;
        Require(expiration.IssuedAtUtc.Offset == TimeSpan.Zero && expiration.NotBeforeUtc.Offset == TimeSpan.Zero
            && expiration.ExpiresAtUtc.Offset == TimeSpan.Zero
            && expiration.IssuedAtUtc <= expiration.NotBeforeUtc && expiration.NotBeforeUtc < expiration.ExpiresAtUtc
            && expiration.ExpiresAtUtc - expiration.IssuedAtUtc <= TimeSpan.FromHours(1)
            && expiration.IssuedAtUtc <= nowUtc && expiration.NotBeforeUtc <= nowUtc && nowUtc < expiration.ExpiresAtUtc,
            "Expiration metadata is invalid, expired or outside the proposed one-hour policy window.");

        Require(record.ExpectedStartingState is not null, "Starting-state declaration is required.");
        var start = record.ExpectedStartingState!;
        Require(Enum.IsDefined(start.State) && !start.MigrationIds.IsDefault, "Starting state is invalid.");
        Prefix(start.MigrationIds);
        Require(start.MigrationIds.Length <= Migrations.Length, "Migration prefix is invalid.");

        if (target.FrozenServer is not null) FrozenServer(target.FrozenServer);
        if (target.Database is not null)
            BoundDatabase(target.Database, target, record.ManifestSha256, executableHash, start.MigrationIds,
                expiration.IssuedAtUtc);

        // No phase implies the next. Preconditions are separate, exact declarations, not live observations.
        switch (record.Phase)
        {
            case DeclaredPhase.OfflineValidation:
            case DeclaredPhase.LiveReadOnlyPreflight:
                Require(start.State == DeclaredStartingState.UnverifiedTarget && start.MigrationIds.Length == 0
                    && target.Database is null, "Preflight/offline phase requires an unverified, unbound target.");
                break;
            case DeclaredPhase.ProvisionDatabase:
                Require(start.State == DeclaredStartingState.AbsentDatabase && start.MigrationIds.Length == 0
                    && target.FrozenServer is not null && target.Database is null,
                    "Provisioning declaration requires frozen-server evidence and an absent, unbound database.");
                break;
            case DeclaredPhase.ApplyBoundedMigrations:
                Require(target.FrozenServer is not null && target.Database is not null
                    && ((start.State == DeclaredStartingState.BoundEmptyDatabase && start.MigrationIds.Length == 0)
                        || (start.State == DeclaredStartingState.MigrationPrefix && start.MigrationIds.Length is > 0 and < 3)),
                    "Migration declaration requires a bound database and an exact incomplete migration prefix.");
                break;
            case DeclaredPhase.RunDeterministicTests:
            case DeclaredPhase.RunCoordinatedConcurrencyTests:
                var expectedState = record.Phase == DeclaredPhase.RunDeterministicTests
                    ? DeclaredStartingState.ReadyForDeterministicTests : DeclaredStartingState.ReadyForConcurrencyTests;
                Require(target.FrozenServer is not null && target.Database is not null
                    && start.State == expectedState && start.MigrationIds.Length == 3,
                    "Test declaration requires its own ready state and all three migrations.");
                break;
            case DeclaredPhase.CleanupDatabase:
                Require(target.FrozenServer is not null && target.Database is not null
                    && start.State == DeclaredStartingState.RetainedDatabase,
                    "Cleanup declaration requires independently bound retained database evidence.");
                break;
            default: throw new PolicyDeclarationException("Unsupported phase.");
        }

        Require(record.ApprovedCommand is not null, "Exact command declaration is required.");
        var command = record.ApprovedCommand!;
        Exact(command.ExecutableFileName, ExecutableName, "Executable override is prohibited.");
        Exact(command.WorkingDirectory, @"C:\OWLI-Fixtures\PersonalFinancePlatform\artifacts\SavingsSqlLive\"
            + record.RunId.ToString("D"), "Working directory does not match the run.");
        string[] arguments = record.Phase == DeclaredPhase.OfflineValidation
            ? ["--mode", "validate-policy-offline", "--policy", "authorization.json"]
            : ["--mode", WirePhase(record.Phase), "--manifest", "manifest.json", "--authorization", "authorization.json"];
        Require(!command.Arguments.IsDefault && command.Arguments.SequenceEqual(arguments, StringComparer.Ordinal),
            "Unexpected command arguments, filenames, order or overrides.");
    }

    private static void BoundDatabase(BoundDatabaseDeclaration database, TargetIdentityDeclaration target,
        string manifestHash, string executableHash, ImmutableArray<string> startingMigrations, DateTimeOffset issuedAtUtc)
    {
        Require(database.DatabaseGuid != Guid.Empty && database.DatabaseId > 4 && database.RunMarker == target.RunId
            && database.CreatedAtUtc.Offset == TimeSpan.Zero && database.CreatedAtUtc > DateTimeOffset.MinValue
            && database.CreatedAtUtc <= issuedAtUtc,
            "Database identity, creation metadata or run marker is invalid.");
        Hash(database.ManifestSha256); Hash(database.ExecutableSha256);
        Require(database.ManifestSha256 == manifestHash && database.ExecutableSha256 == executableHash,
            "Bound fingerprints must match the record.");
        Prefix(database.MigrationIds);
        Require(database.MigrationIds.SequenceEqual(startingMigrations), "Bound migration state differs from expected state.");
        Require(!database.ActualFiles.IsDefault && database.ActualFiles.Length == 2
            && database.ActualFiles.All(f => f is not null), "Exactly two declared database files are required.");
        var data = database.ActualFiles.Where(f => f.Kind == "data").ToArray();
        var log = database.ActualFiles.Where(f => f.Kind == "log").ToArray();
        Require(data.Length == 1 && log.Length == 1 && data[0].FileId == 1 && log[0].FileId == 2,
            "File identities must match the proposed two-file provisioning layout.");
        Exact(data[0].LogicalName, target.DatabaseName, "Data logical name is invalid.");
        Exact(log[0].LogicalName, target.DatabaseName + "_log", "Log logical name is invalid.");
        Exact(data[0].PhysicalPath, target.ApprovedDataPath, "Actual data path differs from its declaration.");
        Exact(log[0].PhysicalPath, target.ApprovedLogPath, "Actual log path differs from its declaration.");
    }

    private static void FrozenServer(FrozenServerDeclaration server)
    {
        const string prefix = @"np:\\.\pipe\LOCALDB#";
        const string suffix = @"\tsql\query";
        Require(server.NamedPipe is not null && server.NamedPipe.StartsWith(prefix, StringComparison.Ordinal)
            && server.NamedPipe.EndsWith(suffix, StringComparison.Ordinal), "Frozen pipe declaration is invalid.");
        var instanceToken = server.NamedPipe![prefix.Length..^suffix.Length];
        Require(instanceToken.Length is >= 1 and <= 64 && instanceToken.All(char.IsAsciiLetterOrDigit),
            "Frozen LocalDB pipe token is invalid.");
        Exact(server.InternalSqlIdentity, @"OWLI-FIXTURE\LOCALDB#" + instanceToken,
            "Internal SQL identity does not match the declared pipe.");
    }

    private static void Identity(WindowsIdentityDeclaration identity)
    {
        Require(identity is not null, "Windows identity is required.");
        Exact(identity!.ComputerName, "OWLI-FIXTURE", "Computer is not approved.");
        Exact(identity.AccountName, @"OWLI-FIXTURE\developer", "Windows account is not approved.");
        Exact(identity.Sid, "S-1-5-21-111111111-222222222-333333333-1001", "Windows SID is not approved.");
    }

    private static void Prefix(ImmutableArray<string> values) => Require(!values.IsDefault
        && values.Length <= Migrations.Length && values.SequenceEqual(Migrations.Take(values.Length), StringComparer.Ordinal),
        "Only an exact prefix of the three approved migration IDs is permitted.");

    private static void Hash(string? value) => Require(value is { Length: 64 }
        && value.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F'), "SHA-256 must be 64 uppercase hexadecimal characters.");

    public static string WirePhase(DeclaredPhase phase) => phase switch
    {
        DeclaredPhase.OfflineValidation => "offline-validation",
        DeclaredPhase.LiveReadOnlyPreflight => "live-read-only-preflight",
        DeclaredPhase.ProvisionDatabase => "provision-database",
        DeclaredPhase.ApplyBoundedMigrations => "apply-bounded-migrations",
        DeclaredPhase.RunDeterministicTests => "run-deterministic-tests",
        DeclaredPhase.RunCoordinatedConcurrencyTests => "run-coordinated-concurrency-tests",
        DeclaredPhase.CleanupDatabase => "cleanup-database",
        _ => throw new PolicyDeclarationException("Unsupported phase.")
    };

    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                Require(names.Add(property.Name), "Duplicate properties are prohibited.");
                RejectDuplicates(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var value in element.EnumerateArray()) RejectDuplicates(value);
    }

    private static void Exact(string? actual, string expected, string message) =>
        Require(string.Equals(actual, expected, StringComparison.Ordinal), message);

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new PolicyDeclarationException(message);
    }
}

public sealed class PolicyDeclarationException(string message) : Exception(message) { }
