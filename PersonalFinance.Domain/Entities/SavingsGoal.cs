using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class SavingsGoal
{
    public int GoalId { get; set; }
    public int UserId { get; set; }
    public string Name { get; set; } = string.Empty;
    public decimal TargetAmount { get; set; }
    public DateOnly? TargetDate { get; set; }
    public SavingsGoalStatus Status { get; set; } = SavingsGoalStatus.Active;
    public byte[] RowVersion { get; set; } = [];
}

