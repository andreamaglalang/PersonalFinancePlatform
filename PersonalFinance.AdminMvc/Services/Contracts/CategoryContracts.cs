using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.AdminMvc.Services.Contracts;

public sealed class CategoryResponse
{
    public required int CategoryId { get; init; }
    public required string Name { get; init; }
    public required string Type { get; init; }
    public required string? ColorHEX { get; init; }
    public required bool IsActive { get; init; }
    public required bool IsSystemDefault { get; init; }
}

public sealed class CategoryPageResponse
{
    public required IReadOnlyList<CategoryResponse> Items { get; init; }
    public required int Page { get; init; }
    public required int PageSize { get; init; }
    public required long TotalCount { get; init; }
}

public sealed class CategoryWriteRequest
{
    [Required, StringLength(50)] public string Name { get; init; } = string.Empty;
    [Required, RegularExpression("^(Income|Expense)$")] public string Type { get; init; } = string.Empty;
    [RegularExpression("^#[0-9A-Fa-f]{6}$")] public string? ColorHEX { get; init; }
    public bool? IsActive { get; init; }
}

public enum CategoryFailure { None, BadRequest, Unauthorized, Forbidden, NotFound, Conflict, RateLimited, Unavailable, InvalidResponse, Stale, ConfirmationRequired }

// No upstream bodies or token-bearing state are passed to views.
public sealed class CategoryResult<T>
{
    public T? Value { get; init; }
    public CategoryFailure Failure { get; init; }
    public bool Succeeded => Failure == CategoryFailure.None;
    public static CategoryResult<T> Success(T value) => new() { Value = value };
    public static CategoryResult<T> Fail(CategoryFailure failure) => new() { Failure = failure };
}
