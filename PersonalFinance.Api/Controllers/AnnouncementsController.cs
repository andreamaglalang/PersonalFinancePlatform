using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/announcements")]
public sealed class AnnouncementsController(ApplicationDbContext db, TimeProvider clock) : ControllerBase
{
    [HttpGet]
    public async Task<ActionResult<Announcement[]>> Published(CancellationToken ct, int page = 1)
    {
        if (page is < 1 or > 1000000) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        return await db.PlatformAnnouncements.AsNoTracking().Where(x => x.Status == "Published")
            .OrderByDescending(x => x.PublishedAtUtc).ThenByDescending(x => x.Id)
            .Skip((page - 1) * 20).Take(20)
            .Select(x => new Announcement(x.Id, x.Title, x.Message, x.Status, x.CreatedAtUtc, x.UpdatedAtUtc, x.PublishedAtUtc))
            .ToArrayAsync(ct);
    }

    [HttpGet("/api/admin/announcements"), Authorize(Roles = ApplicationRoles.Administrator)]
    public async Task<ActionResult<Announcement[]>> Index(CancellationToken ct, int page = 1)
    {
        if (page is < 1 or > 1000000) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        return await db.PlatformAnnouncements.AsNoTracking().OrderByDescending(x => x.UpdatedAtUtc).ThenByDescending(x => x.Id)
            .Skip((page - 1) * 20).Take(21)
            .Select(x => new Announcement(x.Id, x.Title, x.Message, x.Status, x.CreatedAtUtc, x.UpdatedAtUtc, x.PublishedAtUtc))
            .ToArrayAsync(ct);
    }

    [HttpGet("/api/admin/announcements/{id:int}"), Authorize(Roles = ApplicationRoles.Administrator)]
    public async Task<ActionResult<Announcement>> Details(int id, CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var row = await db.PlatformAnnouncements.FindAsync([id], ct);
        return row is null ? NotFound() : Describe(row);
    }

    [HttpPost("/api/admin/announcements"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(32768)]
    public async Task<ActionResult<Announcement>> Create(AnnouncementInput input, CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var now = clock.GetUtcNow().UtcDateTime;
        var row = new PlatformAnnouncement { Title = input.Title.Trim(), Message = input.Message.Trim(),
            CreatedAtUtc = now, UpdatedAtUtc = now };
        db.PlatformAnnouncements.Add(row);
        await db.SaveChangesAsync(ct);
        return StatusCode(201, Describe(row));
    }

    [HttpPut("/api/admin/announcements/{id:int}"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(32768)]
    public async Task<ActionResult<Announcement>> Update(int id, AnnouncementInput input, CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var row = await db.PlatformAnnouncements.FindAsync([id], ct);
        if (row is null) return NotFound();
        if (row.Status == "Archived") return Conflict();
        row.Title = input.Title.Trim();
        row.Message = input.Message.Trim();
        row.UpdatedAtUtc = clock.GetUtcNow().UtcDateTime;
        return await SaveAsync(row, ct);
    }

    [HttpPut("/api/admin/announcements/{id:int}/status"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(4096)]
    public async Task<ActionResult<Announcement>> Status(int id, StatusInput input, CancellationToken ct)
    {
        if (input.Status is not ("Draft" or "Published" or "Archived")) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var row = await db.PlatformAnnouncements.FindAsync([id], ct);
        if (row is null) return NotFound();
        if (row.Status == input.Status) return Describe(row);
        if (row.Status == "Archived") return Conflict();
        var now = clock.GetUtcNow().UtcDateTime;
        if (input.Status == "Published") row.PublishedAtUtc = now;
        row.Status = input.Status;
        row.UpdatedAtUtc = now;
        return await SaveAsync(row, ct);
    }

    private async Task<ActionResult<Announcement>> SaveAsync(PlatformAnnouncement row, CancellationToken ct)
    {
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateConcurrencyException) { return Conflict(); }
        return Describe(row);
    }

    private static Announcement Describe(PlatformAnnouncement x) =>
        new(x.Id, x.Title, x.Message, x.Status, x.CreatedAtUtc, x.UpdatedAtUtc, x.PublishedAtUtc);
    public sealed record Announcement(int Id, string Title, string Message, string Status,
        DateTime CreatedAtUtc, DateTime UpdatedAtUtc, DateTime? PublishedAtUtc);
    public sealed record AnnouncementInput([Required, StringLength(120)] string Title,
        [Required, StringLength(4000)] string Message);
    public sealed record StatusInput([Required, StringLength(16)] string Status);
}
