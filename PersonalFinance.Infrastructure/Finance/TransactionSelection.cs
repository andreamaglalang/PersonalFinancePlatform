using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Infrastructure.Finance;

public static class TransactionSelection
{
    public static bool IsValid(TransactionQuery filters) =>
        (filters.Type is null || FinanceValidation.ValidType(filters.Type))
        && (filters.CategoryId is null or > 0) && !(filters.FromDate > filters.ToDate)
        && (filters.Search?.Length ?? 0) <= 100;

    // Callers supply an owner-scoped source before applying these filters.
    public static IQueryable<Transaction> Apply(IQueryable<Transaction> rows, TransactionQuery filters)
    {
        if (filters.Type is { } type)
        {
            var parsed = FinanceValidation.ParseType(type);
            rows = rows.Where(row => row.Type == parsed);
        }
        if (filters.CategoryId is int category)
            rows = rows.Where(row => row.CategoryId == category);
        if (filters.FromDate is { } from)
            rows = rows.Where(row => row.TransactionDate >= from);
        if (filters.ToDate is { } to)
            rows = rows.Where(row => row.TransactionDate <= to);
        if (filters.Search?.Trim() is { Length: > 0 } search)
            rows = rows.Where(row => (row.Description != null && row.Description.Contains(search))
                || row.Category.Name.Contains(search));
        return rows;
    }
}
