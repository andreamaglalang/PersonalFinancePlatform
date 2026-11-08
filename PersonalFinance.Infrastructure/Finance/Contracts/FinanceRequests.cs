using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateTransactionRequest : TransactionWriteRequest
{
    [Required]
    public Guid? ClientId { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class TransactionWriteRequest
{
    [Required, RegularExpression("^(Income|Expense)$")]
    public string Type { get; set; } = string.Empty;
    [Required, Range(1, int.MaxValue)]
    public int? CategoryId { get; set; }
    [Required]
    public decimal? Amount { get; set; }
    [Required, RegularExpression("^[A-Z]{3}$")]
    public string CurrencyCode { get; set; } = string.Empty;
    [Required]
    public DateOnly? TransactionDate { get; set; }
    [StringLength(200)]
    public string? Description { get; set; }
}

public sealed class TransactionQuery
{
    public string? Type { get; set; }
    public int? CategoryId { get; set; }
    public DateOnly? FromDate { get; set; }
    public DateOnly? ToDate { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 20;
    public string SortBy { get; set; } = "date";
    public string SortDirection { get; set; } = "desc";
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CategoryWriteRequest
{
    [Required, StringLength(50)]
    public string Name { get; set; } = string.Empty;
    [Required, RegularExpression("^(Income|Expense)$")]
    public string Type { get; set; } = string.Empty;
    [RegularExpression("^#[0-9A-Fa-f]{6}$")]
    public string? ColorHEX { get; set; }
    public bool? IsActive { get; set; }
}

public sealed class CategoryQuery
{
    public string? Type { get; set; }
    public bool IncludeInactive { get; set; }
    public int Page { get; set; } = 1;
    public int PageSize { get; set; } = 50;
}

