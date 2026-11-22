using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed record PersonalPage<T>(IReadOnlyList<T> Items, int Page, int PageSize, long TotalCount);
public sealed record PersonalTransaction(int TransactionId, Guid ClientId, string Type, int CategoryId,
    string CategoryName, decimal Amount, string CurrencyCode, decimal AmountBase, DateOnly TransactionDate,
    string? Description, int? BillId, DateOnly? BillDueDate, IReadOnlyList<BudgetResponse>? AffectedBudgets = null,
    decimal? FxRate = null, DateOnly? FxRateDate = null)
{
    public bool BillLinked => BillId.HasValue || BillDueDate.HasValue;
}
public sealed record PersonalTransactionDeletion(int TransactionId, bool Deleted, IReadOnlyList<BudgetResponse> AffectedBudgets);
public sealed record TransactionInput(string Type, int CategoryId, decimal Amount, string CurrencyCode,
    DateOnly TransactionDate, string? Description);
public sealed record CreatePersonalTransaction(Guid ClientId, string Type, int CategoryId, decimal Amount,
    string CurrencyCode, DateOnly TransactionDate, string? Description);

public sealed class TransactionFilters
{
    public string? Type { get; set; }
    public int? CategoryId { get; set; }
    public DateOnly? FromDate { get; set; }
    public DateOnly? ToDate { get; set; }
    public string SortBy { get; set; } = "date";
    public string SortDirection { get; set; } = "desc";
    public int Page { get; set; } = 1;

    public string? Search { get; set; }
    public string? Month { get; set; }

    public DateOnly? SelectedMonth => PersonalMonth.TryParse(Month, out var selected) && PersonalMonth.IsReportMonth(selected) ? selected : null;
    public DateOnly? EffectiveFrom => SelectedMonth is not { } month || FromDate > month ? FromDate : month;
    public DateOnly? EffectiveTo
    {
        get
        {
            var end = SelectedMonth?.AddMonths(1).AddDays(-1);
            return end is null || ToDate < end ? ToDate : end;
        }
    }
    public bool HasFilters => Type is not null || CategoryId.HasValue || FromDate.HasValue || ToDate.HasValue
        || !string.IsNullOrWhiteSpace(Search);

    public bool IsValid => Type is null or "Income" or "Expense" && (CategoryId is null or > 0)
        && (Month is null || SelectedMonth.HasValue) && Search?.Length is null or <= 100
        && !(EffectiveFrom > EffectiveTo) && SortBy is "date" or "amount" or "createdAt"
        && SortDirection is "asc" or "desc" && Page > 0 && (long)(Page - 1) * 20 <= int.MaxValue;
}

public sealed class PersonalTransactionForm : IValidatableObject
{
    [Required, RegularExpression("^(Income|Expense)$")] public string Type { get; set; } = "Expense";
    [Required, Range(1, int.MaxValue)] public int? CategoryId { get; set; }
    [Required] public decimal? Amount { get; set; }
    [Required, RegularExpression("^[A-Z]{3}$")] public string CurrencyCode { get; set; } = "";
    [Required] public DateOnly? TransactionDate { get; set; }
    [StringLength(200)] public string? Description { get; set; }
    [StringLength(512)] public string? DraftToken { get; set; }
    [BindNever] public int? Id { get; set; }
    [BindNever] public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];
    [BindNever] public bool CategoriesAvailable { get; set; }

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (Amount is { } amount && (amount <= 0 || amount > 9999999999999999.99m
            || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2))
            yield return new ValidationResult("Enter a positive amount with at most two decimal places.",
                [nameof(Amount)]);
    }

    public TransactionInput ToInput() => new(Type, CategoryId!.Value, Amount!.Value, CurrencyCode,
        TransactionDate!.Value, Description);
}

public sealed record TransactionHistory(TransactionFilters Filters, PersonalPage<PersonalTransaction>? Results,
    IReadOnlyList<CategoryResponse> Categories, string? Error, bool CategoriesAvailable, PersonalOverview? Overview = null, bool OverviewUnavailable = false);
public sealed record TransactionDetails(PersonalTransaction? Transaction, string? Error = null);
public sealed record PersonalCategoryList(CategoryPageResponse? Results, string? Error = null, string Type = "Expense");
public sealed class PersonalCategoryForm
{
    [Required, StringLength(50)] public string Name { get; set; } = "";
    [Required, RegularExpression("^(Income|Expense)$")] public string Type { get; set; } = "Expense";
    [RegularExpression("^#[0-9A-Fa-f]{6}$", ErrorMessage = "Enter a color such as #7850B5.")]
    public string? ColorHEX { get; set; }
    [BindNever] public int? Id { get; set; }
    [BindNever] public bool Active { get; set; }
}

public sealed record PersonalBudgetNotice(string SessionId, string[] Lines);
