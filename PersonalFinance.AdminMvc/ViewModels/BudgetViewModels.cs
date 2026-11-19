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

public class BudgetEditViewModel : IValidatableObject
{
    [Required, StringLength(19)]
    [Display(Name = "Limit amount")]
    public string? LimitAmount { get; set; }

    [Required, Range(1, 100)]
    [Display(Name = "Alert threshold (%)")]
    public int? AlertThresholdPct { get; set; }

    public decimal Amount => decimal.Parse(LimitAmount!, CultureInfo.InvariantCulture);

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (!decimal.TryParse(LimitAmount, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var amount)
            || amount <= 0 || amount > 9999999999999999.99m || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2)
            yield return new("Enter a positive amount with up to 16 whole digits and two decimal places. Use a decimal point.",
                [nameof(LimitAmount)]);
    }
}

public sealed class BudgetCreateViewModel : BudgetEditViewModel
{
    [Required, Range(1, int.MaxValue)]
    [Display(Name = "Expense category")]
    public int? CategoryId { get; set; }

    [Required, RegularExpression("^[0-9]{4}-(0[1-9]|1[0-2])$")]
    public string? Month { get; set; }

    [BindNever] public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];
}
