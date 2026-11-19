using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

/// <summary>The signed-in member's own notifications, newest first. Read-only; administrators see only their own.</summary>
[ApiController, Authorize, Route("api/notifications")]
public sealed class NotificationsController(INotificationService notifications) : ControllerBase
{
    /// <summary>GET api/notifications?unreadOnly=false&amp;page=1&amp;pageSize=20</summary>
    [HttpGet, ProducesResponseType<PagedResponse<NotificationResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] NotificationQuery query, CancellationToken ct)
    {
        var result = await notifications.ListAsync(query, ct);
        return result.Succeeded ? Ok(result.Value) : Problem(statusCode: result.StatusCode, title: result.Error);
    }
}
