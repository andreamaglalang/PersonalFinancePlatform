using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using PersonalFinance.AdminMvc.Authentication;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>
/// Resolves the signed-in member's access token from the shared in-memory session. Mirrors the administrator
/// gate in <c>BudgetsController.InSessionAsync</c> but applies the User policy only: any authenticated member,
/// including an administrator, and never an Administrator role check.
/// </summary>
public static class MemberSessionGate
{
    /// <summary>Returns the stored access token for the caller's own session, or 401 when the session is
    /// missing, stale, owned by another user, or its access token has expired. Signs the cookie out on 401.</summary>
    public static async Task<(int Status, string? Token)> AcquireAsync(
        ClaimsPrincipal user, AdminSessionStore store, HttpContext http, CancellationToken ct)
    {
        var session = store.Find(user.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null)
        {
            await http.SignOutAsync(AdminSessionStore.Scheme);
            return (401, null);
        }

        await session.Gate.WaitAsync(ct);
        try
        {
            var current = store.IsCurrent(session)
                && user.Identity?.IsAuthenticated == true
                && user.FindFirstValue(ClaimTypes.NameIdentifier)
                    == session.User.Id.ToString(CultureInfo.InvariantCulture)
                && session.Tokens.AccessTokenExpiresAtUtc > DateTime.UtcNow;
            if (!current)
            {
                store.Invalidate(session);
                return (401, null);
            }
            return (200, session.Tokens.AccessToken);
        }
        finally { session.Gate.Release(); }
    }

    /// <summary>Drops the stored session and signs the cookie out. Used when the API rejects the token.</summary>
    public static async Task<int> RejectAsync(
        ClaimsPrincipal user, AdminSessionStore store, HttpContext http)
    {
        var session = store.Find(user.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is not null)
        {
            await session.Gate.WaitAsync();
            try { store.Invalidate(session); }
            finally { session.Gate.Release(); }
        }
        await http.SignOutAsync(AdminSessionStore.Scheme);
        return 401;
    }

    /// <summary>The signed-in member's base currency, used to prefill the create form. Never a secret.</summary>
    public static string BaseCurrency(ClaimsPrincipal user, AdminSessionStore store, string fallback = "PHP") =>
        store.Find(user.FindFirstValue(AdminSessionStore.SessionClaim)) is { User.BaseCurrency: { Length: 3 } currency }
            ? currency
            : fallback;

    public static string Message(int status, bool mutation = false) => status switch
    {
        400 => "The API rejected those details. Check the values and try again.",
        404 => "That record is no longer available. Reload the list.",
        409 => "The change conflicts with an existing record or a concurrent change. Reload and review before retrying.",
        429 => "Too many requests. Wait before trying again.",
        _ => mutation
            ? "The API could not confirm the change. Reload the list before submitting again."
            : "The API is unavailable or returned an invalid response. Try again later."
    };
}
