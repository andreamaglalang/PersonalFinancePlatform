
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class BillPaymentService(
    ApplicationDbContext db,
    ICurrentUser current,
    IFinancialWriteScope writes,
    IConversionService conversion,
    TimeProvider clock,
    IBudgetUsageService budgets)
    : FinanceServiceBase(db, current, writes), IBillPaymentService
{
    public const string PaymentDescription = "Recurring bill payment";

    public Task<FinanceResult<BillPaymentResponse>> PayAsync(
        int billId,
        PayBillRequest request,
        CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;

            if (billId <= 0
                || !FinanceValidation.Valid(request)
                || request.ClientId is null
                || request.ClientId == Guid.Empty
                || request.DueDate is null)
            {
                throw new FinanceException(
                    400,
                    "A bill, nonempty ClientId and DueDate are required.");
            }

            RequireCleanContext();

            var clientId = request.ClientId.Value;
            var due = request.DueDate.Value;

            var preliminary = await Db.RecurringBills.AsNoTracking()
                .SingleOrDefaultAsync(
                    b => b.BillId == billId && b.UserId == userId,
                    ct)
                ?? throw new FinanceException(404, "Bill not found.");

            var resources = new[]
            {
            FinanceValidation.UserLock(userId),
            $"finance:write:bill:{billId}",
            FinanceValidation.CategoryLock(preliminary.CategoryId)
        }.Order(StringComparer.Ordinal).ToArray();

            try
            {
                return await Writes.ExecuteAsync(resources, async token =>
                {
                    var bill = await Db.RecurringBills.SingleOrDefaultAsync(
                        b => b.BillId == billId && b.UserId == userId,
                        token)
                        ?? throw new FinanceException(404, "Bill not found.");

                    var accepted = await ReceiptAsync(userId, clientId, token);

                    if (accepted is not null)
                    {
                        return await ReplayAsync(
                            accepted,
                            userId,
                            billId,
                            due,
                            token);
                    }

                    if (!bill.IsActive)
                    {
                        throw new FinanceException(
                            409,
                            "Inactive bills cannot accept new payments.");
                    }

                    if (bill.CategoryId != preliminary.CategoryId)
                    {
                        throw new FinanceException(
                            409,
                            "Bill category changed. Reload and retry.");
                    }

                    if (due != bill.NextDueDate)
                    {
                        throw new FinanceException(
                            409,
                            "Only the oldest outstanding occurrence may be paid.");
                    }

                    var quoteDate = DateOnly.FromDateTime(
                        clock.GetUtcNow().UtcDateTime);

                    if (due > quoteDate)
                    {
                        throw new FinanceException(
                            409,
                            "Future occurrences cannot be paid.");
                    }

                    if (bill.ReminderDaysBefore > 30)
                    {
                        throw new FinanceException(
                            409,
                            "The bill schedule requires reconciliation.");
                    }

                    var category = await FinanceData
                        .LockedCategory(Db, bill.CategoryId)
                        .AsNoTracking()
                        .SingleOrDefaultAsync(
                            c => c.UserId == userId || c.UserId == null,
                            token);

                    if (category is null
                        || !category.IsActive
                        || category.Type != TransactionType.Expense)
                    {
                        throw new FinanceException(
                            409,
                            "An active accessible expense category is required.");
                    }

                    FinanceValidation.ValidateMoney(bill.Amount);

                    if (!FinanceValidation.CurrencyCode(bill.CurrencyCode))
                    {
                        throw new FinanceException(
                            409,
                            "The bill currency requires reconciliation.");
                    }

                    if (await Db.Transactions.AnyAsync(
                        t => t.UserId == userId && t.ClientId == clientId,
                        token))
                    {
                        throw new FinanceException(
                            409,
                            "ClientId is already used by a transaction without this accepted payment.");
                    }

                    if (await Db.BillPaymentReceipts.AnyAsync(
                            r => r.BillId == billId && r.DueDate == due,
                            token)
                        || await Db.Transactions.AnyAsync(
                            t => t.BillId == billId && t.BillDueDate == due,
                            token))
                    {
                        throw new FinanceException(
                            409,
                            "This occurrence already has payment history.");
                    }

                    var next = ValidateScheduleAndGetNextDueDate(bill);

                    var baseCurrency = await FinanceData.BaseCurrencyAsync(
                        Db,
                        userId,
                        true,
                        token)
                        ?? throw new FinanceException(
                            401,
                            "Account unavailable.");

                    var fx = await conversion.ConvertAsync(
                        bill.Amount,
                        bill.CurrencyCode,
                        baseCurrency,
                        quoteDate,
                        token);

                    var now = clock.GetUtcNow().UtcDateTime;
                    var paidOn = DateOnly.FromDateTime(now);

                    if (paidOn != quoteDate)
                    {
                        throw new FinanceException(
                            409,
                            "UTC business date changed. Retry the payment.");
                    }

                    var transaction = new FinanceTransaction
                    {
                        UserId = userId,
                        ClientId = clientId,
                        BillId = billId,
                        BillDueDate = due,
                        CategoryId = bill.CategoryId,
                        Type = TransactionType.Expense,
                        Amount = bill.Amount,
                        CurrencyCode = bill.CurrencyCode,
                        FxRate = fx.Rate,
                        FxRateDate = fx.RateDate,
                        AmountBase = fx.AmountBase,
                        TransactionDate = paidOn,
                        Description = PaymentDescription,
                        CreatedAtUtc = now
                    };

                    Db.Transactions.Add(transaction);
                    await Db.SaveChangesAsync(token);

                    var receipt = new BillPaymentReceipt
                    {
                        UserId = userId,
                        ClientId = clientId,
                        BillId = billId,
                        TransactionId = transaction.TransactionId,
                        DueDate = due,
                        CategoryId = bill.CategoryId,
                        Amount = bill.Amount,
                        CurrencyCode = bill.CurrencyCode,
                        FxRate = fx.Rate,
                        FxRateDate = fx.RateDate,
                        AmountBase = fx.AmountBase,
                        BaseCurrency = baseCurrency,
                        PaidOn = paidOn,
                        CanonicalVersion = BillPaymentIdempotency.CanonicalVersion,
                        RecordedAtUtc = now
                    };

                    Db.BillPaymentReceipts.Add(receipt);
                    bill.NextDueDate = next;

                    await Db.SaveChangesAsync(token);

                    var affected = await budgets.RecalculateAsync(
                        userId,
                        [BudgetPeriod.For(bill.CategoryId, paidOn)],
                        token);

                    return FinanceResult<BillPaymentResponse>.Success(
                        new(Response(receipt), false, affected),
                        201);
                }, ct);
            }
            catch (DbUpdateConcurrencyException)
            {
                Db.ChangeTracker.Clear();

                throw new FinanceException(
                    409,
                    "Financial records changed. Reload and retry.");
            }
            catch (DbUpdateException ex)
                when (ex.InnerException is SqlException
                {
                    Number: 2601 or 2627
                })
            {
                Db.ChangeTracker.Clear();

                return await Writes.ExecuteAsync(resources, async token =>
                {
                    if (!await Db.RecurringBills.AsNoTracking().AnyAsync(
                        b => b.BillId == billId && b.UserId == userId,
                        token))
                    {
                        throw new FinanceException(404, "Bill not found.");
                    }

                    var winner = await ReceiptAsync(userId, clientId, token)
                        ?? throw new FinanceException(
                            409,
                            "The payment conflicts with existing financial records.");

                    return await ReplayAsync(
                        winner,
                        userId,
                        billId,
                        due,
                        token);
                }, ct);
            }
            catch
            {
                Db.ChangeTracker.Clear();
                throw;
            }
        });

    private void RequireCleanContext()
    {
        var previous = Db.ChangeTracker.AutoDetectChangesEnabled;

        try
        {
            Db.ChangeTracker.AutoDetectChangesEnabled = false;

            if (Db.ChangeTracker.Entries().Any())
            {
                throw new FinanceException(
                    409,
                    "Payment requires a clean DbContext. Use a fresh scope and retry.");
            }
        }
        finally
        {
            Db.ChangeTracker.AutoDetectChangesEnabled = previous;
        }
    }

    private static DateOnly ValidateScheduleAndGetNextDueDate(
        RecurringBill bill)
    {
        var anchor = bill.AnchorDate;
        var due = bill.NextDueDate;

        if (anchor == DateOnly.MinValue || due < anchor)
        {
            throw new FinanceException(
                409,
                "The bill schedule requires reconciliation.");
        }

        try
        {
            if (bill.Frequency == BillFrequency.Weekly)
            {
                if (bill.IntendedDayOfMonth is not null
                    || (due.DayNumber - anchor.DayNumber) % 7 != 0)
                {
                    throw new FinanceException(
                        409,
                        "The bill schedule requires reconciliation.");
                }
            }
            else
            {
                var intervalMonths = bill.Frequency switch
                {
                    BillFrequency.Monthly => 1,
                    BillFrequency.Quarterly => 3,
                    BillFrequency.Yearly => 12,
                    _ => throw new FinanceException(
                        409,
                        "The bill schedule requires reconciliation.")
                };

                if (bill.IntendedDayOfMonth is null or < 1 or > 31)
                {
                    throw new FinanceException(
                        409,
                        "The bill schedule requires reconciliation.");
                }

                var intendedDay = bill.IntendedDayOfMonth.Value;

                var expectedAnchorDay = Math.Min(
                    intendedDay,
                    DateTime.DaysInMonth(anchor.Year, anchor.Month));

                var expectedDueDay = Math.Min(
                    intendedDay,
                    DateTime.DaysInMonth(due.Year, due.Month));

                var elapsedMonths = checked(
                    (due.Year - anchor.Year) * 12
                    + due.Month
                    - anchor.Month);

                if (anchor.Day != expectedAnchorDay
                    || elapsedMonths < 0
                    || elapsedMonths % intervalMonths != 0
                    || due.Day != expectedDueDay)
                {
                    throw new FinanceException(
                        409,
                        "The bill schedule requires reconciliation.");
                }
            }

            var next = BillRecurrence.NextDueDate(
                due,
                bill.Frequency,
                bill.IntendedDayOfMonth);

            if (next <= due)
            {
                throw new FinanceException(
                    409,
                    "The recurrence cannot advance safely.");
            }

            return next;
        }
        catch (ArgumentException)
        {
            throw new FinanceException(
                409,
                "The recurrence cannot advance safely.");
        }
        catch (OverflowException)
        {
            throw new FinanceException(
                409,
                "The recurrence cannot advance safely.");
        }
    }

    private Task<BillPaymentReceipt?> ReceiptAsync(
        int userId,
        Guid clientId,
        CancellationToken ct) =>
        Db.BillPaymentReceipts.AsNoTracking().SingleOrDefaultAsync(
            r => r.UserId == userId && r.ClientId == clientId,
            ct);

    private async Task<FinanceResult<BillPaymentResponse>> ReplayAsync(
        BillPaymentReceipt receipt,
        int userId,
        int billId,
        DateOnly due,
        CancellationToken ct)
    {
        if (!BillPaymentIdempotency.Matches(receipt, billId, due))
        {
            throw new FinanceException(
                409,
                "ClientId was accepted for a different bill or occurrence.");
        }

        var row = await Db.Transactions.AsNoTracking()
            .SingleOrDefaultAsync(
                t => t.TransactionId == receipt.TransactionId
                    && t.UserId == userId,
                ct);

        if (receipt.UserId != userId
            || row is null
            || row.ClientId != receipt.ClientId
            || row.BillId != receipt.BillId
            || row.BillDueDate != receipt.DueDate
            || row.Type != TransactionType.Expense
            || row.CategoryId != receipt.CategoryId
            || row.Amount != receipt.Amount
            || row.CurrencyCode != receipt.CurrencyCode
            || row.FxRate != receipt.FxRate
            || row.FxRateDate != receipt.FxRateDate
            || row.AmountBase != receipt.AmountBase
            || row.TransactionDate != receipt.PaidOn
            || row.CreatedAtUtc != receipt.RecordedAtUtc
            || row.Description != PaymentDescription
            || receipt.DueDate > receipt.PaidOn
            || receipt.PaidOn != DateOnly.FromDateTime(receipt.RecordedAtUtc)
            || !FinanceValidation.CurrencyCode(receipt.BaseCurrency))
        {
            throw new FinanceException(
                409,
                "Accepted payment history requires reconciliation.");
        }

        return FinanceResult<BillPaymentResponse>.Success(
            new(Response(receipt), true, null));
    }

    private static BillPaymentReceiptResponse Response(
        BillPaymentReceipt r) => new(
            r.ReceiptId,
            r.UserId,
            r.ClientId,
            r.BillId,
            r.TransactionId,
            r.DueDate,
            r.CategoryId,
            r.Amount,
            r.CurrencyCode,
            r.FxRate,
            r.FxRateDate,
            r.AmountBase,
            r.BaseCurrency,
            r.PaidOn,
            r.CanonicalVersion,
            r.RecordedAtUtc);
}
