using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Policies;

public static class BudgetStatusCalculator
{
    public static BudgetAlertStatus Calculate(decimal spentAmount, decimal limitAmount, int alertThresholdPct)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(spentAmount);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(limitAmount);
        if (alertThresholdPct is < 1 or > 100) throw new ArgumentOutOfRangeException(nameof(alertThresholdPct));
        if (spentAmount > limitAmount) return BudgetAlertStatus.Exceeded;
        return spentAmount >= limitAmount * alertThresholdPct / 100m
            ? BudgetAlertStatus.Warning : BudgetAlertStatus.OK;
    }
}