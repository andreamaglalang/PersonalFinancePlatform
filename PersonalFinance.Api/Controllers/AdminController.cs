using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize(Roles = ApplicationRoles.Administrator), Route("api/admin")]
public sealed class AdminController(ApplicationDbContext db, TimeProvider clock) : ControllerBase
{
    private IQueryable<ApplicationUser> RegularUsers =>
        db.Users.AsNoTracking().Where(user => db.UserRoles.Any(membership =>
            membership.UserId == user.Id && db.Roles.Any(role =>
                role.Id == membership.RoleId && role.Name == ApplicationRoles.User))
            && !db.UserRoles.Any(membership => membership.UserId == user.Id &&
                db.Roles.Any(role => role.Id == membership.RoleId && role.Name == ApplicationRoles.Administrator)));

    [HttpGet("users")]
    public async Task<ActionResult<RegisteredUsersPage>> Users(
        [FromQuery] string? search, CancellationToken ct, int page = 1, int pageSize = 20, string? status = null)
    {
        search = search?.Trim();
        if (page < 1 || pageSize is < 1 or > 50 || search?.Length > 100 || status is not (null or "active" or "locked"))
            return BadRequest();

        var query = RegularUsers;
        if (!string.IsNullOrEmpty(search))
            query = query.Where(user => user.FullName.Contains(search)
                || (user.Email != null && user.Email.Contains(search)));

        var now = clock.GetUtcNow();
        if (status == "locked")
            query = query.Where(user => user.LockoutEnabled && user.LockoutEnd >= now);
        if (status == "active")
            query = query.Where(user => !user.LockoutEnabled || user.LockoutEnd == null || user.LockoutEnd < now);

        var total = await query.LongCountAsync(ct);
        var lastPage = Math.Max(1L, (total + pageSize - 1) / pageSize);
        page = (int)Math.Min(page, lastPage);
        var offset = (long)(page - 1) * pageSize;
        if (offset > int.MaxValue) return BadRequest();

        var items = await query.OrderByDescending(user => user.CreatedAtUtc).ThenByDescending(user => user.Id)
            .Skip((int)offset).Take(pageSize)
            .Select(user => new RegisteredUser(user.FullName, user.Email, user.CreatedAtUtc,
                user.LockoutEnabled && user.LockoutEnd >= now, user.Id, ApplicationRoles.User))
            .ToListAsync(ct);

        return new RegisteredUsersPage(items, page, pageSize, total);
    }

    [HttpGet("users/{id:int}")]
    public async Task<ActionResult<RegisteredUser>> UserDetails(int id, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var user = await RegularUsers.Where(user => user.Id == id)
            .Select(user => new RegisteredUser(user.FullName, user.Email, user.CreatedAtUtc,
                user.LockoutEnabled && user.LockoutEnd >= now, user.Id, ApplicationRoles.User))
            .SingleOrDefaultAsync(ct);
        return user is null ? NotFound() : user;
    }

    [HttpGet("activity")]
    public async Task<PlatformActivity> Activity(CancellationToken ct)
    {
        var since = clock.GetUtcNow().UtcDateTime.AddDays(-7);
        var registrations = await RegularUsers.Where(user => user.CreatedAtUtc >= since)
            .OrderByDescending(user => user.CreatedAtUtc).ThenByDescending(user => user.Id)
            .Select(user => new ActivityEvent("Registration", user.CreatedAtUtc))
            .Take(20).ToListAsync(ct);
        var registrationCount = await RegularUsers.LongCountAsync(user => user.CreatedAtUtc >= since, ct);

        // Return a platform-level count only, without owners, amounts, or individual transaction dates.
        var transactions = await db.Transactions.LongCountAsync(ct);
        var journalAvailable = await PlatformJournal.IsAvailableAsync(db, ct);
        if (journalAvailable)
        {
            var signIns = await PlatformJournal.Entries(db)
                .Where(entry => entry.TargetType == "Account" && entry.Action == "SignIn" && entry.OccurredAtUtc >= since)
                .OrderByDescending(entry => entry.OccurredAtUtc).ThenByDescending(entry => entry.Id)
                .Select(entry => new ActivityEvent("Successful sign-in", entry.OccurredAtUtc))
                .Take(20).ToListAsync(ct);
            registrations.AddRange(signIns);
        }

        return new(registrationCount, transactions, journalAvailable,
            registrations.OrderByDescending(entry => entry.OccurredAtUtc).Take(20).ToArray());
    }

    [HttpGet("audit")]
    public async Task<ActionResult<AuditPage>> Audit(CancellationToken ct, string? search = null, int page = 1)
    {
        search = search?.Trim();
        if (page < 1 || search?.Length > 100) return BadRequest();
        if (!await PlatformJournal.IsAvailableAsync(db, ct))
            return new AuditPage(false, [], 1, 20, 0);

        const int size = 20;
        var entries = PlatformJournal.Entries(db).Where(entry => entry.TargetType == "SharedCategory" || entry.TargetType == "PlatformSetting");
        if (!string.IsNullOrEmpty(search))
        {
            if (int.TryParse(search, out var id))
                entries = entries.Where(entry => entry.ActorId == id || entry.TargetId == id);
            else
                entries = entries.Where(entry => entry.Action.Contains(search) || entry.TargetType.Contains(search));
        }

        var count = await entries.LongCountAsync(ct);
        page = (int)Math.Min(page, Math.Max(1L, (count + size - 1) / size));
        var offset = (long)(page - 1) * size;
        if (offset > int.MaxValue) return BadRequest();
        var items = await entries.OrderByDescending(entry => entry.OccurredAtUtc).ThenByDescending(entry => entry.Id)
            .Skip((int)offset).Take(size).ToListAsync(ct);
        return new AuditPage(true, items, page, size, count);
    }

    [HttpGet("health")]
    public async Task<SystemHealth> Health(CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(3));
        var databaseHealthy = false;
        try
        {
            databaseHealthy = await db.Database.CanConnectAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }

        return new(databaseHealthy, clock.GetUtcNow());
    }

    public sealed record ActivityEvent(string Action, DateTime OccurredAtUtc);
    public sealed record PlatformActivity(long RegistrationsLastSevenDays, long TransactionCount,
        bool SignInRecordingAvailable, IReadOnlyList<ActivityEvent> Events);
    public sealed record AuditPage(bool RecordingAvailable, IReadOnlyList<PlatformJournalEntry> Items,
        int Page, int PageSize, long TotalCount);
    public sealed record SystemHealth(bool DatabaseHealthy, DateTimeOffset CheckedAtUtc);

    [HttpGet("statistics")]
    public async Task<AdminStatistics> Statistics(CancellationToken ct)
    {
        var users = await RegularUsers.LongCountAsync(ct);
        var categories = await db.Categories.Where(category => category.UserId == null)
            .GroupBy(category => category.IsActive)
            .Select(group => new { Active = group.Key, Count = group.LongCount() })
            .ToDictionaryAsync(row => row.Active, row => row.Count, ct);
        return new(users, categories.GetValueOrDefault(true), categories.GetValueOrDefault(false), clock.GetUtcNow());
    }

    public sealed record RegisteredUser(string FullName, string? Email, DateTime CreatedAtUtc, bool IsLockedOut, int Id, string Role);
    public sealed record RegisteredUsersPage(IReadOnlyList<RegisteredUser> Items, int Page, int PageSize, long TotalCount);
    public sealed record AdminStatistics(long RegisteredUsers, long ActiveSharedCategories,
        long InactiveSharedCategories, DateTimeOffset UpdatedAtUtc);
}

