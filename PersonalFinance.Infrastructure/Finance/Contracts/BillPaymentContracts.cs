
using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class PayBillRequest
{
    [Required]
    public Guid? ClientId { get; set; }

    [Required]
    public DateOnly? DueDate { get; set; }
}

public sealed record BillPaymentReceiptResponse(
    long ReceiptId,
    int UserId,
    Guid ClientId,
    int BillId,
    int TransactionId,
    DateOnly DueDate,
    int CategoryId,
    decimal Amount,
    string CurrencyCode,
    decimal FxRate,
    DateOnly FxRateDate,
    decimal AmountBase,
    string BaseCurrency,
    DateOnly PaidOn,
    short CanonicalVersion,
    DateTime RecordedAtUtc);

public sealed record BillPaymentResponse(
    BillPaymentReceiptResponse Receipt,
    bool Replayed,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<BudgetResponse>? AffectedBudgets);
