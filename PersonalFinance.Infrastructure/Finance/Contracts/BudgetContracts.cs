using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateBudgetRequest : UpdateBudgetRequest
{
    [Required, Range(1, int.MaxValue)]
    public int? CategoryId { get; set; }
    [Required]
    public DateOnly? PeriodMonth { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class UpdateBudgetRequest
{
    [Required]
    public decimal? LimitAmount { get; set; }
    [Required, Range(1, 100)]
    public int? AlertThresholdPct { get; set; }
}

public sealed class BudgetQuery
{
    public DateOnly? PeriodMonth { get; set; }
    public int? CategoryId { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
}

public sealed record BudgetResponse(int BudgetId, int CategoryId, string CategoryName, bool IsCategoryActive,
    DateOnly PeriodMonth, decimal LimitAmount, byte AlertThresholdPct, decimal SpentAmount,
    decimal RemainingAmount, string Status, string BaseCurrency);

public sealed record TransactionDeletionResponse(int TransactionId, bool Deleted, IReadOnlyList<BudgetResponse> AffectedBudgets);