namespace PersonalFinance.Infrastructure;

/// <summary>Maximum published-rate age used by transaction conversion and cache validation.</summary>
public sealed class CurrencyPolicyOptions
{
    public const string SectionName = "CurrencyPolicy";
    public int MaximumRateAgeDays { get; set; } = 7;
}

