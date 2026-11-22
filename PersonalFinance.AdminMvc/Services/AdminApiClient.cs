using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Claims;
using System.Text.Json;
using PersonalFinance.AdminMvc.Authentication;

namespace PersonalFinance.AdminMvc.Services;

public sealed record RegisteredUser(string FullName, string? Email, DateTime CreatedAtUtc, bool IsLockedOut, int Id, string Role);
public sealed record RegisteredUsersPage(IReadOnlyList<RegisteredUser> Items, int Page, int PageSize, long TotalCount);
public sealed record AdminStatistics(long RegisteredUsers, long ActiveSharedCategories,
    long InactiveSharedCategories, DateTimeOffset UpdatedAtUtc);
public sealed record AdminResult<T>(int Status, T? Data = null) where T : class;

public sealed class AdminApiClient(HttpClient http, AdminSessionStore sessions)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<AdminResult<AdminStatistics>> GetStatisticsAsync(ClaimsPrincipal principal, CancellationToken ct) =>
        GetAsync<AdminStatistics>("api/admin/statistics", principal, ct);

    public Task<AdminResult<RegisteredUsersPage>> GetUsersAsync(
        ClaimsPrincipal principal, string search, int page, string status, CancellationToken ct) =>
        GetAsync<RegisteredUsersPage>(FormattableString.Invariant(
            $"api/admin/users?page={page}&search={Uri.EscapeDataString(search)}&status={Uri.EscapeDataString(status)}"), principal, ct);

    public Task<AdminResult<RegisteredUser>> GetUserAsync(ClaimsPrincipal principal, int id, CancellationToken ct) =>
        GetAsync<RegisteredUser>(FormattableString.Invariant($"api/admin/users/{id}"), principal, ct);

    public Task<AdminResult<PlatformActivity>> GetActivityAsync(ClaimsPrincipal principal, CancellationToken ct) =>
        GetAsync<PlatformActivity>("api/admin/activity", principal, ct);

    public Task<AdminResult<AuditLogPage>> GetAuditAsync(ClaimsPrincipal principal, string search, int page, CancellationToken ct) =>
        GetAsync<AuditLogPage>(FormattableString.Invariant(
            $"api/admin/audit?page={page}&search={Uri.EscapeDataString(search)}"), principal, ct);

    public Task<AdminResult<SystemHealth>> GetHealthAsync(ClaimsPrincipal principal, CancellationToken ct) =>
        GetAsync<SystemHealth>("api/admin/health", principal, ct);

    public Task<AdminResult<T>> GetAsync<T>(string path, ClaimsPrincipal principal, CancellationToken ct) where T : class =>
        SendAsync<T>(HttpMethod.Get, path, null, principal, ct);

    public async Task<AdminResult<T>> SendAsync<T>(HttpMethod method, string path, object? body,
        ClaimsPrincipal principal, CancellationToken ct)
        where T : class
    {
        var session = sessions.Find(principal.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null) return new(401);
        await session.Gate.WaitAsync(ct);
        try
        {
            if (!sessions.IsCurrent(session) || session.Tokens.AccessTokenExpiresAtUtc <= DateTime.UtcNow)
                return new(401);
            if (!principal.IsInRole("Administrator") || !session.User.Roles.Contains("Administrator", StringComparer.Ordinal)
                || principal.FindFirstValue(ClaimTypes.NameIdentifier) != session.User.Id.ToString(CultureInfo.InvariantCulture))
                return new(403);

            using var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", session.Tokens.AccessToken);
            using var response = await http.SendAsync(request, ct);
            var status = (int)response.StatusCode;
            if (status == 401) sessions.Invalidate(session);
            if (status is 400 or 401 or 403 or 404 or 409 or 429) return new(status);
            if (status is not (200 or 201) || response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);

            var data = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return data is null ? new(503) : new(200, data);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return new(503); }
        catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException or NotSupportedException)
        {
            return new(503);
        }
        finally { session.Gate.Release(); }
    }
}
