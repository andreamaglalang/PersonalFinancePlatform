using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class Transaction
{
    public int TransactionId { get; set; }
    public int UserId { get; set; }
    public int CategoryId { get; set; }
    public Category Category { get; set; } = null!;
    public int? BillId { get; set; }
    public RecurringBill? Bill { get; set; }
    public DateOnly? BillDueDate { get; set; }
    public Guid ClientId { get; set; }
    public TransactionType Type { get; set; }
    public decimal Amount { get; set; }
    public string CurrencyCode { get; set; } = string.Empty;
    public decimal FxRate { get; set; }
    public DateOnly FxRateDate { get; set; }
    public decimal AmountBase { get; set; }
    public string? Description { get; set; }
    public DateOnly TransactionDate { get; set; }
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}

