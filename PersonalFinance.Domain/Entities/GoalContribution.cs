using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class GoalContribution
{
    public int ContributionId { get; set; }
    public int GoalId { get; set; }
    public SavingsGoal Goal { get; set; } = null!;
    public decimal Amount { get; set; }
    public DateOnly ContributedOn { get; set; }
    public string? Note { get; set; }
    public byte[] RowVersion { get; set; } = [];
}

