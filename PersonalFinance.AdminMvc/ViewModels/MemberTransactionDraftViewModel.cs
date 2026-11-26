using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class MemberTransactionDraftViewModel
{
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public int? TransactionId { get; set; }
    /// <summary>The transaction version read when the edit form was opened (C1); never refreshed on POST.</summary>
    public string? Version { get; set; }

    [Required, RegularExpression("^(Income|Expense)$")]
    public string Type { get; set; } = "Expense";

    [Required, Range(1, int.MaxValue)]
    public int? CategoryId { get; set; }

    [Required, Range(0.01, 100_000_000)]
    public decimal? Amount { get; set; }

    [Required, RegularExpression("^[A-Z]{3}$")]
    public string CurrencyCode { get; set; } = MemberCurrencyPreferences.AccountCurrency;

    [Required]
    public DateOnly? TransactionDate { get; set; }

    [StringLength(200)]
    public string? Description { get; set; }

    public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];

    // Currency-preference wiring (never bound from the form).
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public IReadOnlyList<string> Currencies { get; set; } = MemberCurrencyPreferences.Supported;
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public string BaseCurrency { get; set; } = MemberCurrencyPreferences.AccountCurrency;
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public bool ReviewRequired { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public bool ShowOriginalAmounts { get; set; } = true;

    // Rate-review step: the rendered quote plus the protected confirmation token.
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public MemberFxReview.Quote? FxQuote { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever]
    public string? FxProof { get; set; }

    [FromForm(Name = "FxProof")] public string? PostedFxProof { get; set; }
    [FromForm(Name = "FxConfirmed")] public bool FxConfirmed { get; set; }
}
