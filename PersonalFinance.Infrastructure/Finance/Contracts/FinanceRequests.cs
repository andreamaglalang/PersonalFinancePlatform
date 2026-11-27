using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PersonalFinance.Infrastructure.Finance.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class CreateTransactionRequest : TransactionWriteRequest
{
    [Required(ErrorMessage = "A client-generated ClientId is required.")]
    public Guid? ClientId { get; set; }
}

/// <summary>PUT body: the edit plus the <see cref="TransactionResponse.Version"/> the client last read (C1).</summary>
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class UpdateTransactionRequest : TransactionWriteRequest
{
    // Validated in TransactionService after the 404 and bill-payment checks, so error precedence is unchanged.
    public string? Version { get; set; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public class TransactionWriteRequest
{
    [Required(ErrorMessage = "Choose Income or Expense."), RegularExpression("^(Income|Expense)$", ErrorMessage = "Choose Income or Expense.")]
    public string Type { get; set; } = string.Empty;
    [Required(ErrorMessage = "Choose a category."), Range(1, int.MaxValue, ErrorMessage = "Choose a category.")]
    public int? CategoryId { get; set; }
    [Required(ErrorMessage = "Enter an amount.")]
    public decimal? Amount { get; set; }
    [Required(ErrorMessage = "Choose a currency."), RegularExpression("^[A-Z]{3}$", ErrorMessage = "Use a three-letter uppercase currency code, for example PHP.")]
    public string CurrencyCode { get; set; } = string.Empty;
    [Required(ErrorMessage = "Choose a date.")]
    public DateOnly? TransactionDate { get; set; }
    [StringLength(200, ErrorMessage = "Description must be 200 characters or fewer.")]
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
    [Required(ErrorMessage = "Enter a category name."), StringLength(50, ErrorMessage = "Category names can be up to 50 characters."),
     SingleLineText(ErrorMessage = "Category names can't contain line breaks or control characters.")]
    public string Name { get; set; } = string.Empty;
    [Required(ErrorMessage = "Choose Income or Expense."), RegularExpression("^(Income|Expense)$", ErrorMessage = "Choose Income or Expense.")]
    public string Type { get; set; } = string.Empty;
    [RegularExpression("^#[0-9A-Fa-f]{6}$", ErrorMessage = "Use a six-digit hex color, for example #2E7D32.")]
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

