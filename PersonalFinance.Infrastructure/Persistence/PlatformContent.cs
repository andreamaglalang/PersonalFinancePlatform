using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.Infrastructure.Persistence;

public sealed class PlatformAnnouncement
{
    public int Id { get; set; }
    [MaxLength(120)] public string Title { get; set; } = "";
    [MaxLength(4000)] public string Message { get; set; } = "";
    [MaxLength(16)] public string Status { get; set; } = "Draft";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
    public DateTime? PublishedAtUtc { get; set; }
}

public sealed class SupportRequest
{
    public int Id { get; set; }
    public int OwnerId { get; set; }
    [MaxLength(16)] public string Kind { get; set; } = "";
    [MaxLength(120)] public string Subject { get; set; } = "";
    [MaxLength(4000)] public string Message { get; set; } = "";
    [MaxLength(16)] public string Status { get; set; } = "Open";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
    public ICollection<SupportReply> Replies { get; set; } = [];
}

public sealed class SupportReply
{
    public int Id { get; set; }
    public int SupportRequestId { get; set; }
    [MaxLength(4000)] public string Message { get; set; } = "";
    public DateTime CreatedAtUtc { get; set; }
}

public sealed class PlatformSetting
{
    public int Id { get; set; }
    [MaxLength(40)] public string Key { get; set; } = "";
    [MaxLength(500)] public string Value { get; set; } = "";
    public DateTime? UpdatedAtUtc { get; set; }
}
