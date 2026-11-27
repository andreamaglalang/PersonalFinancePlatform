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
    /// <summary>Edit only: the goal version the form was opened with (a stale form is refused, never applied).</summary>
    public string? Version { get; set; }

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
    /// <summary>New contributions: the identity minted when the form opened, so a resubmitted form records once.</summary>
    public Guid? ClientId { get; set; }

    /// <summary>Edits: the contribution version the form was opened with.</summary>
    public string? Version { get; set; }

    [Required(ErrorMessage = "Enter an amount."), StringLength(19, ErrorMessage = "That amount is too long."), MoneyText]
    [Display(Name = "Amount")]
    public string? Amount { get; set; }

    [Required(ErrorMessage = "Choose a contribution date."), Display(Name = "Contribution date")]
    public DateOnly? ContributedOn { get; set; }

    [StringLength(200, ErrorMessage = "Notes can be up to 200 characters.")]
    [Display(Name = "Note")]
    public string? Note { get; set; }

    public decimal Value => decimal.Parse(Amount!, System.Globalization.CultureInfo.InvariantCulture);

}
