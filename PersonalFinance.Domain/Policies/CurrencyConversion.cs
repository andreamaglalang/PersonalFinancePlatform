namespace PersonalFinance.Domain.Policies;

/// <summary>Pure snapshot arithmetic; currency support and ownership validation belong to the posting service.</summary>
public static class CurrencyConversion
{
    public static (decimal Rate, DateOnly RateDate, decimal AmountBase) CreateSnapshot(
        decimal amount, string currency, string baseCurrency, DateOnly transactionDate,
        decimal rate, DateOnly rateDate, int maximumRateAgeDays = 7)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(amount);
        ArgumentOutOfRangeException.ThrowIfNegative(maximumRateAgeDays);
        if (string.Equals(currency, baseCurrency, StringComparison.Ordinal))
            return (1m, transactionDate, decimal.Round(amount, 2, MidpointRounding.AwayFromZero));

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(rate);
        var age = transactionDate.DayNumber - rateDate.DayNumber;
        if (age < 0 || age > maximumRateAgeDays)
            throw new ArgumentException("The rate must be published on or before the transaction date within the configured maximum age.", nameof(rateDate));

        return (rate, rateDate, decimal.Round(amount * rate, 2, MidpointRounding.AwayFromZero));
    }
}

