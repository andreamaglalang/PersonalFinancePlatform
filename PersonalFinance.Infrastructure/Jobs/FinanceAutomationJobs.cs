using System.Data;
using System.Globalization;
using Hangfire;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

namespace PersonalFinance.Infrastructure.Jobs;

internal static class AutomationClock
{
    public static DateOnly ManilaToday(TimeProvider clock) =>
        DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(clock.GetUtcNow(), HangfireJobRegistration.ManilaTimeZone).DateTime);

    public static DateTime UtcNow(TimeProvider clock) => clock.GetUtcNow().UtcDateTime;
}

public sealed class UpcomingBillReminderJob(ApplicationDbContext db, TimeProvider clock)
{
    [AutomaticRetry(Attempts = 3, OnAttemptsExceeded = AttemptsExceededAction.Fail)]
    public async Task RunAsync(CancellationToken cancellationToken)
    {
        var today = AutomationClock.ManilaToday(clock);
        var horizon = today.AddDays(30);
        await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
        var bills = await db.RecurringBills.AsNoTracking()
            .Where(x => x.IsActive && x.NextDueDate >= today && x.NextDueDate <= horizon && x.ReminderDaysBefore <= 30)
            .Select(x => new { x.BillId, x.UserId, x.Name, x.NextDueDate, x.ReminderDaysBefore })
            .ToListAsync(cancellationToken);
        var due = bills.Where(x => x.NextDueDate.DayNumber - today.DayNumber <= x.ReminderDaysBefore).ToList();
        if (due.Count == 0) { await transaction.CommitAsync(cancellationToken); return; }

        var keys = due.Select(x => new { x.UserId, Key = NotificationKeys.BillReminder(x.BillId, x.NextDueDate) }).ToArray();
        var userIds = keys.Select(x => x.UserId).Distinct().ToArray();
        var keyValues = keys.Select(x => x.Key).ToArray();
        var existing = await db.Notifications.AsNoTracking().Where(x => userIds.Contains(x.UserId) && keyValues.Contains(x.DeduplicationKey))
            .Select(x => new { x.UserId, x.DeduplicationKey }).ToListAsync(cancellationToken);
        var known = existing.Select(x => (x.UserId, x.DeduplicationKey)).ToHashSet();
        var now = AutomationClock.UtcNow(clock);
        foreach (var bill in due)
        {
            var key = NotificationKeys.BillReminder(bill.BillId, bill.NextDueDate);
            if (!known.Add((bill.UserId, key))) continue;
            db.Notifications.Add(new Notification
            {
                UserId = bill.UserId, Type = "BillReminder", ReferenceType = "RecurringBill", ReferenceId = bill.BillId,
                Message = $"{bill.Name} is due on {bill.NextDueDate:yyyy-MM-dd}.", IsRead = false,
                CreatedAtUtc = now, DeduplicationKey = key
            });
        }
        // A unique-key race fails the SQL transaction; Hangfire retries the job. The retry
        // observes the winning notification keys and completes without creating duplicates.
        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
    }
}

public sealed class MonthlyFinancialSummaryJob(ApplicationDbContext db, TimeProvider clock)
{
    [AutomaticRetry(Attempts = 3, OnAttemptsExceeded = AttemptsExceededAction.Fail)]
    public async Task RunAsync(CancellationToken cancellationToken)
    {
        var localToday = AutomationClock.ManilaToday(clock);
        var monthStart = new DateOnly(localToday.Year, localToday.Month, 1);
        var summaryMonth = monthStart.AddMonths(-1);
        var monthEnd = monthStart.AddDays(-1);
        var roleId = await db.Roles.Where(x => x.Name == ApplicationRoles.User).Select(x => (int?)x.Id).SingleOrDefaultAsync(cancellationToken);
        if (roleId is null) return;
        var userIds = await db.UserRoles.Where(x => x.RoleId == roleId).Select(x => x.UserId).Distinct().ToListAsync(cancellationToken);
        var now = AutomationClock.UtcNow(clock);

        foreach (var userId in userIds)
        {
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
            var totals = await db.Transactions.AsNoTracking().Where(x => x.UserId == userId
                    && x.TransactionDate >= summaryMonth && x.TransactionDate <= monthEnd)
                .GroupBy(x => x.Type).Select(g => new { Type = g.Key, Amount = g.Sum(x => x.AmountBase) }).ToListAsync(cancellationToken);
            var income = totals.Where(x => x.Type == TransactionType.Income).Select(x => x.Amount).SingleOrDefault();
            var expense = totals.Where(x => x.Type == TransactionType.Expense).Select(x => x.Amount).SingleOrDefault();
            var savings = await db.GoalContributions.AsNoTracking().Where(c => c.ContributedOn >= summaryMonth && c.ContributedOn <= monthEnd
                    && db.SavingsGoals.Any(g => g.GoalId == c.GoalId && g.UserId == userId))
                .SumAsync(c => (decimal?)c.Amount, cancellationToken) ?? 0m;
            var summary = await db.MonthlySummaries.SingleOrDefaultAsync(x => x.UserId == userId && x.SummaryMonth == summaryMonth, cancellationToken);
            if (summary is null)
            {
                summary = new MonthlySummary { UserId = userId, SummaryMonth = summaryMonth };
                db.MonthlySummaries.Add(summary);
            }
            summary.TotalIncome = income;
            summary.TotalExpense = expense;
            summary.NetCashFlow = income - expense;
            summary.SavingsAllocated = savings;
            summary.GeneratedAtUtc = now;
            await db.SaveChangesAsync(cancellationToken); // summary ID is needed by its inbox notification

            var key = $"monthly-summary:{summaryMonth.ToString("yyyy-MM", CultureInfo.InvariantCulture)}";
            var alreadyNotified = await db.Notifications.AnyAsync(x => x.UserId == userId && x.DeduplicationKey == key, cancellationToken);
            if (!alreadyNotified)
            {
                db.Notifications.Add(new Notification
                {
                    UserId = userId, Type = "MonthlySummary", ReferenceType = "MonthlySummary", ReferenceId = summary.SummaryId,
                    Message = $"Your financial summary for {summaryMonth:yyyy-MM} is ready.", IsRead = false,
                    CreatedAtUtc = now, DeduplicationKey = key
                });
                await db.SaveChangesAsync(cancellationToken);
            }
            await transaction.CommitAsync(cancellationToken);
        }
    }
}

public sealed class BudgetThresholdAlertJob(ApplicationDbContext db, TimeProvider clock)
{
    [AutomaticRetry(Attempts = 3, OnAttemptsExceeded = AttemptsExceededAction.Fail)]
    public async Task RunAsync(CancellationToken cancellationToken)
    {
        var today = AutomationClock.ManilaToday(clock);
        var month = new DateOnly(today.Year, today.Month, 1);
        var now = AutomationClock.UtcNow(clock);
        var budgets = await db.Budgets.AsNoTracking().Where(x => x.PeriodMonth == month).Select(x => x.BudgetId).ToListAsync(cancellationToken);
        foreach (var budgetId in budgets)
        {
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
            var budget = await db.Budgets.Include(x => x.Category).SingleOrDefaultAsync(x => x.BudgetId == budgetId, cancellationToken);
            if (budget is null) { await transaction.CommitAsync(cancellationToken); continue; }
            var spent = await db.Transactions.AsNoTracking().Where(x => x.UserId == budget.UserId && x.CategoryId == budget.CategoryId
                    && x.Type == TransactionType.Expense && x.TransactionDate >= month && x.TransactionDate <= today)
                .SumAsync(x => (decimal?)x.AmountBase, cancellationToken) ?? 0m;
            var current = BudgetStatusCalculator.Calculate(spent, budget.LimitAmount, budget.AlertThresholdPct);
            var previous = budget.LastAlertStatus;
            var shouldNotify = current switch
            {
                BudgetAlertStatus.Warning => previous is null or BudgetAlertStatus.OK,
                BudgetAlertStatus.Exceeded => previous != BudgetAlertStatus.Exceeded,
                _ => false
            };
            if (current == BudgetAlertStatus.OK)
            {
                budget.LastAlertStatus = null; // re-arm after spending falls below the threshold
            }
            else
            {
                budget.LastAlertStatus = current;
                if (shouldNotify)
                {
                    var transitionId = Guid.NewGuid();
                    db.Notifications.Add(new Notification
                    {
                        UserId = budget.UserId, Type = "BudgetAlert", ReferenceType = "Budget", ReferenceId = budget.BudgetId,
                        Message = current == BudgetAlertStatus.Exceeded
                            ? $"Your {budget.Category.Name} budget has been exceeded."
                            : $"Your {budget.Category.Name} budget reached its alert threshold.",
                        IsRead = false, CreatedAtUtc = now,
                        DeduplicationKey = NotificationKeys.BudgetTransition(budget.BudgetId, current, transitionId)
                    });
                    budget.LastAlertAtUtc = now;
                }
            }
            if (previous != budget.LastAlertStatus || shouldNotify) await db.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
        }
    }
}
