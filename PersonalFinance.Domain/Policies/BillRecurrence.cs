using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Policies;

/// <summary>Retains the original intended day through short months. Persistence and concurrency are handled separately.</summary>
public static class BillRecurrence
{
    public static DateOnly NextDueDate(DateOnly currentDueDate, BillFrequency frequency, byte? intendedDayOfMonth)
    {
        if (frequency == BillFrequency.Weekly)
        {
            if (intendedDayOfMonth is not null)
                throw new ArgumentException("Weekly bills do not use an intended day.", nameof(intendedDayOfMonth));
            return currentDueDate.AddDays(7);
        }

        if (intendedDayOfMonth is null or < 1 or > 31)
            throw new ArgumentOutOfRangeException(nameof(intendedDayOfMonth));
        var next = frequency switch
        {
            BillFrequency.Monthly => currentDueDate.AddMonths(1),
            BillFrequency.Quarterly => currentDueDate.AddMonths(3),
            BillFrequency.Yearly => currentDueDate.AddYears(1),
            _ => throw new ArgumentOutOfRangeException(nameof(frequency))
        };
        return new DateOnly(next.Year, next.Month,
            Math.Min(intendedDayOfMonth.Value, DateTime.DaysInMonth(next.Year, next.Month)));
    }
}

