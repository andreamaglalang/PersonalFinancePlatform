using System.ComponentModel.DataAnnotations;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed record DailyFinanceTotal(DateOnly Date, decimal Income, decimal Expenses);
public sealed record PeriodFinanceTotal(DateOnly Month, decimal Income, decimal Expenses);
public sealed record PersonalOverview(DateOnly? Month, string BaseCurrency, decimal TotalIncome, decimal TotalExpenses,
    decimal NetCashFlow, int TransactionCount, IReadOnlyList<ReportCategoryTotal> ExpenseByCategory,
    IReadOnlyList<DailyFinanceTotal> Days, IReadOnlyList<PeriodFinanceTotal> Months);

public sealed record SpendingBreakdown(string Currency, IReadOnlyList<ReportCategoryTotal> Categories, bool ShowTop = true, bool Android = false);
public sealed record GoalSummary(int GoalId, string Name, decimal TargetAmount, DateOnly? TargetDate,
    string Status, decimal SavedAmount, decimal RemainingAmount, decimal SurplusAmount, decimal ProgressPercentage, string BaseCurrency, string Version);
public sealed record GoalOverview(PersonalPage<GoalSummary>? Goals, string? Error);
public sealed record PersonalCurrencyQuote(decimal Amount, string Source, string Target, decimal Rate, DateOnly RateDate, decimal ConvertedAmount);
public sealed class PersonalConversion
{
    [Required, Range(typeof(decimal), "0.01", "9999999999999999.99")]
    public decimal? Amount { get; set; }

    [Required, RegularExpression("^[A-Z]{3}$")] public string Source { get; set; } = "";
    [Required, RegularExpression("^[A-Z]{3}$")] public string Target { get; set; } = "PHP";
}
public sealed class PersonalDisplayName
{
    [Required, StringLength(100)] public string FullName { get; set; } = "";
}

public sealed record PersonalBill(int BillId, string Name, decimal Amount, string CurrencyCode, string Frequency,
    DateOnly NextDueDate, string Status, DateOnly? LastPayment);
public sealed record PersonalBillOverview(PersonalPage<PersonalBill>? Bills, string? Error);
public sealed class PersonalPassword
{
    [Required, StringLength(128)] public string CurrentPassword { get; set; } = "";
    [Required, StringLength(128, MinimumLength = 12)] public string NewPassword { get; set; } = "";
    [Required, StringLength(128), Compare(nameof(NewPassword))] public string ConfirmPassword { get; set; } = "";
}

public sealed record PersonalContribution(int ContributionId, int GoalId, decimal Amount, DateOnly ContributedOn,
    string? Note, string BaseCurrency, string Version);
public sealed record PersonalGoalDetails(GoalSummary Goal, PersonalPage<PersonalContribution>? Contributions, string? Error,
    ContributionForm Entry);
public sealed record ContributionSaved(bool Replayed, bool Deleted, GoalSummary Goal);

public sealed class PersonalGoalForm
{
    [Required, StringLength(100)] public string Name { get; set; } = "";
    [Required, Range(typeof(decimal), "0.01", "9999999999999999.99")] public decimal? TargetAmount { get; set; }
    public DateOnly? TargetDate { get; set; }
}
public sealed class ContributionForm
{
    public Guid ClientId { get; set; }
    [Required, Range(typeof(decimal), "0.01", "9999999999999999.99")] public decimal? Amount { get; set; }
    [Required] public DateOnly? ContributedOn { get; set; }
    [StringLength(200)] public string? Note { get; set; }
}

public sealed record FinanceFlowShare(string Currency, decimal Income, decimal Expenses);
