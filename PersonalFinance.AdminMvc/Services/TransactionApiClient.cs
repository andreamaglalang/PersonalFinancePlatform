using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace PersonalFinance.AdminMvc.Services;

public sealed class TransactionApiClient(HttpClient http)
{
    public const int PageSize = 20;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<TransactionResult<TransactionPage>> ListAsync(string? type, int? categoryId,
        DateOnly? fromDate, DateOnly? toDate, int page, string bearer, CancellationToken ct)
    {
        var route = FormattableString.Invariant($"api/transactions?Page={page}&PageSize={PageSize}&SortBy=date&SortDirection=desc");
        if (!string.IsNullOrWhiteSpace(type)) route += "&Type=" + Uri.EscapeDataString(type);
        if (categoryId is { } id) route += FormattableString.Invariant($"&CategoryId={id}");
        if (fromDate is { } from) route += "&FromDate=" + from.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        if (toDate is { } to) route += "&ToDate=" + to.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        return SendAsync<TransactionPage>(HttpMethod.Get, route, null, 200, bearer, response =>
            response.Page == page && response.PageSize == PageSize && response.Items is not null
            && response.Items.Count <= PageSize && response.TotalCount >= response.Items.Count
            && response.Items.All(Valid), ct);
    }

    // Retrieves a complete, optionally API-filtered owner history. Every API page must agree on the
    // same total and contain unique valid rows before callers may calculate a summary from it.
    public async Task<TransactionResult<IReadOnlyList<TransactionResponse>>> ListAllAsync(
        string bearer, CancellationToken ct, string? type = null, int? categoryId = null,
        DateOnly? fromDate = null, DateOnly? toDate = null)
    {
        var first = await ListAsync(type, categoryId, fromDate, toDate, 1, bearer, ct);
        if (!first.Succeeded || first.Value is null) return new(first.Status);

        var expectedTotal = first.Value.TotalCount;
        if (expectedTotal > 100_000) return new(503);
        var pageCount = expectedTotal == 0 ? 1 : checked((int)Math.Ceiling(expectedTotal / (double)PageSize));
        var rows = new List<TransactionResponse>((int)expectedTotal);
        var ids = new HashSet<int>();

        for (var page = 1; page <= pageCount; page++)
        {
            var result = page == 1 ? first : await ListAsync(type, categoryId, fromDate, toDate, page, bearer, ct);
            if (!result.Succeeded || result.Value is null || result.Value.TotalCount != expectedTotal)
                return new(result.Status == 200 ? 503 : result.Status);

            var expectedPageItems = expectedTotal == 0 ? 0
                : (int)Math.Min(PageSize, expectedTotal - (long)(page - 1) * PageSize);
            if (result.Value.Items.Count != expectedPageItems
                || result.Value.Items.Any(row => !ids.Add(row.TransactionId)))
                return new(503);
            rows.AddRange(result.Value.Items);
        }

        return rows.Count == expectedTotal
            ? new(200, rows.OrderByDescending(row => row.TransactionDate)
                .ThenByDescending(row => row.TransactionId).ToArray())
            : new(503);
    }

    public Task<TransactionResult<TransactionResponse>> GetAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<TransactionResponse>(HttpMethod.Get, Route(id), null, 200, bearer,
            response => Valid(response) && response.TransactionId == id, ct);

    public Task<TransactionResult<TransactionResponse>> CreateAsync(CreateTransactionRequest request, string bearer, CancellationToken ct) =>
        SendAsync<TransactionResponse>(HttpMethod.Post, "api/transactions", request, 201, bearer,
            response => Valid(response) && response.ClientId == request.ClientId, ct);

    public Task<TransactionResult<TransactionResponse>> UpdateAsync(int id, UpdateTransactionRequest request, string bearer, CancellationToken ct) =>
        SendAsync<TransactionResponse>(HttpMethod.Put, Route(id), request, 200, bearer,
            response => Valid(response) && response.TransactionId == id, ct);

    public Task<TransactionResult<TransactionDeletionResponse>> DeleteAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<TransactionDeletionResponse>(HttpMethod.Delete, Route(id), null, 200, bearer,
            response => response.TransactionId == id, ct);

    private static string Route(int id) =>
        "api/transactions/" + id.ToString(CultureInfo.InvariantCulture);

    internal static bool Valid(TransactionResponse? row) =>
        row is { TransactionId: > 0, CategoryId: > 0, Amount: > 0, AmountBase: > 0, FxRate: > 0 }
        && row.Type is "Income" or "Expense"
        && row.CurrencyCode.Length == 3 && row.CurrencyCode.All(c => c is >= 'A' and <= 'Z')
        && !string.IsNullOrWhiteSpace(row.CategoryName)
        && (row.AffectedBudgets is null || row.AffectedBudgets.All(BudgetApiClient.Valid));

    private async Task<TransactionResult<T>> SendAsync<T>(HttpMethod method, string route, object? body, int expected,
        string bearer, Func<T, bool>? validate, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            if (body is not null) request.Content = JsonContent.Create(body, body.GetType(), options: Json);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 409 or 429) return new(status) { Message = status is 400 or 404 or 409 ? await ApiMessages.ReadAsync(response, ct) : null };
            // A replayed ClientId is answered 200 with the original row (validated below), not 201.
            if (status != expected && !(expected == 201 && status == 200)) return new(503);
            if (response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return value is not null && validate!(value) ? new(status, value) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return new(503);
        }
    }
}
