using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class MemberBillIndexViewModel
{
    [Range(1, int.MaxValue)] public int Page { get; set; } = 1;
    [BindNever] public RecurringBillPage? Results { get; set; }
    [BindNever] public string? Notice { get; set; }
    [BindNever] public string? Error { get; set; }
}

/// <summary>Editable bill fields. Frequency and first due date are fixed at creation and never submitted as edits.</summary>
public class MemberBillFormModel : IValidatableObject
{
    [Required, StringLength(100)]
    [Display(Name = "Bill name")]
    public string? Name { get; set; }

    [Required, Range(1, int.MaxValue)]
    [Display(Name = "Expense category")]
    public int? CategoryId { get; set; }

    [Required, StringLength(19)]
    [Display(Name = "Amount")]
    public string? Amount { get; set; }

    [Required, StringLength(3, MinimumLength = 3)]
    [Display(Name = "Currency")]
    public string? CurrencyCode { get; set; }

    [Required, Range(0, 30)]
    [Display(Name = "Reminder days before")]
    public int? ReminderDaysBefore { get; set; }

    [Display(Name = "Active")]
    public bool IsActive { get; set; } = true;

    [Required, RegularExpression("Weekly|Monthly|Quarterly|Yearly")]
    [Display(Name = "Frequency")]
    public string? Frequency { get; set; }

    [Required, Display(Name = "First due date")]
    public DateOnly? FirstDueDate { get; set; }

    public decimal Value => decimal.Parse(Amount!, System.Globalization.CultureInfo.InvariantCulture);

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (!decimal.TryParse(Amount, System.Globalization.NumberStyles.AllowDecimalPoint,
                System.Globalization.CultureInfo.InvariantCulture, out var amount)
            || amount <= 0 || amount > 9999999999999999.99m
            || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2)
            yield return new("Enter a positive amount with up to 16 whole digits and two decimal places. Use a decimal point.",
                [nameof(Amount)]);
        if (CurrencyCode is not { Length: 3 } || !CurrencyCode.All(c => c is >= 'A' and <= 'Z'))
            yield return new("Currency is three letters, for example PHP.", [nameof(CurrencyCode)]);
    }
}
