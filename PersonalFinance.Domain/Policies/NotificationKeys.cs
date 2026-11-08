using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Policies;

public static class NotificationKeys
{
    // Allocate and retain a transition ID when alert work is first created, then reuse it on retries.
    // A later transition receives another ID, even if the status is the same as a previous transition.
    public static string BudgetTransition(int budgetId, BudgetAlertStatus status, Guid transitionId)
    {
        if (transitionId == Guid.Empty) throw new ArgumentException("A stable transition ID is required.", nameof(transitionId));
        if (status is not (BudgetAlertStatus.Warning or BudgetAlertStatus.Exceeded))
            throw new ArgumentOutOfRangeException(nameof(status));
        return $"budget:{budgetId}:transition:{transitionId:N}:{status}";
    }

    public static string BillReminder(int billId, DateOnly dueDate) =>
        FormattableString.Invariant($"bill:{billId}:due:{dueDate:yyyy-MM-dd}");
}

