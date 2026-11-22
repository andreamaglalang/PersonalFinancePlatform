using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.AdminMvc.Services;

public sealed record BudgetResponse(int BudgetId, int CategoryId, string CategoryName, bool IsCategoryActive,
    DateOnly PeriodMonth, decimal LimitAmount, byte AlertThresholdPct, decimal SpentAmount,
    decimal RemainingAmount, string Status, string BaseCurrency);

public sealed record BudgetPage(IReadOnlyList<BudgetResponse> Items, int Page, int PageSize, long TotalCount);
public sealed record CreateBudgetRequest(int CategoryId, DateOnly PeriodMonth, decimal LimitAmount, int AlertThresholdPct);
public sealed record UpdateBudgetRequest(decimal LimitAmount, int AlertThresholdPct);

public sealed record BudgetResult<T>(int Status, T? Value = default)
{
    public bool Succeeded => Status is 200 or 201 or 204;
}

public sealed class BudgetApiClient(HttpClient http)
{
    public const int PageSize = 20;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<BudgetResult<BudgetPage>> ListAsync(DateOnly? month, int page, string bearer, CancellationToken ct, int? categoryId = null)
    {
        var route = FormattableString.Invariant($"api/budgets?Page={page}&PageSize={PageSize}");
        if (month is { } value) route += "&PeriodMonth=" + value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        if (categoryId is int id) route += "&CategoryId=" + id.ToString(CultureInfo.InvariantCulture);
        return SendAsync<BudgetPage>(HttpMethod.Get, route, null, 200, bearer,
            response => response.Page == page && response.PageSize == PageSize && response.Items is not null
                && response.Items.Count <= PageSize && response.TotalCount >= response.Items.Count
                && response.Items.All(item => Valid(item) && (month is null || item.PeriodMonth == month)
                    && (categoryId is null || item.CategoryId == categoryId)), ct);
    }

    public Task<BudgetResult<BudgetResponse>> GetAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<BudgetResponse>(HttpMethod.Get, Route(id), null, 200, bearer, response => Valid(response) && response.BudgetId == id, ct);

    public Task<BudgetResult<BudgetResponse>> CreateAsync(CreateBudgetRequest request, string bearer, CancellationToken ct) =>
        SendAsync<BudgetResponse>(HttpMethod.Post, "api/budgets", request, 201, bearer,
            response => Valid(response) && response.CategoryId == request.CategoryId && response.PeriodMonth == request.PeriodMonth, ct);

    public Task<BudgetResult<BudgetResponse>> UpdateAsync(int id, UpdateBudgetRequest request, string bearer, CancellationToken ct) =>
        SendAsync<BudgetResponse>(HttpMethod.Put, Route(id), request, 200, bearer, response => Valid(response) && response.BudgetId == id, ct);

    public Task<BudgetResult<bool>> DeleteAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<bool>(HttpMethod.Delete, Route(id), null, 204, bearer, null, ct);

    private static string Route(int id) => "api/budgets/" + id.ToString(CultureInfo.InvariantCulture);

    internal static bool Valid(BudgetResponse? budget) => budget is
    {
        BudgetId: > 0, CategoryId: > 0, PeriodMonth.Day: 1, LimitAmount: > 0, AlertThresholdPct: >= 1 and <= 100,
        Status: "OK" or "Warning" or "Exceeded", BaseCurrency.Length: 3
    } && !string.IsNullOrWhiteSpace(budget.CategoryName)
        && budget.BaseCurrency.All(c => c is >= 'A' and <= 'Z');

    private async Task<BudgetResult<T>> SendAsync<T>(HttpMethod method, string route, object? body, int expected,
        string bearer, Func<T, bool>? validate, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            if (body is not null) request.Content = JsonContent.Create(body, body.GetType(), options: Json);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 409 or 429) return new(status);
            if (status != expected) return new(503);
            if (expected == 204) return new(status);
            if (response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return value is not null && validate!(value) ? new(status, value) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException or JsonException
            or NotSupportedException or InvalidOperationException or FormatException)
        {
            return new(503);
        }
    }
}
