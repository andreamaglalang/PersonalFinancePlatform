using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>Member budget list. Reuses the administrator budget form models so validation stays identical.</summary>
public sealed class MemberBudgetIndexViewModel
{
    public string? Month { get; set; }
    [Range(1, int.MaxValue)] public int Page { get; set; } = 1;
    [BindNever] public BudgetPage? Results { get; set; }
    [BindNever] public BudgetPlanSummary? Summary { get; set; }
    [BindNever] public string? Notice { get; set; }
    [BindNever] public string? Error { get; set; }
}
