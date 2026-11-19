using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

/// <summary>Fields editable after creation. The schedule (frequency and first due date) is fixed at creation.</summary>
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class UpdateRecurringBillRequest
{
    [Required, StringLength(100)]
    public string Name { get; set; } = string.Empty;
    [Required, Range(1, int.MaxValue)]
    public int? CategoryId { get; set; }
    [Required]
    public decimal? Amount { get; set; }
    [Required, StringLength(3, MinimumLength = 3)]
    public string CurrencyCode { get; set; } = string.Empty;
    [Required, Range(0, 30)]
    public int? ReminderDaysBefore { get; set; }
    public bool? IsActive { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateRecurringBillRequest : UpdateRecurringBillRequest
{
    /// <summary>Weekly, Monthly, Quarterly or Yearly.</summary>
    [Required, StringLength(20)]
    public string Frequency { get; set; } = string.Empty;
    /// <summary>The first due date; later due dates follow the frequency from this date.</summary>
    [Required]
    public DateOnly? FirstDueDate { get; set; }
}

public sealed class RecurringBillQuery
{
    public bool IncludeInactive { get; set; }
    /// <summary>Only bills whose next due date is on or before this date (for "due soon" lists).</summary>
    public DateOnly? DueOnOrBefore { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
}

public sealed record RecurringBillResponse(int BillId, string Name, int CategoryId, string CategoryName,
    decimal Amount, string CurrencyCode, string Frequency, DateOnly FirstDueDate, DateOnly NextDueDate,
    int ReminderDaysBefore, bool IsActive);
