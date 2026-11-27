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
    /// <summary>New transactions: the identity minted when the form opened, so a resubmitted form records once.</summary>
    public Guid? ClientId { get; set; }

    [Required(ErrorMessage = "Choose Income or Expense."), RegularExpression("^(Income|Expense)$", ErrorMessage = "Choose Income or Expense.")]
    public string Type { get; set; } = "Expense";

    [Required(ErrorMessage = "Choose a category."), Range(1, int.MaxValue, ErrorMessage = "Choose a category.")]
    public int? CategoryId { get; set; }

    [Required(ErrorMessage = "Enter an amount."), MoneyAmount(100_000_000), ModelBinder(typeof(MoneyModelBinder))]
    public decimal? Amount { get; set; }

    [Required(ErrorMessage = "Choose a currency."), RegularExpression("^[A-Z]{3}$", ErrorMessage = "Choose a currency.")]
    public string CurrencyCode { get; set; } = MemberCurrencyPreferences.AccountCurrency;

    [Required(ErrorMessage = "Choose a date.")]
    public DateOnly? TransactionDate { get; set; }

    [StringLength(200, ErrorMessage = "Description must be 200 characters or fewer.")]
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
