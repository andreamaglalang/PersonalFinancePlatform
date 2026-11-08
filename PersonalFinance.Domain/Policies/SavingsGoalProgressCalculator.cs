using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Policies;

public sealed record SavingsGoalProgress(decimal TotalSavedAmount, decimal RemainingAmount,
    decimal SurplusAmount, decimal ProgressPercentage, SavingsGoalStatus Status);

public static class SavingsGoalProgressCalculator
{
    // Domain-owned decimal(18,2) ceiling; matches FinanceValidation.MaximumMoney without depending on Infrastructure.
    public const decimal MaximumMoney = 9999999999999999.99m;

    public static decimal CalculateTotalSaved(IEnumerable<decimal> contributionAmounts)
    {
        ArgumentNullException.ThrowIfNull(contributionAmounts);
        var total = 0m;
        foreach (var amount in contributionAmounts)
        {
            ValidateMoney(amount, nameof(contributionAmounts), allowZero: false);
            // Check before adding so even an extreme input sequence cannot overflow decimal.
            if (amount > MaximumMoney - total)
                throw new ArgumentOutOfRangeException(nameof(contributionAmounts), "Total savings exceeds the supported monetary ceiling.");
            total += amount;
        }
        return total;
    }

    public static SavingsGoalProgress Calculate(IEnumerable<decimal> contributionAmounts,
        decimal targetAmount, SavingsGoalStatus currentStatus) =>
        Calculate(CalculateTotalSaved(contributionAmounts), targetAmount, currentStatus);

    /// <summary>Accepts an aggregate calculated by the caller; never writes balances or lifecycle state.</summary>
    public static SavingsGoalProgress Calculate(decimal totalSavedAmount,
        decimal targetAmount, SavingsGoalStatus currentStatus)
    {
        ValidateMoney(totalSavedAmount, nameof(totalSavedAmount), allowZero: true);
        ValidateMoney(targetAmount, nameof(targetAmount), allowZero: false);
        if (currentStatus is not (SavingsGoalStatus.Active or SavingsGoalStatus.Completed or SavingsGoalStatus.Cancelled))
            throw new ArgumentOutOfRangeException(nameof(currentStatus));

        var remaining = targetAmount - totalSavedAmount;
        var status = currentStatus == SavingsGoalStatus.Cancelled ? SavingsGoalStatus.Cancelled
            : totalSavedAmount >= targetAmount ? SavingsGoalStatus.Completed : SavingsGoalStatus.Active;
        // Divide first to keep intermediate arithmetic bounded; round only the display percentage.
        var percentage = decimal.Round(totalSavedAmount / targetAmount * 100m, 2, MidpointRounding.AwayFromZero);
        return new(totalSavedAmount, remaining, Math.Max(0m, -remaining), percentage, status);
    }

    private static void ValidateMoney(decimal amount, string parameterName, bool allowZero)
    {
        var scale = (decimal.GetBits(amount)[3] >> 16) & 0xFF;
        if (amount < 0 || (!allowZero && amount == 0) || amount > MaximumMoney || scale > 2)
            throw new ArgumentOutOfRangeException(parameterName,
                "Money must be within decimal(18,2), have at most two decimal places, and be positive (or zero for total savings).");
    }
}
