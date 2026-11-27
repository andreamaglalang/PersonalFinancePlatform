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
public class MemberBillFormModel
{
    [Required(ErrorMessage = "Enter a bill name."), StringLength(100, ErrorMessage = "Bill name must be 100 characters or fewer."),
     SingleLineText(ErrorMessage = "Bill names can't contain line breaks or control characters.")]
    [Display(Name = "Bill name")]
    public string? Name { get; set; }

    [Required(ErrorMessage = "Choose an expense category."), Range(1, int.MaxValue, ErrorMessage = "Choose an expense category.")]
    [Display(Name = "Expense category")]
    public int? CategoryId { get; set; }

    [Required(ErrorMessage = "Enter an amount."), StringLength(19, ErrorMessage = "That amount is too long."), MoneyText]
    [Display(Name = "Amount")]
    public string? Amount { get; set; }

    [Required(ErrorMessage = "Choose a currency."), RegularExpression("^[A-Z]{3}$", ErrorMessage = "Currency is three letters, for example PHP.")]
    [Display(Name = "Currency")]
    public string? CurrencyCode { get; set; }

    [Required(ErrorMessage = "Enter how many days before to remind you."), Range(0, 30, ErrorMessage = "Reminder days must be a whole number from 0 to 30.")]
    [Display(Name = "Reminder days before")]
    public int? ReminderDaysBefore { get; set; }

    [Display(Name = "Active")]
    public bool IsActive { get; set; } = true;

    [Required(ErrorMessage = "Choose how often the bill repeats."), RegularExpression("Weekly|Monthly|Quarterly|Yearly", ErrorMessage = "Choose how often the bill repeats.")]
    [Display(Name = "Frequency")]
    public string? Frequency { get; set; }

    [Required(ErrorMessage = "Choose the first due date."), Display(Name = "First due date")]
    public DateOnly? FirstDueDate { get; set; }

    // Read only after ModelState is valid; validation must not evaluate it (unparseable text would throw).
    [Microsoft.AspNetCore.Mvc.ModelBinding.Validation.ValidateNever]
    public decimal Value => decimal.Parse(Amount!, System.Globalization.CultureInfo.InvariantCulture);

}
