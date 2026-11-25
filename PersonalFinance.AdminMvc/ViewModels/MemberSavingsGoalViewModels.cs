using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class MemberSavingsGoalIndexViewModel
{
    [Range(1, int.MaxValue)] public int Page { get; set; } = 1;
    [BindNever] public SavingsGoalPage? Results { get; set; }
    [BindNever] public string? Notice { get; set; }
    [BindNever] public string? Error { get; set; }
}

public sealed class MemberSavingsGoalDetailViewModel
{
    public required SavingsGoalResponse Goal { get; init; }
    public required GoalContributionPage Contributions { get; init; }
    public string? Error { get; init; }
    public string? Notice { get; init; }
}

public class MemberSavingsGoalFormModel : IValidatableObject
{
    [Required, StringLength(100)]
    [Display(Name = "Goal name")]
    public string? Name { get; set; }

    [Required, StringLength(19)]
    [Display(Name = "Target amount")]
    public string? TargetAmount { get; set; }

    [Display(Name = "Target date")]
    public DateOnly? TargetDate { get; set; }

    public decimal Amount => decimal.Parse(TargetAmount!, System.Globalization.CultureInfo.InvariantCulture);

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (!decimal.TryParse(TargetAmount, System.Globalization.NumberStyles.AllowDecimalPoint,
                System.Globalization.CultureInfo.InvariantCulture, out var amount)
            || amount <= 0 || amount > 9999999999999999.99m
            || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2)
            yield return new("Enter a positive amount with up to 16 whole digits and two decimal places. Use a decimal point.",
                [nameof(TargetAmount)]);
    }
}

public sealed class MemberContributionFormModel : IValidatableObject
{
    [Required, StringLength(19)]
    [Display(Name = "Amount")]
    public string? Amount { get; set; }

    [Required, Display(Name = "Contribution date")]
    public DateOnly? ContributedOn { get; set; }

    [StringLength(200)]
    [Display(Name = "Note")]
    public string? Note { get; set; }

    public decimal Value => decimal.Parse(Amount!, System.Globalization.CultureInfo.InvariantCulture);

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (!decimal.TryParse(Amount, System.Globalization.NumberStyles.AllowDecimalPoint,
                System.Globalization.CultureInfo.InvariantCulture, out var amount)
            || amount <= 0 || amount > 9999999999999999.99m
            || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2)
            yield return new("Enter a positive amount with up to 16 whole digits and two decimal places. Use a decimal point.",
                [nameof(Amount)]);
    }
}
