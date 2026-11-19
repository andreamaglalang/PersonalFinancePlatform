using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public interface IRecurringBillService
{
    Task<FinanceResult<RecurringBillResponse>> CreateAsync(CreateRecurringBillRequest request, CancellationToken ct);
    Task<FinanceResult<PagedResponse<RecurringBillResponse>>> ListAsync(RecurringBillQuery query, CancellationToken ct);
    Task<FinanceResult<RecurringBillResponse>> GetAsync(int id, CancellationToken ct);
    Task<FinanceResult<RecurringBillResponse>> UpdateAsync(int id, UpdateRecurringBillRequest request, CancellationToken ct);
    Task<FinanceResult<bool>> DeleteAsync(int id, CancellationToken ct);
}

/// <summary>
/// Member-owned recurring bills. Every query is scoped to the current user. Writes take the same user, bill and
/// category locks as <see cref="BillPaymentService"/>, so an edit can never interleave with a payment. Payment
/// (creating the expense transaction and advancing NextDueDate) stays in BillPaymentService.
/// </summary>
public sealed class RecurringBillService(ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes)
    : FinanceServiceBase(db, current, writes), IRecurringBillService
{
    public Task<FinanceResult<RecurringBillResponse>> CreateAsync(CreateRecurringBillRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        Validate(request);
        if (!Enum.TryParse<BillFrequency>(request.Frequency, ignoreCase: false, out var frequency) || !Enum.IsDefined(frequency))
            throw new FinanceException(400, "Frequency must be Weekly, Monthly, Quarterly or Yearly.");
        var first = request.FirstDueDate ?? throw new FinanceException(400, "FirstDueDate is required.");
        if (first.Year is < 2000 or > 9998) throw new FinanceException(400, "FirstDueDate is out of range.");
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId), FinanceValidation.CategoryLock(request.CategoryId!.Value)], async token =>
        {
            var category = await AccessibleExpenseCategory(userId, request.CategoryId.Value, token);
            var bill = new RecurringBill
            {
                UserId = userId, CategoryId = category.CategoryId, Name = request.Name.Trim(), Amount = request.Amount!.Value,
                CurrencyCode = request.CurrencyCode, Frequency = frequency, AnchorDate = first, NextDueDate = first,
                // Monthly-style schedules keep the intended day so short months clamp and later months recover.
                IntendedDayOfMonth = frequency == BillFrequency.Weekly ? null : (byte)first.Day,
                ReminderDaysBefore = (byte)request.ReminderDaysBefore!.Value, IsActive = request.IsActive ?? true,
            };
            Db.RecurringBills.Add(bill);
            await Db.SaveChangesAsync(token);
            return FinanceResult<RecurringBillResponse>.Success(Describe(bill, category.Name), 201);
        }, ct);
    });

    public Task<FinanceResult<PagedResponse<RecurringBillResponse>>> ListAsync(RecurringBillQuery query, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        if (!FinanceValidation.ValidPage(query.Page, query.PageSize))
            throw new FinanceException(400, "Invalid bill filters or pagination.");
        var rows = Db.RecurringBills.AsNoTracking().Where(b => b.UserId == userId);
        if (!query.IncludeInactive) rows = rows.Where(b => b.IsActive);
        if (query.DueOnOrBefore.HasValue) rows = rows.Where(b => b.NextDueDate <= query.DueOnOrBefore.Value);
        var count = await rows.LongCountAsync(ct);
        var page = await rows.OrderBy(b => b.NextDueDate).ThenBy(b => b.BillId)
            .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize)
            .Select(b => new { Bill = b, CategoryName = b.Category.Name }).ToListAsync(ct);
        return FinanceResult<PagedResponse<RecurringBillResponse>>.Success(
            new(page.Select(x => Describe(x.Bill, x.CategoryName)).ToArray(), query.Page, query.PageSize, count));
    });

    public Task<FinanceResult<RecurringBillResponse>> GetAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        var row = await Db.RecurringBills.AsNoTracking().Where(b => b.BillId == id && b.UserId == userId)
            .Select(b => new { Bill = b, CategoryName = b.Category.Name }).SingleOrDefaultAsync(ct);
        return row is null ? FinanceResult<RecurringBillResponse>.Failure(404, "Bill not found.")
            : FinanceResult<RecurringBillResponse>.Success(Describe(row.Bill, row.CategoryName));
    });

    public Task<FinanceResult<RecurringBillResponse>> UpdateAsync(int id, UpdateRecurringBillRequest request, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        Validate(request);
        var original = await Owned(id, userId).AsNoTracking().SingleOrDefaultAsync(ct);
        if (original is null) return FinanceResult<RecurringBillResponse>.Failure(404, "Bill not found.");
        var locks = new[] { FinanceValidation.UserLock(userId), $"finance:write:bill:{id}",
            FinanceValidation.CategoryLock(original.CategoryId), FinanceValidation.CategoryLock(request.CategoryId!.Value) }
            .Distinct().Order(StringComparer.Ordinal).ToArray();
        return await Writes.ExecuteAsync(locks, async token =>
        {
            var bill = await Owned(id, userId).SingleOrDefaultAsync(token);
            if (bill is null) return FinanceResult<RecurringBillResponse>.Failure(404, "Bill not found.");
            // A payment between the read and the lock moves NextDueDate; reject instead of overwriting.
            if (bill.NextDueDate != original.NextDueDate || bill.CategoryId != original.CategoryId)
                throw new FinanceException(409, "The bill changed during the request. Reload and retry.");
            var category = await AccessibleExpenseCategory(userId, request.CategoryId.Value, token, allowCurrent: bill.CategoryId);
            bill.Name = request.Name.Trim();
            bill.CategoryId = category.CategoryId;
            bill.Amount = request.Amount!.Value;
            bill.CurrencyCode = request.CurrencyCode;
            bill.ReminderDaysBefore = (byte)request.ReminderDaysBefore!.Value;
            if (request.IsActive.HasValue) bill.IsActive = request.IsActive.Value;
            await Db.SaveChangesAsync(token);
            return FinanceResult<RecurringBillResponse>.Success(Describe(bill, category.Name));
        }, ct);
    });

    public Task<FinanceResult<bool>> DeleteAsync(int id, CancellationToken ct) => Guard(async () =>
    {
        var userId = UserId;
        if (await Owned(id, userId).AsNoTracking().AnyAsync(ct) is false)
            return FinanceResult<bool>.Failure(404, "Bill not found.");
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId), $"finance:write:bill:{id}"], async token =>
        {
            var bill = await Owned(id, userId).SingleOrDefaultAsync(token);
            if (bill is null) return FinanceResult<bool>.Failure(404, "Bill not found.");
            // Paid bills keep their payment history: deactivate them instead.
            if (await Db.BillPaymentReceipts.AnyAsync(r => r.BillId == id, token)
                || await Db.Transactions.AnyAsync(t => t.BillId == id, token))
                throw new FinanceException(409, "This bill has payment history and cannot be deleted. Deactivate it instead.");
            Db.RecurringBills.Remove(bill);
            await Db.SaveChangesAsync(token);
            return FinanceResult<bool>.Success(true, 204);
        }, ct);
    });

    private IQueryable<RecurringBill> Owned(int id, int userId) => Db.RecurringBills.Where(b => b.BillId == id && b.UserId == userId);

    private async Task<Category> AccessibleExpenseCategory(int userId, int categoryId, CancellationToken ct, int? allowCurrent = null)
    {
        var category = await FinanceData.LockedCategory(Db, categoryId).AsNoTracking()
            .SingleOrDefaultAsync(c => c.UserId == userId || c.UserId == null, ct);
        // Keeping an existing (now inactive) category is allowed; choosing a new inactive one is not.
        if (category is null || category.Type != TransactionType.Expense || (!category.IsActive && category.CategoryId != allowCurrent))
            throw new FinanceException(400, "An active, accessible expense category is required.");
        return category;
    }

    private static void Validate(UpdateRecurringBillRequest request)
    {
        if (!FinanceValidation.Valid(request) || string.IsNullOrWhiteSpace(request.Name) || !FinanceValidation.CurrencyCode(request.CurrencyCode))
            throw new FinanceException(400, "Invalid bill data.");
        FinanceValidation.ValidateMoney(request.Amount!.Value);
    }

    private static RecurringBillResponse Describe(RecurringBill b, string categoryName) => new(b.BillId, b.Name, b.CategoryId, categoryName,
        b.Amount, b.CurrencyCode, b.Frequency.ToString(), b.AnchorDate, b.NextDueDate, b.ReminderDaysBefore, b.IsActive);
}
