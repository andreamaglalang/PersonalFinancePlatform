using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

/// <summary>Fields editable after creation. The schedule (frequency and first due date) is fixed at creation.</summary>
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class UpdateRecurringBillRequest
{
    [Required(ErrorMessage = "Enter a bill name."), StringLength(100, ErrorMessage = "Bill name must be 100 characters or fewer."),
     SingleLineText(ErrorMessage = "Bill names can't contain line breaks or control characters.")]
    public string Name { get; set; } = string.Empty;
    [Required(ErrorMessage = "Choose an expense category."), Range(1, int.MaxValue, ErrorMessage = "Choose an expense category.")]
    public int? CategoryId { get; set; }
    [Required(ErrorMessage = "Enter an amount.")]
    public decimal? Amount { get; set; }
    [Required(ErrorMessage = "Choose a currency."), RegularExpression("^[A-Z]{3}$", ErrorMessage = "Use a three-letter uppercase currency code, for example PHP.")]
    public string CurrencyCode { get; set; } = string.Empty;
    [Required(ErrorMessage = "Enter reminder days from 0 to 30."), Range(0, 30, ErrorMessage = "Enter reminder days from 0 to 30.")]
    public int? ReminderDaysBefore { get; set; }
    public bool? IsActive { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateRecurringBillRequest : UpdateRecurringBillRequest
{
    /// <summary>Weekly, Monthly, Quarterly or Yearly.</summary>
    [Required(ErrorMessage = "Choose how often the bill repeats."), StringLength(20, ErrorMessage = "Frequency must be Weekly, Monthly, Quarterly or Yearly.")]
    public string Frequency { get; set; } = string.Empty;
    /// <summary>The first due date; later due dates follow the frequency from this date.</summary>
    [Required(ErrorMessage = "Choose the first due date.")]
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
