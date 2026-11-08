using PersonalFinance.Domain.Enums;

namespace PersonalFinance.Domain.Entities;

public sealed class Category
{
    public int CategoryId { get; set; }
    public int? UserId { get; set; }
    public string Name { get; set; } = string.Empty;
    public TransactionType Type { get; set; }
    public string? ColorHEX { get; set; }
    public bool IsActive { get; set; } = true;
}

