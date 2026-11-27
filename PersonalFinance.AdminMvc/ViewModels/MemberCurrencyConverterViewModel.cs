using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class MemberCurrencyConverterViewModel
{
    public string From { get; set; } = "USD";
    public string To { get; set; } = MemberCurrencyPreferences.AccountCurrency;
    public string Amount { get; set; } = "1.00";
    public ExchangeRateQuoteResponse? Quote { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Error { get; set; }
    public IReadOnlyList<string> Currencies => MemberCurrencyPreferences.Supported;
}

public sealed class MemberCategoryIndexViewModel
{
    public string? Type { get; set; }
    public int Page { get; set; } = 1;
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public IReadOnlyList<PersonalFinance.AdminMvc.Services.Contracts.CategoryResponse> Items { get; set; } = [];
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public long TotalCount { get; set; }
    public string? Error { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Notice { get; set; }
    public int PageSize => 20;
    public int TotalPages => Math.Max(1, (int)Math.Ceiling(TotalCount / (double)PageSize));
}

public sealed class MemberCategoryFormViewModel : System.ComponentModel.DataAnnotations.IValidatableObject
{
    [System.ComponentModel.DataAnnotations.Required(ErrorMessage = "Enter a category name."), System.ComponentModel.DataAnnotations.StringLength(50, ErrorMessage = "Category names can be up to 50 characters.")]
    public string Name { get; set; } = string.Empty;
    [System.ComponentModel.DataAnnotations.Required(ErrorMessage = "Choose Income or Expense."), System.ComponentModel.DataAnnotations.RegularExpression("^(Income|Expense)$", ErrorMessage = "Choose Income or Expense.")]
    public string Type { get; set; } = "Expense";
    [System.ComponentModel.DataAnnotations.RegularExpression("^#[0-9A-Fa-f]{6}$", ErrorMessage = "Choose a valid color.")]
    public string? ColorHEX { get; set; }
    public bool IsActive { get; set; } = true;
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public bool IsEdit { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Error { get; set; }

    public IEnumerable<System.ComponentModel.DataAnnotations.ValidationResult> Validate(System.ComponentModel.DataAnnotations.ValidationContext validationContext)
    {
        if (!string.IsNullOrEmpty(ColorHEX) && !System.Text.RegularExpressions.Regex.IsMatch(ColorHEX, "^#[0-9A-Fa-f]{6}$"))
            yield return new("Choose a valid six-digit color.", [nameof(ColorHEX)]);
    }
}
