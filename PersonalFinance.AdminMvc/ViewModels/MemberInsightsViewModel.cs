using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>Member Insights state. Mutable: the quote form re-renders the same request with its result.</summary>
public sealed class MemberInsightsViewModel
{
    public sealed record MonthPoint(DateOnly Month, string BaseCurrency, decimal Income, decimal Expenses,
        decimal NetFlow, int TransactionCount);

    public string? Month { get; set; }
    public string? MonthError { get; set; }
    public MonthlyReportResponse? Report { get; set; }
    public IReadOnlyList<MonthPoint> MonthlyHistory { get; set; } = [];
    public IReadOnlyDictionary<int, string> CategoryColors { get; set; } = new Dictionary<int, string>();
    public bool HistoryPartial { get; set; }
    public string? ReportError { get; set; }
    public string? QuoteFrom { get; set; }
    public string? QuoteTo { get; set; }
    public string? QuoteAmount { get; set; }
    public ExchangeRateQuoteResponse? Quote { get; set; }
    public string? QuoteError { get; set; }

    public MemberInsightsViewModel() { }

    public MemberInsightsViewModel(string? month, string? monthError = null, MonthlyReportResponse? report = null,
        string? reportError = null)
    {
        Month = month; MonthError = monthError; Report = report; ReportError = reportError;
    }
}
