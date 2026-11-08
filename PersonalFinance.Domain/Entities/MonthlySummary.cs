using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class MonthlySummary
{
    public int SummaryId { get; set; }
    public int UserId { get; set; }
    public DateOnly SummaryMonth { get; set; }
    public decimal TotalIncome { get; set; }
    public decimal TotalExpense { get; set; }
    public decimal NetCashFlow { get; set; }
    public decimal SavingsAllocated { get; set; }
    public DateTime GeneratedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime? EmailSentAtUtc { get; set; }
}

