using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/platform-settings")]
public sealed class PlatformSettingsController(ApplicationDbContext db, TimeProvider clock) : ControllerBase
{
    [HttpGet]
    public async Task<ActionResult<Setting[]>> Read(CancellationToken ct)
    {
        if (!await PlatformContentSchema.AvailableAsync(db, ct)) return StatusCode(503);
        return await db.PlatformSettings.AsNoTracking()
            .Where(row => row.Key == "welcomeMessage" || row.Key == "supportInstructions")
            .OrderBy(row => row.Id).Select(row => new Setting(row.Key, row.Value, row.UpdatedAtUtc)).ToArrayAsync(ct);
    }

    [HttpPut("/api/admin/platform-settings/{key}"), Authorize(Roles = ApplicationRoles.Administrator), RequestSizeLimit(8192)]
    public async Task<ActionResult<Setting>> Update(string key, SettingInput input, CancellationToken ct)
    {
        if (CurrentUser.ReadUserId(User) is not int actor) return Unauthorized();
        if (key is not ("welcomeMessage" or "supportInstructions")) return NotFound();
        if (!await PlatformContentSchema.AvailableAsync(db, ct) || !await PlatformJournal.IsAvailableAsync(db, ct))
            return StatusCode(503);

        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var row = await db.PlatformSettings.SingleOrDefaultAsync(x => x.Key == key, ct);
        if (row is null) return StatusCode(503);
        var value = input.Value.Trim();
        if (row.Value == value) return new Setting(row.Key, row.Value, row.UpdatedAtUtc);
        row.Value = value;
        var now = clock.GetUtcNow().UtcDateTime;
        row.UpdatedAtUtc = now;
        try
        {
            await db.SaveChangesAsync(ct);
            await PlatformJournal.RecordAsync(db, actor, "Update", "PlatformSetting", row.Id, now, ct);
            await transaction.CommitAsync(ct);
        }
        catch (DbUpdateConcurrencyException) { return Conflict(); }
        return new Setting(row.Key, row.Value, row.UpdatedAtUtc);
    }

    public sealed record Setting(string Key, string Value, DateTime? UpdatedAtUtc);
    public sealed record SettingInput([Required(AllowEmptyStrings = true), StringLength(500)] string Value);
}
