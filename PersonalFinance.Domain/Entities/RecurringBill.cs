
using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class RecurringBill
{
    public int BillId { get; set; }
    public int UserId { get; set; }
    public int CategoryId { get; set; }
    public Category Category { get; set; } = null!;
    public string Name { get; set; } = string.Empty;
    public decimal Amount { get; set; }
    public string CurrencyCode { get; set; } = string.Empty;
    public BillFrequency Frequency { get; set; }
    public DateOnly AnchorDate { get; set; }
    public DateOnly NextDueDate { get; set; }
    /// <summary>Original recurrence day, retained when a shorter month clamps the due date. Null for weekly bills.</summary>
    public byte? IntendedDayOfMonth { get; set; }
    public byte ReminderDaysBefore { get; set; }
    public bool IsActive { get; set; } = true;
    public byte[] RowVersion { get; set; } = [];
}
