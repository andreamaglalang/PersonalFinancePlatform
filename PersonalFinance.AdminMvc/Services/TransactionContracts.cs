using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.AdminMvc.Services;

// Mirrors PersonalFinance.Infrastructure.Finance.Contracts.FinanceResponses.TransactionResponse exactly:
// 15 constructor parameters plus an optional AffectedBudgets property. The upstream record carries no
// per-transaction currency code (AmountBase and BudgetResponse carry the currency fields).
public sealed record TransactionResponse(
    int TransactionId, Guid ClientId, string Type, int CategoryId, string CategoryName,
    decimal Amount, string CurrencyCode, decimal FxRate, DateOnly FxRateDate, decimal AmountBase,
    DateOnly TransactionDate, string? Description, DateTime CreatedAtUtc, int? BillId, DateOnly? BillDueDate)
{
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<BudgetResponse>? AffectedBudgets { get; init; }
}

public sealed record TransactionPage(IReadOnlyList<TransactionResponse> Items, int Page, int PageSize, long TotalCount);

// Mirrors Infrastructure CreateTransactionRequest, where ClientId is Guid? upstream.
public sealed record CreateTransactionRequest(Guid? ClientId, string Type, int CategoryId, decimal Amount,
    string CurrencyCode, DateOnly TransactionDate, string? Description);

// Mirrors Infrastructure TransactionWriteRequest.
public sealed record UpdateTransactionRequest(string Type, int CategoryId, decimal Amount,
    string CurrencyCode, DateOnly TransactionDate, string? Description);

// Mirrors Infrastructure TransactionDeletionResponse.
public sealed record TransactionDeletionResponse(int TransactionId, bool Deleted,
    IReadOnlyList<BudgetResponse> AffectedBudgets);

public sealed record TransactionResult<T>(int Status, T? Value = default)
{
    public bool Succeeded => Status is 200 or 201 or 204;
}
