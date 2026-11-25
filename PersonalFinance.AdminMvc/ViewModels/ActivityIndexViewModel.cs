using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class ActivityIndexViewModel
{
    public string? Type { get; set; }
    public int? CategoryId { get; set; }
    public string? Month { get; set; }
    public string Period { get; set; } = "Month";
    public string? Search { get; set; }
    public DateOnly? Day { get; set; }
    public DateOnly? FromDate { get; set; }
    public DateOnly? ToDate { get; set; }
    public string? AmountOrder { get; set; }
    public int Page { get; set; } = 1;

    [BindNever] public long TotalCount { get; set; }
    [BindNever] public long SummaryCount { get; set; }
    [BindNever] public decimal IncomeTotal { get; set; }
    [BindNever] public decimal ExpenseTotal { get; set; }
    [BindNever] public IReadOnlyDictionary<DateOnly, decimal> DailyNet { get; set; } = new Dictionary<DateOnly, decimal>();
    [BindNever] public IReadOnlyDictionary<DateOnly, int> DailyCount { get; set; } = new Dictionary<DateOnly, int>();
    public int ActiveFilterCount => (Type is null ? 0 : 1) + (CategoryId is null ? 0 : 1) + (string.IsNullOrWhiteSpace(Search) ? 0 : 1) + (Day is null ? 0 : 1) + (Period == "Range" ? 1 : 0) + (AmountOrder is null ? 0 : 1);
    public decimal NetFlow => IncomeTotal - ExpenseTotal;
    [BindNever] public IReadOnlyDictionary<DateOnly, decimal> DailyVolume { get; set; } = new Dictionary<DateOnly, decimal>();
    [BindNever] public IReadOnlyList<TransactionResponse> Items { get; set; } = [];
    [BindNever] public IReadOnlyList<CategoryResponse> Categories { get; set; } = [];
    [BindNever] public string? CategoryError { get; set; }
    [BindNever] public bool DataLoaded { get; set; }
    [BindNever] public string? Error { get; set; }
    [BindNever] public string? Notice { get; set; }

    // Currency display, from the member's browser-local preference.
    [BindNever] public string BaseCurrency { get; set; } = MemberCurrencyPreferences.AccountCurrency;
    [BindNever] public bool ShowOriginalAmounts { get; set; } = true;

    public int PageSize => TransactionApiClient.PageSize;
    public int TotalPages => PageSize <= 0 || TotalCount <= 0
        ? 1
        : (int)Math.Ceiling(TotalCount / (double)PageSize);
    public bool HasPrevious => Page > 1;
    public bool HasNext => (long)Page * PageSize < TotalCount;
    public int FirstRecord => TotalCount == 0 ? 0 : (Page - 1) * PageSize + 1;
    public int LastRecord => (int)Math.Min(TotalCount, (long)Page * PageSize);
    public bool HasActiveFilters => Type is not null || CategoryId is not null
        || !string.IsNullOrWhiteSpace(Search) || Day is not null || Period == "Range" || AmountOrder is not null;
}
