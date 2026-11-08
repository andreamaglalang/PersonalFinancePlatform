namespace PersonalFinance.Infrastructure.Finance.Contracts;

public sealed record TransactionResponse(
    int TransactionId, Guid ClientId, string Type, int CategoryId, string CategoryName,
    decimal Amount, string CurrencyCode, decimal FxRate, DateOnly FxRateDate, decimal AmountBase,
    DateOnly TransactionDate, string? Description, DateTime CreatedAtUtc, int? BillId, DateOnly? BillDueDate)
{
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<BudgetResponse>? AffectedBudgets { get; init; }
}

public sealed record CategoryResponse(int CategoryId, string Name, string Type, string? ColorHEX, bool IsActive, bool IsSystemDefault);
public sealed record PagedResponse<T>(IReadOnlyList<T> Items, int Page, int PageSize, long TotalCount);

public sealed record FinanceResult<T>(T? Value, int StatusCode, string? Error)
{
    public bool Succeeded => Error is null;
    public static FinanceResult<T> Success(T value, int status = 200) => new(value, status, null);
    public static FinanceResult<T> Failure(int status, string error) => new(default, status, error);
}

