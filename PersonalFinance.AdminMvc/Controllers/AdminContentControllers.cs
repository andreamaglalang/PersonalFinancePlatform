using System.ComponentModel.DataAnnotations;
using System.Globalization;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public abstract class AdminContentController(AdminApiClient api) : Controller
{
    protected Task<AdminResult<T>> Read<T>(string path, CancellationToken ct) where T : class =>
        api.GetAsync<T>(path, User, ct);

    protected Task<AdminResult<T>> Write<T>(HttpMethod method, string path, object input, CancellationToken ct) where T : class =>
        api.SendAsync<T>(method, path, input, User, ct);

    protected async Task<IActionResult?> AuthFailure(int status)
    {
        if (status == 403) return Forbid();
        if (status != 401) return null;
        await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
        return RedirectToAction("Login", "Account");
    }

    protected string Error(int status)
    {
        Response.StatusCode = status is 400 or 404 or 409 or 429 ? status : 503;
        return status switch
        {
            400 => "The API rejected these values. Check your input and retry.",
            404 => "This record could not be found.",
            409 => "The record changed or this action is unavailable. Reload before retrying.",
            429 => "Too many requests. Try again shortly.",
            _ => "The service is unavailable. Check that the platform content migration is applied and try again."
        };
    }

    protected static string ItemPath(string path, int id) => path + "/" + id.ToString(CultureInfo.InvariantCulture);
}

public sealed class AnnouncementsController(AdminApiClient api) : AdminContentController(api)
{
    private const string Path = "api/admin/announcements";

    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct, int page = 1)
    {
        var result = await Read<AnnouncementRow[]>(FormattableString.Invariant($"{Path}?page={page}"), ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        return View(new AnnouncementList(result.Data, page, result.Data is null ? Error(result.Status) : null));
    }

    [HttpGet]
    public async Task<IActionResult> Edit(int? id, CancellationToken ct)
    {
        if (id is null) return View(new AnnouncementForm());
        var result = await Read<AnnouncementRow>(ItemPath(Path, id.Value), ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        if (result.Data is not { } row) return View(new AnnouncementForm { Error = Error(result.Status) });
        return View(new AnnouncementForm { Id = row.Id, Title = row.Title, Message = row.Message, Status = row.Status });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Save(int? id, AnnouncementForm form, CancellationToken ct)
    {
        form.Id = id;
        if (!ModelState.IsValid) return View("Edit", form);
        var result = await Write<AnnouncementRow>(id.HasValue ? HttpMethod.Put : HttpMethod.Post,
            id.HasValue ? ItemPath(Path, id.Value) : Path, new { form.Title, form.Message }, ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        if (result.Data is null)
        {
            form.Error = Error(result.Status);
            return View("Edit", form);
        }
        TempData["ContentNotice"] = id.HasValue ? "Announcement saved." : "Draft created.";
        return RedirectToAction(nameof(Index));
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Status(int id, string status, CancellationToken ct)
    {
        var result = await Write<AnnouncementRow>(HttpMethod.Put, ItemPath(Path, id) + "/status", new { status }, ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        TempData[result.Data is null ? "ContentError" : "ContentNotice"] =
            result.Data is null ? Error(result.Status) : "Announcement status updated.";
        return RedirectToAction(nameof(Index));
    }
}

public sealed class SupportInboxController(AdminApiClient api) : AdminContentController(api)
{
    private const string Path = "api/admin/support";

    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct, string? search, string? status, string? kind, int page = 1)
    {
        search = search?.Trim() ?? "";
        status ??= "";
        kind ??= "";
        var result = await Read<TicketSummary[]>(FormattableString.Invariant(
            $"{Path}?page={page}&search={Uri.EscapeDataString(search)}&status={Uri.EscapeDataString(status)}&kind={Uri.EscapeDataString(kind)}"), ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        return View(new SupportList(result.Data, search, status, kind, page, result.Data is null ? Error(result.Status) : null));
    }

    [HttpGet]
    public async Task<IActionResult> Details(int id, CancellationToken ct, int replyPage = 1)
    {
        var result = await Read<SupportTicket>(ItemPath(Path, id) + FormattableString.Invariant($"?replyPage={replyPage}"), ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        return View(new TicketDetail(result.Data, result.Data is null ? Error(result.Status) : null));
    }

    [HttpPost, ValidateAntiForgeryToken]
    public Task<IActionResult> Status(int id, string status, CancellationToken ct) =>
        Change(id, HttpMethod.Put, "/status", new { status }, "Request status saved.", null, ct);

    [HttpPost, ValidateAntiForgeryToken]
    public Task<IActionResult> Reply(int id, string? message, CancellationToken ct) =>
        Change(id, HttpMethod.Post, "/replies", new { message }, "Reply sent to the request owner.", message, ct);

    private async Task<IActionResult> Change(int id, HttpMethod method, string suffix, object input,
        string notice, string? draft, CancellationToken ct)
    {
        var path = ItemPath(Path, id);
        var result = await Write<SupportTicket>(method, path + suffix, input, ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        if (result.Data is not null)
        {
            TempData["ContentNotice"] = notice;
            return RedirectToAction(nameof(Details), new { id });
        }
        var current = await Read<SupportTicket>(path, ct);
        return View("Details", new TicketDetail(current.Data, Error(result.Status), draft));
    }
}

public sealed class PlatformSettingsController(AdminApiClient api) : AdminContentController(api)
{
    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var result = await Read<SettingRow[]>("api/platform-settings", ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        return View(new SettingsPage(result.Data, result.Data is null ? Error(result.Status) : null));
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Save(string key, string? value, CancellationToken ct)
    {
        if (key is not ("welcomeMessage" or "supportInstructions")) return BadRequest();
        var result = await Write<SettingRow>(HttpMethod.Put, "api/admin/platform-settings/" + key, new { value = value ?? "" }, ct);
        if (await AuthFailure(result.Status) is { } failure) return failure;
        if (result.Data is not null)
        {
            TempData["ContentNotice"] = "Setting saved.";
            return RedirectToAction(nameof(Index));
        }

        var current = await Read<SettingRow[]>("api/platform-settings", ct);
        return View("Index", new SettingsPage(current.Data, Error(result.Status), key, value ?? ""));
    }
}

public sealed record AnnouncementRow(int Id, string Title, string Message, string Status,
    DateTime CreatedAtUtc, DateTime UpdatedAtUtc, DateTime? PublishedAtUtc);
public sealed record AnnouncementList(AnnouncementRow[]? Rows, int Page, string? Error);
public sealed class AnnouncementForm
{
    public int? Id { get; set; }
    [Required, StringLength(120)] public string Title { get; set; } = "";
    [Required, StringLength(4000)] public string Message { get; set; } = "";
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Status { get; set; }
    [Microsoft.AspNetCore.Mvc.ModelBinding.BindNever] public string? Error { get; set; }
}
public sealed record TicketSummary(int Id, string Kind, string Subject, string Status, DateTime CreatedAtUtc, DateTime UpdatedAtUtc);
public sealed record SupportTicket(int Id, string Kind, string Subject, string Message, string Status,
    DateTime CreatedAtUtc, DateTime UpdatedAtUtc, SupportReplyRow[] Replies, int ReplyPage, bool HasMoreReplies);
public sealed record SupportReplyRow(string Message, DateTime CreatedAtUtc);
public sealed record SupportList(TicketSummary[]? Rows, string Search, string Status, string Kind, int Page, string? Error);
public sealed record TicketDetail(SupportTicket? Ticket, string? Error, string? Draft = null);
public sealed record SettingRow(string Key, string Value, DateTime? UpdatedAtUtc);
public sealed record SettingsPage(SettingRow[]? Rows, string? Error, string? FailedKey = null, string? Draft = null);
