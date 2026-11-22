using Microsoft.EntityFrameworkCore;

namespace PersonalFinance.Infrastructure.Persistence;

public sealed class PlatformJournalEntry
{
    public long Id { get; set; }
    public int ActorId { get; set; }
    public string Action { get; set; } = string.Empty;
    public string TargetType { get; set; } = string.Empty;
    public int TargetId { get; set; }
    public DateTime OccurredAtUtc { get; set; }
}

// SQL-only journal lets the existing application run while the additive migration awaits approval.
public static class PlatformJournal
{
    public static Task<bool> IsAvailableAsync(ApplicationDbContext db, CancellationToken ct) =>
        !db.Database.IsSqlServer() ? Task.FromResult(false) : db.Database.SqlQueryRaw<bool>("""
            SELECT CAST(CASE WHEN OBJECT_ID(N'dbo.PlatformJournal', N'U') IS NULL THEN 0 ELSE 1 END AS bit) AS [Value]
            """).SingleAsync(ct);

    public static IQueryable<PlatformJournalEntry> Entries(ApplicationDbContext db) =>
        db.Database.SqlQueryRaw<PlatformJournalEntry>("""
            SELECT Id, ActorId, Action, TargetType, TargetId, OccurredAtUtc FROM dbo.PlatformJournal
            """);

    public static Task RecordAsync(ApplicationDbContext db, int actorId, string action, string targetType,
        int targetId, DateTime occurredAtUtc, CancellationToken ct)
    {
        if (!db.Database.IsSqlServer()) return Task.CompletedTask;
        return db.Database.ExecuteSqlInterpolatedAsync($"""
            IF OBJECT_ID(N'dbo.PlatformJournal', N'U') IS NOT NULL
                EXEC sys.sp_executesql
                    N'INSERT dbo.PlatformJournal (ActorId, Action, TargetType, TargetId, OccurredAtUtc)
                      VALUES (@actor, @action, @type, @target, @occurred)',
                    N'@actor int, @action nvarchar(40), @type nvarchar(40), @target int, @occurred datetime2',
                    @actor={actorId}, @action={action}, @type={targetType}, @target={targetId}, @occurred={occurredAtUtc};
            """, ct);
    }
}

