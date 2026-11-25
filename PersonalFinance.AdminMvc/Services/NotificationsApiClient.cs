using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.AdminMvc.Services;

// Mirrors PersonalFinance.Infrastructure.Finance.Contracts.NotificationResponse exactly. No extra properties:
// RespectRequiredConstructorParameters would reject a body the API does not define.
public sealed record NotificationResponse(int NotificationId, string Type, string? ReferenceType, int? ReferenceId,
    string Message, bool IsRead, DateTime CreatedAtUtc);

public sealed record NotificationPage(IReadOnlyList<NotificationResponse> Items, int Page, int PageSize,
    long TotalCount);

public sealed record NotificationResult(int Status, NotificationPage? Page = null)
{
    public bool Succeeded => Status is 200;
}

/// <summary>
/// api/notifications. Read-only: the API exposes no read-state mutation, so this client never writes.
/// </summary>
public sealed class NotificationsApiClient(HttpClient http)
{
    public const int PageSize = 20;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<NotificationResult> ListAsync(bool unreadOnly, int page, string bearer, CancellationToken ct) =>
        SendAsync(unreadOnly, page, bearer, ct);

    private async Task<NotificationResult> SendAsync(bool unreadOnly, int page, string bearer, CancellationToken ct)
    {
        try
        {
            var route = FormattableString.Invariant(
                $"api/notifications?UnreadOnly={(unreadOnly ? "true" : "false")}&Page={page}&PageSize={PageSize}");
            using var request = new HttpRequestMessage(HttpMethod.Get, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 409 or 428 or 429) return new(status);
            if (status != 200) return new(503);
            if (response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<NotificationPage>(Json, ct);
            return value is not null && Valid(value, page, unreadOnly) ? new(status, value) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException or JsonException)
        {
            return new(503);
        }
    }

    internal static bool Valid(NotificationPage? page, int requestedPage, bool unreadOnly) => page is
    {
        Page: > 0, PageSize: > 0, Items: not null
    } && page.Page == requestedPage && page.PageSize == PageSize
        && page.Items.Count <= PageSize && page.TotalCount >= page.Items.Count
        && page.Items.All(Valid)
        && (!unreadOnly || page.Items.All(n => n.IsRead is false));

    internal static bool Valid(NotificationResponse? notification) => notification is
    {
        NotificationId: > 0, Message.Length: > 0
    } && !string.IsNullOrWhiteSpace(notification.Type)
        && (notification.ReferenceType is null || !string.IsNullOrWhiteSpace(notification.ReferenceType))
        && (notification.ReferenceId is null || notification.ReferenceId > 0)
        && notification.CreatedAtUtc != default;
}
