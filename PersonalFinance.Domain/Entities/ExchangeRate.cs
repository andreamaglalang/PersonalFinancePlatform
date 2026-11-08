using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class ExchangeRate
{
    public int RateId { get; set; }
    public string BaseCurrency { get; set; } = string.Empty;
    public string QuoteCurrency { get; set; } = string.Empty;
    public decimal Rate { get; set; }
    public DateOnly RateDate { get; set; }
    public DateTime FetchedAtUtc { get; set; } = DateTime.UtcNow;
}

