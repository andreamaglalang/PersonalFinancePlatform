using System.Buffers.Binary;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Infrastructure;
using PersonalFinance.Domain.Entities;

namespace PersonalFinance.FinanceTests;

/// <summary>InMemory simulation only: not SQL rowversion generation, transactions, or locking.</summary>
internal sealed class SavingsRowVersionSimulation : SaveChangesInterceptor
{
    private long nextVersion;
    public int SaveAttempts { get; private set; }

    private void Prepare(DbContext? context)
    {
        if (context is null) return;
        SaveAttempts++;
        context.ChangeTracker.DetectChanges();
        foreach (var entry in context.ChangeTracker.Entries()
            .Where(e => (e.Entity is SavingsGoal or GoalContribution or RecurringBill)
                && e.State is EntityState.Added or EntityState.Modified).ToArray())
        {
            var property = entry.Property("RowVersion");
            var original = ((byte[])property.OriginalValue!).ToArray();
            var version = new byte[8];
            BinaryPrimitives.WriteInt64BigEndian(version, Interlocked.Increment(ref nextVersion));
            property.CurrentValue = version;
            // Keep stale originals for EF InMemory's concurrency comparison; never overwrite them with the new token.
            if (entry.State == EntityState.Modified) property.OriginalValue = original;
        }
    }

    public override InterceptionResult<int> SavingChanges(DbContextEventData eventData, InterceptionResult<int> result)
    { Prepare(eventData.Context); return result; }

    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(DbContextEventData eventData,
        InterceptionResult<int> result, CancellationToken cancellationToken = default)
    { Prepare(eventData.Context); return ValueTask.FromResult(result); }
}

/// <summary>Only the InMemory fixture writes simulated tokens; production SQL mappings remain untouched.</summary>
internal sealed class SavingsInMemoryModelCustomizer(ModelCustomizerDependencies dependencies) : ModelCustomizer(dependencies)
{
    public override void Customize(ModelBuilder modelBuilder, DbContext context)
    {
        base.Customize(modelBuilder, context);
        // The interceptor supplies values that SQL would normally generate. Keep concurrency comparison enabled.
        modelBuilder.Entity<SavingsGoal>().Property(g => g.RowVersion).ValueGeneratedNever().IsConcurrencyToken();
        modelBuilder.Entity<GoalContribution>().Property(c => c.RowVersion).ValueGeneratedNever().IsConcurrencyToken();
        modelBuilder.Entity<RecurringBill>().Property(b => b.RowVersion).ValueGeneratedNever().IsConcurrencyToken();
    }
}
