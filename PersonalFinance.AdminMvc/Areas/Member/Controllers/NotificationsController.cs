using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member notification inbox over the signed-in member's own notifications, with read state shared with Android
/// through the API (C3). Notifications are never created, edited or deleted here.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class NotificationsController(NotificationsApiClient notifications, AdminSessionStore sessions)
    : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] MemberNotificationIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["NotificationNotice"] as string;
        if (model.Page < 1 || (long)(model.Page - 1) * NotificationsApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid page number.";
            return View(model);
        }

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await notifications.ListAsync(model.UnreadOnly, model.Page, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (result.Succeeded) model.Results = result.Page!;
        else
        {
            Response.StatusCode = result.Status;
            model.Error = MemberSessionGate.Message(result.Status);
        }
        return View(model);
    }

    [HttpPost, ValidateAntiForgeryToken]
    public Task<IActionResult> MarkRead([FromForm] int id, [FromForm] bool unreadOnly, [FromForm] int page, CancellationToken ct) =>
        UpdateReadState(token => notifications.MarkReadAsync(id, token, ct), "Notification marked as read.", unreadOnly, page, ct);

    [HttpPost, ValidateAntiForgeryToken]
    public Task<IActionResult> MarkAllRead([FromForm] bool unreadOnly, CancellationToken ct) =>
        UpdateReadState(token => notifications.MarkAllReadAsync(token, ct), "All notifications marked as read.", unreadOnly, 1, ct);

    private async Task<IActionResult> UpdateReadState(Func<string, Task<int>> send, string success, bool unreadOnly, int page,
        CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await send(token!);
        if (result == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        TempData["NotificationNotice"] = result == 204 ? success
            : result == 404 ? "That notification is no longer available." : MemberSessionGate.Message(result, mutation: true);
        return RedirectToAction(nameof(Index), new { UnreadOnly = unreadOnly, Page = page < 1 ? 1 : page });
    }
}
