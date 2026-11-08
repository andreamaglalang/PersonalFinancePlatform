using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class BudgetService(ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes,
    IBudgetUsageService usage) : FinanceServiceBase(db, current, writes), IBudgetService
{
    public Task<FinanceResult<BudgetResponse>> CreateAsync(CreateBudgetRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        Validate(request);
        if (request.PeriodMonth is null || request.PeriodMonth.Value.Day != 1)
            throw new FinanceException(400, "PeriodMonth must be the first day of a month.");
        return await UniqueWrite(async () => await Writes.ExecuteAsync(
            [FinanceValidation.UserLock(userId), FinanceValidation.CategoryLock(request.CategoryId!.Value)], async token =>
        {
            var category = await FinanceData.LockedCategory(Db, request.CategoryId.Value).AsNoTracking()
                .SingleOrDefaultAsync(c => c.UserId == userId || c.UserId == null, token);
            if (category is null || !category.IsActive || category.Type != TransactionType.Expense)
                throw new FinanceException(400, "An active, accessible expense category is required.");
            if (await Db.Budgets.AnyAsync(b => b.UserId == userId && b.CategoryId == category.CategoryId
                && b.PeriodMonth == request.PeriodMonth.Value, token))
                throw new FinanceException(409, "A budget already exists for this category and month.");
            var budget = new Budget { UserId = userId, CategoryId = category.CategoryId,
                PeriodMonth = request.PeriodMonth.Value, LimitAmount = request.LimitAmount!.Value,
                AlertThresholdPct = (byte)request.AlertThresholdPct!.Value };
            Db.Budgets.Add(budget);
            await Db.SaveChangesAsync(token);
            return FinanceResult<BudgetResponse>.Success(
                (await usage.RecalculateAsync(userId, [new(budget.CategoryId, budget.PeriodMonth)], token)).Single(), 201);
        }, ct));
    });

    public Task<FinanceResult<PagedResponse<BudgetResponse>>> ListAsync(BudgetQuery query, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        if (!FinanceValidation.ValidPage(query.Page, query.PageSize) || query.CategoryId is <= 0
            || query.PeriodMonth is { Day: not 1 })
            throw new FinanceException(400, "Invalid budget filters or pagination.");
        var rows = Db.Budgets.AsNoTracking().Where(b => b.UserId == userId);
        if (query.CategoryId.HasValue) rows = rows.Where(b => b.CategoryId == query.CategoryId.Value);
        if (query.PeriodMonth.HasValue) rows = rows.Where(b => b.PeriodMonth == query.PeriodMonth.Value);
        var count = await rows.LongCountAsync(ct);
        var page = await rows.OrderByDescending(b => b.PeriodMonth).ThenBy(b => b.CategoryId).ThenBy(b => b.BudgetId)
            .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize)
            .Select(b => new { b.BudgetId, b.CategoryId, b.PeriodMonth }).ToListAsync(ct);
        var results = await usage.ReadAsync(userId, page.Select(b => new BudgetPeriod(b.CategoryId, b.PeriodMonth)).ToArray(), ct);
        var lookup = results.ToDictionary(b => b.BudgetId);
        return FinanceResult<PagedResponse<BudgetResponse>>.Success(new(
            page.Select(b => lookup.GetValueOrDefault(b.BudgetId)).OfType<BudgetResponse>().ToArray(), query.Page, query.PageSize, count));
    });

    public Task<FinanceResult<BudgetResponse>> GetAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var budget = await Owned(id, userId).AsNoTracking().SingleOrDefaultAsync(ct);
        if (budget is null) return FinanceResult<BudgetResponse>.Failure(404, "Budget not found.");
        var result = (await usage.ReadAsync(userId, [new(budget.CategoryId, budget.PeriodMonth)], ct)).SingleOrDefault();
        return result is null ? FinanceResult<BudgetResponse>.Failure(404, "Budget not found.") : FinanceResult<BudgetResponse>.Success(result);
    });

    public Task<FinanceResult<BudgetResponse>> UpdateAsync(int id, UpdateBudgetRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var original = await Owned(id, userId).AsNoTracking().SingleOrDefaultAsync(ct);
        if (original is null) return FinanceResult<BudgetResponse>.Failure(404, "Budget not found.");
        Validate(request);
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId), FinanceValidation.CategoryLock(original.CategoryId)], async token =>
        {
            var budget = await Owned(id, userId).SingleOrDefaultAsync(token);
            if (budget is null) return FinanceResult<BudgetResponse>.Failure(404, "Budget not found.");
            CheckUnchanged(budget, original);
            budget.LimitAmount = request.LimitAmount!.Value;
            budget.AlertThresholdPct = (byte)request.AlertThresholdPct!.Value;
            await Db.SaveChangesAsync(token);
            return FinanceResult<BudgetResponse>.Success(
                (await usage.RecalculateAsync(userId, [new(budget.CategoryId, budget.PeriodMonth)], token)).Single());
        }, ct);
    });

    public Task<FinanceResult<bool>> DeleteAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var original = await Owned(id, userId).AsNoTracking().SingleOrDefaultAsync(ct);
        if (original is null) return FinanceResult<bool>.Failure(404, "Budget not found.");
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId), FinanceValidation.CategoryLock(original.CategoryId)], async token =>
        {
            var budget = await Owned(id, userId).SingleOrDefaultAsync(token);
            if (budget is null) return FinanceResult<bool>.Failure(404, "Budget not found.");
            CheckUnchanged(budget, original);
            // No notification producer exists yet. Use the entity name as reference discriminator,
            // accepting case variants and the existing NotificationKeys budget-transition key convention.
            // Check all history without rewriting it, including older notifications lacking typed references.
            var transitionKeyPrefix = $"budget:{id}:transition:";
            if (await Db.Notifications.AnyAsync(n =>
                (n.ReferenceId == id && n.ReferenceType != null && n.ReferenceType.ToUpper() == "BUDGET")
                || n.DeduplicationKey.StartsWith(transitionKeyPrefix), token))
                throw new FinanceException(409, "This budget is referenced by notification history and cannot be deleted.");
            Db.Budgets.Remove(budget);
            await Db.SaveChangesAsync(token);
            return FinanceResult<bool>.Success(true, 204);
        }, ct);
    });

    private IQueryable<Budget> Owned(int id, int userId) => Db.Budgets.Where(b => b.BudgetId == id && b.UserId == userId);
    private static void Validate(UpdateBudgetRequest request)
    {
        if (!FinanceValidation.Valid(request)) throw new FinanceException(400, "Invalid budget data.");
        FinanceValidation.ValidateMoney(request.LimitAmount!.Value);
    }
    private static void CheckUnchanged(Budget budget, Budget original)
    {
        if (budget.LimitAmount != original.LimitAmount || budget.AlertThresholdPct != original.AlertThresholdPct)
            throw new FinanceException(409, "Budget changed during the request. Reload and retry.");
    }
    private async Task<FinanceResult<BudgetResponse>> UniqueWrite(Func<Task<FinanceResult<BudgetResponse>>> operation)
    {
        try { return await operation(); }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 2601 or 2627 })
        {
            // The write scope has rolled back; discard its tracked state.
            Db.ChangeTracker.Clear();
            return FinanceResult<BudgetResponse>.Failure(409, "A budget already exists for this category and month.");
        }
    }
}