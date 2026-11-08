namespace PersonalFinance.Domain.Entities;

/// <summary>Retained request evidence, including after the contribution is deleted.</summary>
public sealed class GoalContributionRequest
{
    public long RequestId { get; set; }
    public int UserId { get; set; }
    /// <summary>Null is reserved for separately approved legacy enrollment.</summary>
    public Guid? ClientId { get; set; }
    public int GoalId { get; set; }
    /// <summary>Historical identifier, deliberately not a foreign key to the deletable contribution.</summary>
    public int ContributionId { get; set; }
    public decimal OriginalAmount { get; set; }
    public DateOnly OriginalContributedOn { get; set; }
    public string? OriginalNote { get; set; }
    public short CanonicalVersion { get; set; } = 1;
    public DateTime RecordedAtUtc { get; set; }
    public DateTime? DeletedAtUtc { get; set; }
}
