using System.Collections.Immutable;
using System.Text.RegularExpressions;

namespace PersonalFinance.BillPaymentSqlPreflight;

internal static class PreflightPolicy
{
    internal const string HistoricalComputer = "OWLI-FIXTURE";
    internal const string HistoricalAccount = @"OWLI-FIXTURE\developer";
    internal const string HistoricalSid = "S-1-5-21-111111111-222222222-333333333-1001";
    internal const string HistoricalInstance = "MSSQLLocalDB";
    internal const string HistoricalVersion = "17.0.4025.3";
    internal const string ExecutableName = "PersonalFinance.BillPaymentSqlPreflight.dll";
    internal static readonly ImmutableArray<string> Migrations =
    [
        "20261031023901_InitialFinanceSchema",
        "20261031115500_AddCategoryNameUniqueness",
        "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions",
        "20261107054048_AddBillPaymentReceiptsAndGuards"
    ];

    internal static void Validate(PreflightRequest request, DateTimeOffset now)
    {
        Need(request is not null && request.Scope is not null, PreflightFailure.PolicyRejected);
        var scope = request.Scope;
        Need(request.Mode == "simulate-read-only-preflight"
            && scope.Phase == "live-read-only-preflight", PreflightFailure.PolicyRejected);
        Need(scope.Target is not null && scope.WindowsOwner is not null && scope.InstanceOwner is not null
            && scope.Endpoint is not null && scope.Artifacts is not null, PreflightFailure.PolicyRejected);
        var target = scope.Target;
        Need(Guid.TryParseExact(target.RunId, "D", out var run) && run != Guid.Empty
            && target.RunId == run.ToString("D"), PreflightFailure.PolicyRejected);
        var database = "PersonalFinanceBillPaymentTests_" + run.ToString("N");
        var directory = @"D:\PersonalFinanceBillPaymentSqlTests\" + run.ToString("D");
        Need(!string.Equals(target.Name, "PersonalFinanceDb", StringComparison.OrdinalIgnoreCase)
            && target.Name == database && target.State == "absent-target"
            && target.DataPath == directory + "\\" + database + ".mdf"
            && target.LogPath == directory + "\\" + database + "_log.ldf", PreflightFailure.PolicyRejected);
        var historicalOwner = new IdentityDeclaration(HistoricalComputer, HistoricalAccount, HistoricalSid);
        Need(scope.WindowsOwner == historicalOwner && scope.InstanceOwner == historicalOwner
            && scope.Instance == HistoricalInstance && scope.Version == HistoricalVersion, PreflightFailure.PolicyRejected);
        var pipe = Regex.Match(scope.Endpoint.Pipe ?? "",
            @"\Anp:\\\\\.\\pipe\\LOCALDB#([A-Za-z0-9]{1,64})\\tsql\\query\z",
            RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
        Need(pipe.Success && scope.Endpoint.InternalServer == HistoricalComputer + @"\LOCALDB#" + pipe.Groups[1].Value,
            PreflightFailure.PolicyRejected);
        Need(IsHash(scope.Artifacts.ManifestSha256) && IsHash(scope.Artifacts.ExecutableSha256)
            && scope.Artifacts.ExecutableName == ExecutableName, PreflightFailure.PolicyRejected);
        Need(!scope.MigrationIds.IsDefault && scope.MigrationIds.SequenceEqual(Migrations, StringComparer.Ordinal),
            PreflightFailure.PolicyRejected);
        Need(now.Offset == TimeSpan.Zero && scope.IssuedUtc.Offset == TimeSpan.Zero
            && scope.NotBeforeUtc.Offset == TimeSpan.Zero && scope.ExpiresUtc.Offset == TimeSpan.Zero
            && scope.IssuedUtc <= scope.NotBeforeUtc && scope.NotBeforeUtc <= now && now < scope.ExpiresUtc
            && scope.ExpiresUtc - scope.IssuedUtc <= TimeSpan.FromHours(1)
            && scope.TimeoutMilliseconds is >= 10 and <= 10_000, PreflightFailure.PolicyRejected);
        var approval = request.Approval;
        Need(approval is not null && approval.ProvenanceKind == "offline-test-simulation"
            && Guid.TryParseExact(approval.ApprovalId, "D", out var approvalId) && approvalId != Guid.Empty,
            PreflightFailure.ApprovalRejected);
        Need(SameScope(approval!.Scope, scope), PreflightFailure.ApprovalRejected);
        Need(request.ObservedArtifacts == scope.Artifacts, PreflightFailure.ArtifactMismatch);
    }

    private static bool SameScope(ApprovedPreflightScope a, ApprovedPreflightScope b) =>
        a is not null && a.Target == b.Target && a.WindowsOwner == b.WindowsOwner
        && a.InstanceOwner == b.InstanceOwner && a.Instance == b.Instance && a.Version == b.Version
        && a.Endpoint == b.Endpoint && a.Artifacts == b.Artifacts && a.Phase == b.Phase
        && a.IssuedUtc == b.IssuedUtc && a.NotBeforeUtc == b.NotBeforeUtc && a.ExpiresUtc == b.ExpiresUtc
        && a.TimeoutMilliseconds == b.TimeoutMilliseconds && !a.MigrationIds.IsDefault
        && a.MigrationIds.SequenceEqual(b.MigrationIds, StringComparer.Ordinal);

    private static bool IsHash(string value) => value is { Length: 64 }
        && value.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F');

    internal static void Need([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition,
        PreflightFailure failure)
    {
        if (!condition) throw new PreflightRejection(failure);
    }
}

internal sealed class PreflightRejection(PreflightFailure failure) : Exception
{
    internal PreflightFailure Failure { get; } = failure;
}
