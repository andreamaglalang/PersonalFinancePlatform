
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class TransactionService(
    ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes,
    IConversionService conversion, TimeProvider clock, IBudgetUsageService budgets)
    : FinanceServiceBase(db, current, writes), ITransactionService
{
    public Task<FinanceResult<TransactionResponse>> CreateAsync(CreateTransactionRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        Validate(request);
        if (request.ClientId is null || request.ClientId == Guid.Empty)
            throw new FinanceException(400, "A nonempty client-generated ClientId is required.");
        var clientId = request.ClientId.Value;
        var existing = await ByClientAsync(userId, clientId, ct);
        if (existing is not null)
            return FinanceResult<TransactionResponse>.Success(await WithUsageAsync(userId, existing, ct));

        await ValidateCategoryAsync(request.CategoryId!.Value, request.Type, false, false, ct);
        var target = await FinanceData.BaseCurrencyAsync(Db, userId, false, ct)
            ?? throw new FinanceException(401, "Account unavailable.");
        var snapshot = await conversion.ConvertAsync(request.Amount!.Value, request.CurrencyCode, target, request.TransactionDate!.Value, ct);

        try
        {
            return await Writes.ExecuteAsync([
                FinanceValidation.UserLock(userId),
                FinanceValidation.CategoryLock(request.CategoryId.Value)], async token =>
                {
                    existing = await ByClientAsync(userId, clientId, token);
                    if (existing is not null)
                        return FinanceResult<TransactionResponse>.Success(await WithUsageAsync(userId, existing, ct));

                    await ValidateCategoryAsync(request.CategoryId.Value, request.Type, false, true, token);
                    if (await FinanceData.BaseCurrencyAsync(Db, userId, true, token) != target)
                        throw new FinanceException(409, "Account currency changed. Retry the operation.");

                    var row = new FinanceTransaction
                    {
                        UserId = userId,
                        ClientId = clientId,
                        Type = FinanceValidation.ParseType(request.Type),
                        CategoryId = request.CategoryId.Value,
                        Amount = request.Amount.Value,
                        CurrencyCode = request.CurrencyCode,
                        TransactionDate = request.TransactionDate.Value,
                        Description = request.Description,
                        FxRate = snapshot.Rate,
                        FxRateDate = snapshot.RateDate,
                        AmountBase = snapshot.AmountBase,
                        CreatedAtUtc = clock.GetUtcNow().UtcDateTime
                    };

                    Db.Transactions.Add(row);
                    await Db.SaveChangesAsync(token);
                    var affected = await budgets.RecalculateAsync(userId, Periods(row), token);

                    return FinanceResult<TransactionResponse>.Success(
                        (await ByIdAsync(userId, row.TransactionId, token))! with { AffectedBudgets = affected }, 201);
                }, ct);
        }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 2601 or 2627 })
        {
            foreach (var entry in Db.ChangeTracker.Entries<FinanceTransaction>()
                .Where(e => e.State == EntityState.Added).ToArray())
                entry.State = EntityState.Detached;

            var winner = await ByClientAsync(userId, clientId, ct);
            if (winner is not null)
                return FinanceResult<TransactionResponse>.Success(await WithUsageAsync(userId, winner, ct));

            throw;
        }
    });

    public Task<FinanceResult<PagedResponse<TransactionResponse>>> ListAsync(TransactionQuery query, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        if (!FinanceValidation.ValidPage(query.Page, query.PageSize)
            || (query.Type is not null && !FinanceValidation.ValidType(query.Type))
            || query.CategoryId is <= 0
            || (query.FromDate.HasValue && query.ToDate.HasValue && query.FromDate > query.ToDate)
            || query.SortBy is not ("date" or "amount" or "createdAt")
            || query.SortDirection is not ("asc" or "desc"))
            throw new FinanceException(400, "Invalid transaction filters, sorting, or pagination.");

        var rows = Db.Transactions.AsNoTracking().Where(t => t.UserId == userId);

        if (query.Type is not null)
        {
            var type = FinanceValidation.ParseType(query.Type);
            rows = rows.Where(t => t.Type == type);
        }

        if (query.CategoryId.HasValue)
            rows = rows.Where(t => t.CategoryId == query.CategoryId.Value);

        if (query.FromDate.HasValue)
            rows = rows.Where(t => t.TransactionDate >= query.FromDate.Value);

        if (query.ToDate.HasValue)
            rows = rows.Where(t => t.TransactionDate <= query.ToDate.Value);

        var count = await rows.LongCountAsync(ct);
        var descending = query.SortDirection == "desc";

        IOrderedQueryable<FinanceTransaction> ordered = query.SortBy switch
        {
            "amount" => descending
                ? rows.OrderByDescending(t => t.Amount)
                : rows.OrderBy(t => t.Amount),
            "createdAt" => descending
                ? rows.OrderByDescending(t => t.CreatedAtUtc)
                : rows.OrderBy(t => t.CreatedAtUtc),
            _ => descending
                ? rows.OrderByDescending(t => t.TransactionDate)
                : rows.OrderBy(t => t.TransactionDate)
        };

        ordered = descending
            ? ordered.ThenByDescending(t => t.TransactionId)
            : ordered.ThenBy(t => t.TransactionId);

        var items = await ordered
            .Skip((query.Page - 1) * query.PageSize)
            .Take(query.PageSize)
            .Select(FinanceData.TransactionProjection)
            .ToListAsync(ct);

        return FinanceResult<PagedResponse<TransactionResponse>>.Success(
            new(items, query.Page, query.PageSize, count));
    });

    public Task<FinanceResult<TransactionResponse>> GetAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var item = await ByIdAsync(UserId, id, ct);
        return item is null
            ? FinanceResult<TransactionResponse>.Failure(404, "Transaction not found.")
            : FinanceResult<TransactionResponse>.Success(item);
    });

    public Task<FinanceResult<TransactionResponse>> UpdateAsync(int id, TransactionWriteRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var original = await Db.Transactions.AsNoTracking()
            .SingleOrDefaultAsync(t => t.TransactionId == id && t.UserId == userId, ct);

        if (original is null)
            return FinanceResult<TransactionResponse>.Failure(404, "Transaction not found.");

        ProtectBillPayment(original);
        Validate(request);

        await ValidateCategoryAsync(
            request.CategoryId!.Value,
            request.Type,
            request.CategoryId == original.CategoryId,
            false,
            ct);

        ConversionSnapshot? replacement = null;
        string? target = null;

        if (request.CurrencyCode != original.CurrencyCode
            || request.TransactionDate != original.TransactionDate)
        {
            target = await FinanceData.BaseCurrencyAsync(Db, userId, false, ct)
                ?? throw new FinanceException(401, "Account unavailable.");

            replacement = await conversion.ConvertAsync(
                request.Amount!.Value,
                request.CurrencyCode,
                target,
                request.TransactionDate!.Value,
                ct);
        }
        else if (request.Amount != original.Amount)
        {
            replacement = new(
                original.FxRate,
                original.FxRateDate,
                FinanceValidation.ConvertAmount(request.Amount!.Value, original.FxRate));
        }

        return await Writes.ExecuteAsync([
            FinanceValidation.UserLock(userId),
            FinanceValidation.CategoryLock(original.CategoryId),
            FinanceValidation.CategoryLock(request.CategoryId.Value)], async token =>
            {
                var row = await Db.Transactions
                    .SingleOrDefaultAsync(t => t.TransactionId == id && t.UserId == userId, token);

                if (row is null)
                    return FinanceResult<TransactionResponse>.Failure(404, "Transaction not found.");

                ProtectBillPayment(row);

                if (!FinanceData.SameState(row, original))
                    throw new FinanceException(409, "Transaction changed during the request. Reload and retry.");

                await ValidateCategoryAsync(
                    request.CategoryId.Value,
                    request.Type,
                    request.CategoryId == row.CategoryId,
                    true,
                    token);

                if (target is not null
                    && await FinanceData.BaseCurrencyAsync(Db, userId, true, token) != target)
                    throw new FinanceException(409, "Account currency changed. Retry the operation.");

                row.Type = FinanceValidation.ParseType(request.Type);
                row.CategoryId = request.CategoryId.Value;
                row.Amount = request.Amount!.Value;
                row.CurrencyCode = request.CurrencyCode;
                row.TransactionDate = request.TransactionDate!.Value;
                row.Description = request.Description;

                if (replacement is not null)
                {
                    row.FxRate = replacement.Rate;
                    row.FxRateDate = replacement.RateDate;
                    row.AmountBase = replacement.AmountBase;
                }

                await Db.SaveChangesAsync(token);

                var affected = await budgets.RecalculateAsync(
                    userId,
                    Periods(original).Concat(Periods(row)).Distinct().ToArray(),
                    token);

                return FinanceResult<TransactionResponse>.Success(
                    (await ByIdAsync(userId, id, token))! with { AffectedBudgets = affected });
            }, ct);
    });

    public Task<FinanceResult<TransactionDeletionResponse>> DeleteAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var original = await Db.Transactions.AsNoTracking()
            .SingleOrDefaultAsync(t => t.TransactionId == id && t.UserId == userId, ct);

        if (original is null)
            return FinanceResult<TransactionDeletionResponse>.Failure(404, "Transaction not found.");

        ProtectBillPayment(original);

        return await Writes.ExecuteAsync([
            FinanceValidation.UserLock(userId),
            FinanceValidation.CategoryLock(original.CategoryId)], async token =>
            {
                var row = await Db.Transactions
                    .SingleOrDefaultAsync(t => t.TransactionId == id && t.UserId == userId, token);

                if (row is null)
                    return FinanceResult<TransactionDeletionResponse>.Failure(404, "Transaction not found.");

                ProtectBillPayment(row);

                if (!FinanceData.SameState(row, original))
                    throw new FinanceException(409, "Transaction changed. Reload and retry.");

                Db.Transactions.Remove(row);
                await Db.SaveChangesAsync(token);

                var affected = await budgets.RecalculateAsync(userId, Periods(original), token);

                return FinanceResult<TransactionDeletionResponse>.Success(
                    new(id, true, affected));
            }, ct);
    });

    private static void ProtectBillPayment(FinanceTransaction row)
    {
        if (row.BillId.HasValue || row.BillDueDate.HasValue)
            throw new FinanceException(
                409,
                "Bill-payment transactions cannot be modified or deleted.");
    }

    private static BudgetPeriod[] Periods(FinanceTransaction row) =>
        row.Type == PersonalFinance.Domain.Enums.TransactionType.Expense
            ? [BudgetPeriod.For(row.CategoryId, row.TransactionDate)]
            : [];

    private async Task<TransactionResponse> WithUsageAsync(
        int userId, TransactionResponse row, CancellationToken ct) =>
        row with
        {
            AffectedBudgets = await budgets.ReadAsync(
                userId,
                row.Type == "Expense"
                    ? [BudgetPeriod.For(row.CategoryId, row.TransactionDate)]
                    : [],
                ct)
        };

    private static void Validate(TransactionWriteRequest request)
    {
        if (!FinanceValidation.Valid(request))
            throw new FinanceException(400, "Invalid transaction data.");

        FinanceValidation.ValidateMoney(request.Amount!.Value);
    }

    private async Task ValidateCategoryAsync(
        int id, string type, bool allowInactive, bool locked, CancellationToken ct)
    {
        var rows = locked
            ? FinanceData.LockedCategory(Db, id)
            : Db.Categories.Where(c => c.CategoryId == id);

        var category = await rows.AsNoTracking()
            .SingleOrDefaultAsync(c => c.UserId == UserId || c.UserId == null, ct);

        if (category is null
            || (!category.IsActive && !allowInactive)
            || category.Type != FinanceValidation.ParseType(type))
            throw new FinanceException(
                400,
                "Category is unavailable or incompatible with the transaction type.");
    }

    private Task<TransactionResponse?> ByIdAsync(
        int userId, int id, CancellationToken ct) =>
        Db.Transactions.AsNoTracking()
            .Where(t => t.UserId == userId && t.TransactionId == id)
            .Select(FinanceData.TransactionProjection)
            .SingleOrDefaultAsync(ct);

    private Task<TransactionResponse?> ByClientAsync(
        int userId, Guid clientId, CancellationToken ct) =>
        Db.Transactions.AsNoTracking()
            .Where(t => t.UserId == userId && t.ClientId == clientId)
            .Select(FinanceData.TransactionProjection)
            .SingleOrDefaultAsync(ct);
}
