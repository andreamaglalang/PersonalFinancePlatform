using System.Globalization;
using Hangfire;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Jobs;

/// <summary>
/// Background finance jobs. Each job works per user with explicit user scoping (no cross-user reads into another
/// user's notifications) and is safe to re-run: notifications use stable deduplication keys backed by the unique
/// (UserId, DeduplicationKey) index, and summaries by the unique (UserId, SummaryMonth) index.
/// Jobs only create Notification / MonthlySummary rows and update budget alert state; they never change transactions.
/// </summary>
public sealed class FinanceJobs(ApplicationDbContext db, IBudgetUsageService budgets, IFinancialWriteScope writes, TimeProvider clock)
{
    public const string BillReminderType = "BillReminder";
    public const string BudgetAlertType = "BudgetAlert";
    public const string MonthlySummaryType = "MonthlySummary";

    private DateOnly Today => DateOnly.FromDateTime(clock.GetLocalNow().DateTime);
    private static string Money(decimal v, string currency) => currency + " " + v.ToString("#,##0.00", CultureInfo.InvariantCulture);

    /// <summary>One reminder per bill per due date, from ReminderDaysBefore days ahead up to the due date.</summary>
    [DisableConcurrentExecution(600), AutomaticRetry(Attempts = 3)]
    public async Task<int> SendBillRemindersAsync(CancellationToken ct)
    {
        var today = Today;
        var due = await db.RecurringBills.AsNoTracking()
            .Where(b => b.IsActive && b.NextDueDate >= today && b.NextDueDate.AddDays(-b.ReminderDaysBefore) <= today)
            .Select(b => new { b.BillId, b.UserId, b.Name, b.Amount, b.CurrencyCode, b.NextDueDate }).ToListAsync(ct);
        var created = 0;
        foreach (var b in due)
        {
            var key = NotificationKeys.BillReminder(b.BillId, b.NextDueDate);
            var days = b.NextDueDate.DayNumber - today.DayNumber;
            var when = days == 0 ? "today" : days == 1 ? "tomorrow" : $"in {days} days";
            if (await TryAddAsync(new Notification
            {
                UserId = b.UserId, Type = BillReminderType, ReferenceType = "RecurringBill", ReferenceId = b.BillId,
                Message = $"{b.Name} ({Money(b.Amount, b.CurrencyCode)}) is due {when}, on {b.NextDueDate:MMM d, yyyy}.",
                DeduplicationKey = key, CreatedAtUtc = clock.GetUtcNow().UtcDateTime,
            }, ct)) created++;
        }
        return created;
    }

    /// <summary>
    /// Alerts when a current-month budget newly reaches Warning or Exceeded (server BudgetStatusCalculator). The
    /// budget's LastAlertStatus and the notification are saved together under the user's write lock, so a retry
    /// cannot alert twice; returning to OK clears the state (in BudgetUsageService), so a later crossing alerts again.
    /// </summary>
    [DisableConcurrentExecution(600), AutomaticRetry(Attempts = 3)]
    public async Task<int> SendBudgetAlertsAsync(CancellationToken ct)
    {
        var month = new DateOnly(Today.Year, Today.Month, 1);
        var users = await db.Budgets.AsNoTracking().Where(b => b.PeriodMonth == month).Select(b => b.UserId).Distinct().ToListAsync(ct);
        var created = 0;
        foreach (var userId in users)
        {
            created += await writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
            {
                db.ChangeTracker.Clear();
                var periods = await db.Budgets.AsNoTracking().Where(b => b.UserId == userId && b.PeriodMonth == month)
                    .Select(b => new BudgetPeriod(b.CategoryId, b.PeriodMonth)).ToArrayAsync(token);
                var results = await budgets.RecalculateAsync(userId, periods, token);
                var n = 0;
                foreach (var r in results)
                {
                    if (!Enum.TryParse<BudgetAlertStatus>(r.Status, out var status) || status == BudgetAlertStatus.OK) continue;
                    var budget = await db.Budgets.SingleAsync(b => b.BudgetId == r.BudgetId && b.UserId == userId, token);
                    if (budget.LastAlertStatus == status) continue; // already alerted for this transition
                    budget.LastAlertStatus = status;
                    budget.LastAlertAtUtc = clock.GetUtcNow().UtcDateTime;
                    db.Notifications.Add(new Notification
                    {
                        UserId = userId, Type = BudgetAlertType, ReferenceType = "Budget", ReferenceId = budget.BudgetId,
                        Message = status == BudgetAlertStatus.Exceeded
                            ? $"{r.CategoryName} budget exceeded: {Money(r.SpentAmount, r.BaseCurrency)} of {Money(r.LimitAmount, r.BaseCurrency)}."
                            : $"{r.CategoryName} budget reached {r.AlertThresholdPct}%: {Money(r.SpentAmount, r.BaseCurrency)} of {Money(r.LimitAmount, r.BaseCurrency)}.",
                        DeduplicationKey = NotificationKeys.BudgetTransition(budget.BudgetId, status, Guid.NewGuid()),
                        CreatedAtUtc = clock.GetUtcNow().UtcDateTime,
                    });
                    n++;
                }
                await db.SaveChangesAsync(token);
                return n;
            }, ct);
        }
        return created;
    }

    /// <summary>Previous month's summary per user with activity; existing summaries are left unchanged.</summary>
    [DisableConcurrentExecution(1800), AutomaticRetry(Attempts = 3)]
    public async Task<int> GenerateMonthlySummariesAsync(CancellationToken ct)
    {
        var current = new DateOnly(Today.Year, Today.Month, 1);
        var month = current.AddMonths(-1);
        var active = await db.Transactions.AsNoTracking().Where(t => t.TransactionDate >= month && t.TransactionDate < current)
            .Select(t => t.UserId).Union(db.GoalContributions.AsNoTracking()
                .Where(c => c.ContributedOn >= month && c.ContributedOn < current).Select(c => c.Goal.UserId))
            .Distinct().ToListAsync(ct);
        var created = 0;
        foreach (var userId in active)
        {
            if (await db.MonthlySummaries.AsNoTracking().AnyAsync(s => s.UserId == userId && s.SummaryMonth == month, ct)) continue;
            var totals = await MonthlyTotals.ComputeAsync(db, userId, month, ct);
            db.ChangeTracker.Clear();
            db.MonthlySummaries.Add(new MonthlySummary
            {
                UserId = userId, SummaryMonth = month, TotalIncome = totals.Income, TotalExpense = totals.Expenses,
                NetCashFlow = totals.Income - totals.Expenses, SavingsAllocated = totals.Savings, GeneratedAtUtc = clock.GetUtcNow().UtcDateTime,
            });
            db.Notifications.Add(new Notification
            {
                UserId = userId, Type = MonthlySummaryType, ReferenceType = "MonthlySummary",
                Message = $"Your {month:MMMM yyyy} summary is ready: income {totals.Income:#,##0.00}, expenses {totals.Expenses:#,##0.00}.",
                DeduplicationKey = FormattableString.Invariant($"summary:{month:yyyy-MM}"), CreatedAtUtc = clock.GetUtcNow().UtcDateTime,
            });
            if (await TrySaveAsync(ct)) created++;
        }
        return created;
    }

    private async Task<bool> TryAddAsync(Notification n, CancellationToken ct)
    {
        if (await db.Notifications.AsNoTracking().AnyAsync(x => x.UserId == n.UserId && x.DeduplicationKey == n.DeduplicationKey, ct)) return false;
        db.ChangeTracker.Clear();
        db.Notifications.Add(n);
        return await TrySaveAsync(ct);
    }

    /// <summary>A concurrent run that already inserted the same key/month is not an error.</summary>
    private async Task<bool> TrySaveAsync(CancellationToken ct)
    {
        try { await db.SaveChangesAsync(ct); return true; }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 2601 or 2627 })
        {
            db.ChangeTracker.Clear();
            return false;
        }
    }
}
