using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateSavingsGoalRequest : UpdateSavingsGoalRequest { }

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class UpdateSavingsGoalRequest
{
    [Required, StringLength(100)]
    public string Name { get; set; } = string.Empty;
    [Required]
    public decimal? TargetAmount { get; set; }
    public DateOnly? TargetDate { get; set; }
}

public sealed class SavingsGoalQuery
{
    public string? Status { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateGoalContributionRequest : UpdateGoalContributionRequest
{
    [Required]
    public Guid? ClientId { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class UpdateGoalContributionRequest
{
    [Required]
    public decimal? Amount { get; set; }
    [Required]
    public DateOnly? ContributedOn { get; set; }
    [StringLength(200)]
    public string? Note { get; set; }
}

public sealed class GoalContributionQuery
{
    public DateOnly? FromDate { get; set; }
    public DateOnly? ToDate { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
}

// Version is the unquoted base64 encoding of SQL Server's eight-byte rowversion.
// The later controller layer is responsible for ETag quoting and If-Match header parsing.
public sealed record SavingsGoalResponse(int GoalId, string Name, decimal TargetAmount, DateOnly? TargetDate,
    string Status, decimal SavedAmount, decimal RemainingAmount, decimal SurplusAmount,
    decimal ProgressPercentage, string BaseCurrency, string Version);

public sealed record GoalContributionResponse(int ContributionId, int GoalId, decimal Amount,
    DateOnly ContributedOn, string? Note, string BaseCurrency, string Version);

public sealed record GoalContributionReceipt(long RequestId, Guid ClientId, int GoalId, int ContributionId,
    decimal OriginalAmount, DateOnly OriginalContributedOn, string? OriginalNote, short CanonicalVersion,
    DateTime RecordedAtUtc, DateTime? DeletedAtUtc, string BaseCurrency);

public sealed record ContributionCreateResponse(GoalContributionReceipt Receipt, bool Replayed, bool Deleted,
    GoalContributionResponse? CurrentContribution, SavingsGoalResponse Goal);

public sealed record GoalContributionMutationResponse(GoalContributionResponse Contribution, SavingsGoalResponse Goal);

public sealed record ContributionDeletionResponse(int ContributionId, bool Deleted, SavingsGoalResponse Goal);
