using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member notification inbox. Read-only list over the signed-in member's own notifications; the API has no
/// mark-as-read endpoint and none is invented here.
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
}
