using System.ComponentModel.DataAnnotations;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>
/// Member currency preferences. Browser-local and account-scoped; the account currency is read-only and always PHP.
/// </summary>
public sealed class MemberCurrencyPreferencesViewModel
{
    /// <summary>The account's own currency. Read-only: transactions are always converted to it.</summary>
    public string AccountCurrency { get; set; } = MemberCurrencyPreferences.AccountCurrency;

    [Required(ErrorMessage = "Choose one of the supported currencies."), RegularExpression("^[A-Z]{3}$", ErrorMessage = "Choose one of the supported currencies.")]
    [Display(Name = "Default transaction currency")]
    public string DefaultCurrency { get; set; } = MemberCurrencyPreferences.AccountCurrency;

    [Display(Name = "Show original foreign amounts")]
    public bool ShowOriginalAmounts { get; set; } = true;

    [Display(Name = "Review exchange rate before saving")]
    public bool ReviewRateBeforeSaving { get; set; }

    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Notice { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Error { get; set; }

    public IReadOnlyList<string> Currencies { get; set; } = MemberCurrencyPreferences.Supported;
}
