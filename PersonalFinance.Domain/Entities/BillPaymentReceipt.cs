
namespace PersonalFinance.Domain.Entities;

public sealed class BillPaymentReceipt
{
    public long ReceiptId { get; private set; }

    public int UserId { get; init; }
    public Guid ClientId { get; init; }
    public int BillId { get; init; }
    public int TransactionId { get; init; }

    public DateOnly DueDate { get; init; }

    public int CategoryId { get; init; }
    public decimal Amount { get; init; }
    public string CurrencyCode { get; init; } = string.Empty;

    public decimal FxRate { get; init; }
    public DateOnly FxRateDate { get; init; }
    public decimal AmountBase { get; init; }
    public string BaseCurrency { get; init; } = string.Empty;

    public DateOnly PaidOn { get; init; }

    public short CanonicalVersion { get; init; } = 1;

    public DateTime RecordedAtUtc { get; init; }
}
