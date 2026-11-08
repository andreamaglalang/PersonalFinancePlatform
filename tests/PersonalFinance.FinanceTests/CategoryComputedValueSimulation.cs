using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Persistence;
namespace PersonalFinance.FinanceTests;

/// <summary>Test-only boundary-value simulation. Does not emulate SQL collation, constraints, or transactions.</summary>
internal sealed class CategoryComputedValueSimulation : SaveChangesInterceptor
{
    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
        DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
    {
        foreach (var entry in eventData.Context!.ChangeTracker.Entries<Category>()
            .Where(e => e.State is EntityState.Added or EntityState.Modified))
            entry.Property<string>(CategoryNameNormalization.PropertyName).CurrentValue =
                CategoryNameNormalization.Trim(entry.Entity.Name);
        return ValueTask.FromResult(result);
    }
}
