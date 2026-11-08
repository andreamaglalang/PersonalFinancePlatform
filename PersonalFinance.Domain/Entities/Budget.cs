using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class Budget
{
    public int BudgetId { get; set; }
    public int UserId { get; set; }
    public int CategoryId { get; set; }
    public Category Category { get; set; } = null!;
    public DateOnly PeriodMonth { get; set; }
    public decimal LimitAmount { get; set; }
    public byte AlertThresholdPct { get; set; }
    public BudgetAlertStatus? LastAlertStatus { get; set; }
    public DateTime? LastAlertAtUtc { get; set; }
}

