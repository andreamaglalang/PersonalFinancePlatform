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

public class MemberSavingsGoalFormModel
{
    [Required(ErrorMessage = "Enter a goal name."), StringLength(100, ErrorMessage = "Goal name must be 100 characters or fewer.")]
    [Display(Name = "Goal name")]
    public string? Name { get; set; }

    [Required(ErrorMessage = "Enter a target amount."), StringLength(19, ErrorMessage = "That amount is too long."), MoneyText]
    [Display(Name = "Target amount")]
    public string? TargetAmount { get; set; }

    [Display(Name = "Target date")]
    public DateOnly? TargetDate { get; set; }

    public decimal Amount => decimal.Parse(TargetAmount!, System.Globalization.CultureInfo.InvariantCulture);

}

public sealed class MemberContributionFormModel
{
    [Required(ErrorMessage = "Enter an amount."), StringLength(19, ErrorMessage = "That amount is too long."), MoneyText]
    [Display(Name = "Amount")]
    public string? Amount { get; set; }

    [Required(ErrorMessage = "Choose a contribution date."), Display(Name = "Contribution date")]
    public DateOnly? ContributedOn { get; set; }

    [StringLength(200)]
    [Display(Name = "Note")]
    public string? Note { get; set; }

    public decimal Value => decimal.Parse(Amount!, System.Globalization.CultureInfo.InvariantCulture);

}
