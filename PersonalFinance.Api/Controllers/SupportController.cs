using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/support")]
public sealed class SupportController(ApplicationDbContext db, TimeProvider clock) : ControllerBase
{
    [HttpPost, RequestSizeLimit(32768)]
    public async Task<ActionResult<Ticket>> Submit(Submission input, CancellationToken ct)
    {
        if (CurrentUser.ReadUserId(User) is not int owner) return Unauthorized();
        if (input.Kind is not ("Feedback" or "Support")) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var now = clock.GetUtcNow().UtcDateTime;
        var row = new SupportRequest { OwnerId = owner, Kind = input.Kind, Subject = input.Subject.Trim(),
            Message = input.Message.Trim(), CreatedAtUtc = now, UpdatedAtUtc = now };
        db.SupportRequests.Add(row);
        await db.SaveChangesAsync(ct);
        return CreatedAtAction(nameof(OwnDetails), new { id = row.Id }, await DescribeAsync(row, 1, ct));
    }

    [HttpGet]
    public async Task<ActionResult<TicketSummary[]>> Mine(CancellationToken ct, int page = 1)
    {
        if (CurrentUser.ReadUserId(User) is not int owner) return Unauthorized();
        return await ListAsync(db.SupportRequests.Where(x => x.OwnerId == owner), page, 20, ct);
    }

    [HttpGet("{id:int}")]
    public async Task<ActionResult<Ticket>> OwnDetails(int id, CancellationToken ct, int replyPage = 1)
    {
        if (CurrentUser.ReadUserId(User) is not int owner) return Unauthorized();
        return await DetailsAsync(db.SupportRequests.Where(x => x.OwnerId == owner), id, replyPage, ct);
    }

    [HttpGet("/api/admin/support"), Authorize(Roles = ApplicationRoles.Administrator)]
    public async Task<ActionResult<TicketSummary[]>> Inbox(CancellationToken ct, string? search = null,
        string? status = null, string? kind = null, int page = 1)
    {
        search = search?.Trim();
        if (search?.Length > 100 || status is not (null or "Open" or "InProgress" or "Resolved")
            || kind is not (null or "Feedback" or "Support")) return BadRequest();
        var rows = db.SupportRequests.AsQueryable();
        if (!string.IsNullOrEmpty(search)) rows = rows.Where(x => x.Subject.Contains(search) || x.Message.Contains(search));
        if (status is not null) rows = rows.Where(x => x.Status == status);
        if (kind is not null) rows = rows.Where(x => x.Kind == kind);
        return await ListAsync(rows, page, 21, ct);
    }

    [HttpGet("/api/admin/support/{id:int}"), Authorize(Roles = ApplicationRoles.Administrator)]
    public Task<ActionResult<Ticket>> AdminDetails(int id, CancellationToken ct, int replyPage = 1) =>
        DetailsAsync(db.SupportRequests, id, replyPage, ct);

    [HttpPut("/api/admin/support/{id:int}/status"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(4096)]
    public async Task<ActionResult<Ticket>> Status(int id, AnnouncementsController.StatusInput input, CancellationToken ct)
    {
        if (input.Status is not ("Open" or "InProgress" or "Resolved")) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var row = await db.SupportRequests.SingleOrDefaultAsync(x => x.Id == id, ct);
        if (row is null) return NotFound();
        if (row.Status == input.Status) return await DescribeAsync(row, 1, ct);
        row.Status = input.Status;
        row.UpdatedAtUtc = clock.GetUtcNow().UtcDateTime;
        return await SaveAsync(row, ct);
    }

    [HttpPost("/api/admin/support/{id:int}/replies"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(32768)]
    public async Task<ActionResult<Ticket>> Reply(int id, ReplyInput input, CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        var row = await db.SupportRequests.SingleOrDefaultAsync(x => x.Id == id, ct);
        if (row is null) return NotFound();
        var now = clock.GetUtcNow().UtcDateTime;
        row.Replies.Add(new SupportReply { Message = input.Message.Trim(), CreatedAtUtc = now });
        row.UpdatedAtUtc = now;
        return await SaveAsync(row, ct);
    }

    private async Task<ActionResult<TicketSummary[]>> ListAsync(IQueryable<SupportRequest> rows, int page, int count, CancellationToken ct)
    {
        if (page is < 1 or > 1000000) return BadRequest();
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        return await rows.AsNoTracking().OrderByDescending(x => x.UpdatedAtUtc).ThenByDescending(x => x.Id)
            .Skip((page - 1) * 20).Take(count)
            .Select(x => new TicketSummary(x.Id, x.Kind, x.Subject, x.Status, x.CreatedAtUtc, x.UpdatedAtUtc))
            .ToArrayAsync(ct);
    }

    private async Task<ActionResult<Ticket>> DetailsAsync(IQueryable<SupportRequest> rows, int id, int replyPage, CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        if (replyPage is < 1 or > 1000000) return BadRequest();
        var row = await rows.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct);
        return row is null ? NotFound() : await DescribeAsync(row, replyPage, ct);
    }

    private async Task<ActionResult<Ticket>> SaveAsync(SupportRequest row, CancellationToken ct)
    {
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateConcurrencyException) { return Conflict(); }
        return await DescribeAsync(row, 1, ct);
    }

    private async Task<Ticket> DescribeAsync(SupportRequest row, int replyPage, CancellationToken ct)
    {
        const int pageSize = 20;
        var replies = await db.SupportReplies.AsNoTracking().Where(reply => reply.SupportRequestId == row.Id)
            .OrderByDescending(reply => reply.CreatedAtUtc).ThenByDescending(reply => reply.Id)
            .Skip((replyPage - 1) * pageSize).Take(pageSize + 1)
            .Select(reply => new ReplyDto(reply.Message, reply.CreatedAtUtc)).ToArrayAsync(ct);
        return new(row.Id, row.Kind, row.Subject, row.Message, row.Status, row.CreatedAtUtc, row.UpdatedAtUtc,
            replies.Take(pageSize).ToArray(), replyPage, replies.Length > pageSize);
    }
    public sealed record TicketSummary(int Id, string Kind, string Subject, string Status, DateTime CreatedAtUtc, DateTime UpdatedAtUtc);
    public sealed record Ticket(int Id, string Kind, string Subject, string Message, string Status,
        DateTime CreatedAtUtc, DateTime UpdatedAtUtc, ReplyDto[] Replies, int ReplyPage, bool HasMoreReplies);
    public sealed record ReplyDto(string Message, DateTime CreatedAtUtc);
    public sealed record Submission([Required, StringLength(16)] string Kind,
        [Required, StringLength(120)] string Subject, [Required, StringLength(4000)] string Message);
    public sealed record ReplyInput([Required, StringLength(4000)] string Message);
}
