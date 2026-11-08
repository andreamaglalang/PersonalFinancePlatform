using System.Globalization;
using System.Security.Claims;
using PersonalFinance.Infrastructure.Authentication;

namespace PersonalFinance.Api.Authentication;

public sealed class CurrentUser(IHttpContextAccessor accessor) : ICurrentUser
{
    public int? UserId => ReadUserId(accessor.HttpContext?.User);
    public bool IsAdministrator => UserId.HasValue && accessor.HttpContext!.User.IsInRole("Administrator");
    // Administrator is deliberately not an ownership bypass.
    public bool Owns(int resourceOwnerId) => UserId is int userId && userId == resourceOwnerId;

    public static int? ReadUserId(ClaimsPrincipal? principal) =>
        principal?.Identity?.IsAuthenticated == true
        && int.TryParse(principal.FindFirst("sub")?.Value, NumberStyles.None, CultureInfo.InvariantCulture, out var id)
        && id > 0 ? id : null;
}

