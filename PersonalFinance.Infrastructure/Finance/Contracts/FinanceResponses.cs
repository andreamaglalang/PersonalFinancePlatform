namespace PersonalFinance.Infrastructure.Finance.Contracts;

public sealed record TransactionResponse(
    int TransactionId, Guid ClientId, string Type, int CategoryId, string CategoryName,
    decimal Amount, string CurrencyCode, decimal FxRate, DateOnly FxRateDate, decimal AmountBase,
    DateOnly TransactionDate, string? Description, DateTime CreatedAtUtc, int? BillId, DateOnly? BillDueDate)
{
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<BudgetResponse>? AffectedBudgets { get; init; }

    /// <summary>Optimistic-concurrency token (C1): uppercase SHA-256 hex of the stored, editable state.</summary>
    public string Version => TransactionVersion.Of(TransactionId, Type, CategoryId, Amount, CurrencyCode, FxRate, FxRateDate,
        AmountBase, TransactionDate, Description);
}

public static class TransactionVersion
{
    public static string Of(int id, string type, int categoryId, decimal amount, string currency, decimal fxRate,
        DateOnly fxRateDate, decimal amountBase, DateOnly date, string? description)
    {
        // Normalize scale so 90 and 90.00 hash identically (request vs SQL decimal(18,2)).
        static string D(decimal value) => (value / 1.000000000000000000000000000000000m).ToString(System.Globalization.CultureInfo.InvariantCulture);
        var state = string.Join('\u001F', id, type, categoryId, D(amount), currency, D(fxRate), fxRateDate.ToString("O"),
            D(amountBase), date.ToString("O"), description ?? "\u0000");
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(state)));
    }
}

public sealed record CategoryResponse(int CategoryId, string Name, string Type, string? ColorHEX, bool IsActive, bool IsSystemDefault);
public sealed record PagedResponse<T>(IReadOnlyList<T> Items, int Page, int PageSize, long TotalCount);

public sealed record FinanceResult<T>(T? Value, int StatusCode, string? Error)
{
    public bool Succeeded => Error is null;
    public static FinanceResult<T> Success(T value, int status = 200) => new(value, status, null);
    public static FinanceResult<T> Failure(int status, string error) => new(default, status, error);
}

