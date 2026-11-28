namespace PersonalFinance.Domain.Entities;

/// <summary>Retained request identity after deletion. Never reuses a transaction's ClientId for this owner.</summary>
public sealed class DeletedTransaction
{
    public int UserId { get; set; }
    public Guid ClientId { get; set; }
    public int TransactionId { get; set; }
    public DateTime DeletedAtUtc { get; set; }
}
