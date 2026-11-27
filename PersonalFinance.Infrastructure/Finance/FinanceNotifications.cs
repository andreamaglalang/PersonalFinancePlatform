using System.Globalization;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

/// <summary>
/// Event-driven inbox notifications. Each is added to the same DbContext save as the financial change that caused it,
/// so it commits (or rolls back) with that change; the worker is not involved. Keys use the existing unique
/// (UserId, DeduplicationKey) index, and replayed requests never reach these methods.
/// </summary>
public static class FinanceNotifications
{
    public const string GoalMilestoneType = "GoalMilestone";
    public const string GoalCompletedType = "GoalCompleted";
    public const string BillOverdueType = "BillOverdue";
    public const string BillPaymentRecordedType = "BillPaymentRecorded";
    public const string PasswordChangedType = "PasswordChanged";

    public static string Money(decimal value, string currency) =>
        currency + " " + value.ToString("#,##0.00", CultureInfo.InvariantCulture);

    /// <summary>Highest milestone reached (25, 50, 75, or 100 = target met), from exact decimals; 0 below 25%.</summary>
    public static int Milestone(decimal saved, decimal target) =>
        target <= 0 ? 0 : saved >= target ? 100 : saved * 4 >= target * 3 ? 75 : saved * 2 >= target ? 50 : saved * 4 >= target ? 25 : 0;

    /// <summary>
    /// After a committed-with-this-save goal change: notify the highest milestone reached, but only when it is higher
    /// than every milestone already notified for this goal. One contribution crossing several milestones therefore
    /// gives one notification (completion wins), and falling back and re-crossing (edits, deletions, a raised target)
    /// never repeats one. Completion is notified once per goal, even if the goal is later reopened and completed again.
    /// </summary>
    public static async Task AddGoalProgressAsync(ApplicationDbContext db, SavingsGoal goal, decimal saved, string currency,
        DateTime now, CancellationToken ct)
    {
        if (goal.Status == SavingsGoalStatus.Cancelled) return;
        var reached = Milestone(saved, goal.TargetAmount);
        if (reached == 0) return;
        var prefix = FormattableString.Invariant($"goal:{goal.GoalId}:");
        var keys = await db.Notifications.AsNoTracking()
            .Where(n => n.UserId == goal.UserId && n.DeduplicationKey.StartsWith(prefix)).Select(n => n.DeduplicationKey).ToListAsync(ct);
        if (keys.Select(k => MilestoneOf(k, prefix)).DefaultIfEmpty(0).Max() >= reached) return;
        db.Notifications.Add(new Notification
        {
            UserId = goal.UserId, Type = reached == 100 ? GoalCompletedType : GoalMilestoneType,
            ReferenceType = "SavingsGoal", ReferenceId = goal.GoalId, CreatedAtUtc = now,
            DeduplicationKey = prefix + (reached == 100 ? "completed" : FormattableString.Invariant($"milestone:{reached}")),
            Message = reached switch
            {
                100 => $"Congratulations! You've reached your {Money(goal.TargetAmount, currency)} {goal.Name} goal.",
                75 => $"Almost there! You've saved 75% of your {goal.Name} target.",
                50 => $"You're halfway there! You've reached 50% of your {goal.Name} savings goal.",
                _ => $"Great start! You've saved 25% of your {goal.Name} goal.",
            },
        });
    }

    private static int MilestoneOf(string key, string prefix) => key[prefix.Length..] switch
    {
        "completed" => 100,
        var rest when rest.StartsWith("milestone:", StringComparison.Ordinal)
            && int.TryParse(rest["milestone:".Length..], NumberStyles.None, CultureInfo.InvariantCulture, out var pct) => pct,
        _ => 0,
    };

    /// <summary>Recorded with the payment's receipt. OWLI records payments; it never transfers money to a provider.</summary>
    public static Notification BillPaymentRecorded(RecurringBill bill, DateOnly due, DateTime now) => new()
    {
        UserId = bill.UserId, Type = BillPaymentRecordedType, ReferenceType = "RecurringBill", ReferenceId = bill.BillId,
        Message = FormattableString.Invariant($"Your {bill.Name} bill payment of {Money(bill.Amount, bill.CurrencyCode)} for {due:MMM d, yyyy} has been recorded."),
        DeduplicationKey = FormattableString.Invariant($"bill:{bill.BillId}:paid:{due:yyyy-MM-dd}"), CreatedAtUtc = now,
    };

    /// <summary>Committed with a successful password change only. Contains no credential, token or session data.</summary>
    public static Notification PasswordChanged(int userId, DateTime now) => new()
    {
        UserId = userId, Type = PasswordChangedType,
        Message = "Your OWLI account password was successfully changed.",
        DeduplicationKey = FormattableString.Invariant($"security:password:{Guid.NewGuid():N}"), CreatedAtUtc = now,
    };
}
