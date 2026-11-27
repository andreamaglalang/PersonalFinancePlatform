
using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class PayBillRequest
{
    [Required(ErrorMessage = "A client-generated ClientId is required.")]
    public Guid? ClientId { get; set; }

    [Required(ErrorMessage = "Choose the due date being paid.")]
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
