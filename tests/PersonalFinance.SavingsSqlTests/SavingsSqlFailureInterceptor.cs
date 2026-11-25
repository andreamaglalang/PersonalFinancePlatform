using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using PersonalFinance.Domain.Entities;

namespace PersonalFinance.SavingsSqlTests;

// Observes real SaveChanges calls; never writes versions or emulates commit/rollback.
internal sealed class SavingsSqlFailureInterceptor(bool failBeforeLedger = false) : SaveChangesInterceptor
{
    public int SaveAttempts { get; private set; }
    public int SuccessfulSaves { get; private set; }
    public int? PersistedContributionId { get; private set; }
    public bool Injected { get; private set; }

    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(DbContextEventData eventData,
        InterceptionResult<int> result, CancellationToken cancellationToken = default)
    {
        SaveAttempts++;
        var db = eventData.Context ?? throw new InvalidOperationException("Missing interceptor context.");
        var pending = db.ChangeTracker.Entries<GoalContributionRequest>()
            .Where(e => e.State == EntityState.Added).ToArray();
        if (failBeforeLedger && pending.Length != 0)
        {
            SavingsSqlAssert.Require(!Injected && SuccessfulSaves == 1 && PersistedContributionId.HasValue
                && pending.Length == 1 && pending[0].Entity.ContributionId == PersistedContributionId.Value
                && db.Database.CurrentTransaction is not null,
                "Failure injection must follow one successful contribution save inside the real transaction.");
            Injected = true;
            throw new SavingsSqlInjectedFailure();
        }
        return ValueTask.FromResult(result);
    }

    public override ValueTask<int> SavedChangesAsync(SaveChangesCompletedEventData eventData, int result,
        CancellationToken cancellationToken = default)
    {
        SuccessfulSaves++;
        var db = eventData.Context ?? throw new InvalidOperationException("Missing interceptor context.");
        if (failBeforeLedger && SuccessfulSaves == 1)
        {
            var contribution = db.ChangeTracker.Entries<GoalContribution>().Single();
            SavingsSqlAssert.Require(contribution.State == EntityState.Unchanged
                && contribution.Entity.ContributionId > 0 && contribution.Entity.RowVersion.Length == 8
                && db.Database.CurrentTransaction is not null,
                "Expected a persisted native-version contribution before ledger insertion.");
            PersistedContributionId = contribution.Entity.ContributionId;
        }
        return ValueTask.FromResult(result);
    }
}

internal sealed class SavingsSqlInjectedFailure : Exception
{
    public SavingsSqlInjectedFailure() : base("Synthetic failure before ledger completion.") { }
}
