using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class Notification
{
    public int NotificationId { get; set; }
    public int UserId { get; set; }
    public string Type { get; set; } = string.Empty;
    public string? ReferenceType { get; set; }
    public int? ReferenceId { get; set; }
    public string Message { get; set; } = string.Empty;
    public bool IsRead { get; set; }
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime? EmailSentAtUtc { get; set; }
    /// <summary>Stable across retries of one event. Budget alerts must include a distinct transition ID, not just budget and status.</summary>
    public string DeduplicationKey { get; set; } = string.Empty;
}

