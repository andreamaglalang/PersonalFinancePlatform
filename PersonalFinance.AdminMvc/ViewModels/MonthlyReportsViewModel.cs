using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed record MonthlyReportsViewModel(string? Month, string? MonthError = null,
    MonthlyReportResponse? Report = null, string? ReportError = null);
