using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewComponents;

/// <summary>Each member page/PRG re-reads the shared inbox; no cached cross-account count.</summary>
public sealed class NotificationUnreadCountViewComponent(NotificationsApiClient notifications, AdminSessionStore sessions) : ViewComponent
{
    public async Task<IViewComponentResult> InvokeAsync()
    {
        var session = sessions.Find(HttpContext.User.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null) return Content("");
        string? token = null;
        await session.Gate.WaitAsync(HttpContext.RequestAborted);
        try
        {
            if (sessions.IsCurrent(session) && HttpContext.User.FindFirstValue(ClaimTypes.NameIdentifier)
                == session.User.Id.ToString(CultureInfo.InvariantCulture) && session.Tokens.AccessTokenExpiresAtUtc > DateTime.UtcNow)
                token = session.Tokens.AccessToken;
        }
        finally { session.Gate.Release(); }
        if (token is null) return Content("");
        var result = await notifications.ListAsync(true, 1, token, HttpContext.RequestAborted);
        return View(result.Succeeded ? result.Page!.TotalCount : (long?)null);
    }
}
