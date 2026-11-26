using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

/// <summary>The signed-in member's own notifications, newest first, and their read state; administrators see only their own.</summary>
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

    /// <summary>POST api/notifications/{id}/read → 204 (idempotent); another user's or a missing notification → 404.</summary>
    [HttpPost("{id:int}/read"), ProducesResponseType(204), ProducesResponseType(404)]
    public async Task<IActionResult> MarkRead(int id, CancellationToken ct) => Respond(await notifications.MarkReadAsync(id, ct));

    /// <summary>POST api/notifications/read-all → 204.</summary>
    [HttpPost("read-all"), ProducesResponseType(204)]
    public async Task<IActionResult> MarkAllRead(CancellationToken ct) => Respond(await notifications.MarkAllReadAsync(ct));

    private IActionResult Respond(FinanceResult<bool> result) =>
        result.Succeeded ? NoContent() : Problem(statusCode: result.StatusCode, title: result.Error);
}
