using System.Data;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Authentication;

public sealed class SqlAuthSessionStore(ApplicationDbContext db) : IAuthSessionStore
{
    public async Task<T> ExecuteAsync<T>(string resource, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);
        // Locks are shared across API processes and are released on commit/rollback.
        // Login, refresh, logout and logout-all use the same per-user resource.
        await db.Database.ExecuteSqlInterpolatedAsync($"""
            DECLARE @result int;
            EXEC @result = sys.sp_getapplock @Resource={resource},
                @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=10000;
            IF @result < 0 THROW 51010, 'Authentication operation could not acquire its transaction lock.', 1;
            """, ct);
        var result = await operation(ct);
        await transaction.CommitAsync(ct);
        return result;
    }

    public Task<RefreshToken?> FindAsync(string hash, CancellationToken ct) =>
        db.RefreshTokens.AsNoTracking().SingleOrDefaultAsync(x => x.TokenHash == hash, ct);

    public Task ReloadUserAsync(ApplicationUser user, CancellationToken ct) => db.Entry(user).ReloadAsync(ct);

    public async Task AddAsync(RefreshToken token, CancellationToken ct)
    {
        db.RefreshTokens.Add(token);
        await db.SaveChangesAsync(ct);
    }

    public async Task<bool> ConsumeAsync(string hash, string replacementHash, DateTime now, CancellationToken ct) =>
        await db.RefreshTokens.Where(x => x.TokenHash == hash && x.RevokedAtUtc == null && x.ExpiresAtUtc > now)
            .ExecuteUpdateAsync(update => update
                .SetProperty(x => x.RevokedAtUtc, now)
                .SetProperty(x => x.ReplacedByTokenHash, replacementHash), ct) == 1;

    // A revoked terminal token marks the family as closed. Normal predecessors have replacement hashes.
    // No token rows are deleted: old values remain available for replay detection.
    public Task<bool> FamilyRevokedAsync(int userId, Guid familyId, CancellationToken ct) =>
        db.RefreshTokens.AnyAsync(x => x.UserId == userId && x.TokenFamilyId == familyId
            && x.RevokedAtUtc != null && x.ReplacedByTokenHash == null, ct);

    public Task RevokeFamilyAsync(int userId, Guid familyId, DateTime now, CancellationToken ct) =>
        db.RefreshTokens.Where(x => x.UserId == userId && x.TokenFamilyId == familyId && x.RevokedAtUtc == null)
            .ExecuteUpdateAsync(update => update.SetProperty(x => x.RevokedAtUtc, now), ct);

    public Task RevokeAllAsync(int userId, DateTime now, CancellationToken ct) =>
        db.RefreshTokens.Where(x => x.UserId == userId && x.RevokedAtUtc == null)
            .ExecuteUpdateAsync(update => update.SetProperty(x => x.RevokedAtUtc, now), ct);
}

