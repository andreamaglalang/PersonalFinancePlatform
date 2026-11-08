
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Infrastructure.Finance;

public interface ITransactionService
{
    Task<FinanceResult<TransactionResponse>> CreateAsync(CreateTransactionRequest request, CancellationToken ct);
    Task<FinanceResult<PagedResponse<TransactionResponse>>> ListAsync(TransactionQuery query, CancellationToken ct);
    Task<FinanceResult<TransactionResponse>> GetAsync(int id, CancellationToken ct);
    Task<FinanceResult<TransactionResponse>> UpdateAsync(int id, TransactionWriteRequest request, CancellationToken ct);
    Task<FinanceResult<TransactionDeletionResponse>> DeleteAsync(int id, CancellationToken ct);
}

public interface ICategoryService
{
    Task<FinanceResult<PagedResponse<CategoryResponse>>> ListAsync(CategoryQuery query, CancellationToken ct);
    Task<FinanceResult<CategoryResponse>> GetAsync(int id, CancellationToken ct);
    Task<FinanceResult<CategoryResponse>> CreateAsync(CategoryWriteRequest request, bool systemDefault, CancellationToken ct);
    Task<FinanceResult<CategoryResponse>> UpdateAsync(int id, CategoryWriteRequest request, bool systemDefault, CancellationToken ct);
    Task<FinanceResult<bool>> DeleteAsync(int id, bool systemDefault, CancellationToken ct);
}

public interface IFinancialWriteScope
{
    Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken, Task<T>> operation, CancellationToken ct);
}

public sealed record FxQuote(string BaseCurrency, string QuoteCurrency, DateOnly RateDate, decimal Rate);

public interface IFrankfurterClient
{
    Task<bool> SupportsAsync(string currency, CancellationToken ct);
    Task<FxQuote> GetRateAsync(string source, string target, DateOnly date, CancellationToken ct);
}

public sealed record ConversionSnapshot(decimal Rate, DateOnly RateDate, decimal AmountBase);

public interface IConversionService
{
    Task<ConversionSnapshot> ConvertAsync(decimal amount, string source, string target, DateOnly date, CancellationToken ct);
}

public sealed class FinanceException(int statusCode, string safeMessage) : Exception(safeMessage)
{
    public int StatusCode { get; } = statusCode;
}

public interface IBudgetService
{
    Task<FinanceResult<BudgetResponse>> CreateAsync(CreateBudgetRequest request, CancellationToken ct);
    Task<FinanceResult<PagedResponse<BudgetResponse>>> ListAsync(BudgetQuery query, CancellationToken ct);
    Task<FinanceResult<BudgetResponse>> GetAsync(int id, CancellationToken ct);
    Task<FinanceResult<BudgetResponse>> UpdateAsync(int id, UpdateBudgetRequest request, CancellationToken ct);
    Task<FinanceResult<bool>> DeleteAsync(int id, CancellationToken ct);
}

public readonly record struct BudgetPeriod(int CategoryId, DateOnly PeriodMonth)
{
    public static BudgetPeriod For(int categoryId, DateOnly date) => new(categoryId, new(date.Year, date.Month, 1));
}

public interface IBudgetUsageService
{
    Task<IReadOnlyList<BudgetResponse>> ReadAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct);
    Task<IReadOnlyList<BudgetResponse>> RecalculateAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct);
}

public interface ISavingsGoalService
{
    Task<FinanceResult<SavingsGoalResponse>> CreateAsync(CreateSavingsGoalRequest request, CancellationToken ct);
    Task<FinanceResult<PagedResponse<SavingsGoalResponse>>> ListAsync(SavingsGoalQuery query, CancellationToken ct);
    Task<FinanceResult<SavingsGoalResponse>> GetAsync(int id, CancellationToken ct);
    Task<FinanceResult<SavingsGoalResponse>> UpdateAsync(int id, UpdateSavingsGoalRequest request, string? expectedVersion, CancellationToken ct);
    Task<FinanceResult<SavingsGoalResponse>> CancelAsync(int id, string? expectedVersion, CancellationToken ct);
    Task<FinanceResult<bool>> DeleteAsync(int id, string? expectedVersion, CancellationToken ct);
    Task<FinanceResult<ContributionCreateResponse>> CreateContributionAsync(int goalId, CreateGoalContributionRequest request, CancellationToken ct);
    Task<FinanceResult<PagedResponse<GoalContributionResponse>>> ListContributionsAsync(int goalId, GoalContributionQuery query, CancellationToken ct);
    Task<FinanceResult<GoalContributionResponse>> GetContributionAsync(int goalId, int contributionId, CancellationToken ct);
    Task<FinanceResult<GoalContributionMutationResponse>> UpdateContributionAsync(int goalId, int contributionId,
        UpdateGoalContributionRequest request, string? expectedVersion, CancellationToken ct);
    Task<FinanceResult<ContributionDeletionResponse>> DeleteContributionAsync(int goalId, int contributionId,
        string? expectedVersion, CancellationToken ct);
}

public interface IBillPaymentService
{
    Task<FinanceResult<BillPaymentResponse>> PayAsync(
        int billId,
        PayBillRequest request,
        CancellationToken ct);
}
