using System.ComponentModel.DataAnnotations;
using System.Globalization;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class BudgetIndexViewModel
{
    public string? Month { get; set; }
    [Range(1, int.MaxValue)] public int Page { get; set; } = 1;
    [BindNever] public BudgetPage? Results { get; set; }
    [BindNever] public string? Error { get; set; }
}

public class BudgetEditViewModel
{
    [Required(ErrorMessage = "Enter a limit amount."), StringLength(19, ErrorMessage = "That amount is too long."), MoneyText]
    [Display(Name = "Limit amount")]
    public string? LimitAmount { get; set; }

    [Required(ErrorMessage = "Enter an alert threshold from 1 to 100."), Range(1, 100, ErrorMessage = "Enter an alert threshold from 1 to 100.")]
    [Display(Name = "Alert threshold (%)")]
    public int? AlertThresholdPct { get; set; }

    // Read only after ModelState is valid; validation must not evaluate it (unparseable text would throw).
    [Microsoft.AspNetCore.Mvc.ModelBinding.Validation.ValidateNever]
    public decimal Amount => decimal.Parse(LimitAmount!, CultureInfo.InvariantCulture);

}

public sealed class BudgetCreateViewModel : BudgetEditViewModel
{
    [Required(ErrorMessage = "Choose an expense category."), Range(1, int.MaxValue, ErrorMessage = "Choose an expense category.")]
    [Display(Name = "Expense category")]
    public int? CategoryId { get; set; }

    [Required(ErrorMessage = "Choose a month."), RegularExpression("^[0-9]{4}-(0[1-9]|1[0-2])$", ErrorMessage = "Choose a valid month (YYYY-MM).")]
    public string? Month { get; set; }

    [BindNever] public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];
}
