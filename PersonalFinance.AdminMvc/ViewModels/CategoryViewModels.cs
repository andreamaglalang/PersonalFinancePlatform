using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class CategoryIndexViewModel
{
    [RegularExpression("^(Income|Expense)$")] public string? Type { get; set; }
    [Range(1, int.MaxValue)] public int Page { get; set; } = 1;
    [Range(1, 100)] public int PageSize { get; set; } = 50;
    public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];
    public bool HasPrevious => Page > 1;
    public bool HasNext { get; set; }
    public string? Error { get; set; }
}

public sealed class CategoryCreateViewModel
{
    [Required, StringLength(50)] public string Name { get; set; } = string.Empty;
    [Required, RegularExpression("^(Income|Expense)$")] public string Type { get; set; } = "Expense";
    [Display(Name = "Color (optional)"), RegularExpression("^#[0-9A-Fa-f]{6}$")]
    public string? ColorHEX { get; set; }
    [Display(Name = "Active")] public bool IsActive { get; set; } = true;
}

public sealed class CategoryEditViewModel
{
    [Required, StringLength(50)] public string Name { get; set; } = string.Empty;
    [Display(Name = "Color (optional)"), RegularExpression("^#[0-9A-Fa-f]{6}$")]
    public string? ColorHEX { get; set; }
    [Display(Name = "Active")] public bool IsActive { get; set; }
    public bool ConfirmDeactivation { get; set; }
    // Protected nonsecret category snapshot, bound to this MVC session. Not an API token.
    [Required, StringLength(8192)] public string OriginalState { get; set; } = string.Empty;
    [BindNever] public string CurrentType { get; set; } = string.Empty;
}
