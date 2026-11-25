using System.ComponentModel.DataAnnotations;
using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

public sealed class CategoryApiClient(HttpClient http) : ICategoryApiClient
{
    public async Task<CategoryResult<CategoryPageResponse>> ListAsync(string? type, int page, int pageSize, string bearer, CancellationToken ct)
    {
        var route = FormattableString.Invariant($"api/categories?IncludeInactive=true&Page={page}&PageSize={pageSize}");
        if (type is not null) route += "&Type=" + Uri.EscapeDataString(type);
        var result = await SendAsync<CategoryPageResponse>(HttpMethod.Get, route, null, bearer, ct);
        if (result.Succeeded && (result.Value!.Page != page || result.Value.PageSize != pageSize))
            return CategoryResult<CategoryPageResponse>.Fail(CategoryFailure.InvalidResponse);
        return result;
    }
    public Task<CategoryResult<CategoryResponse>> GetAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<CategoryResponse>(HttpMethod.Get, "api/categories/" + id.ToString(CultureInfo.InvariantCulture), null, bearer, ct);
    public Task<CategoryResult<CategoryResponse>> CreateAsync(CategoryWriteRequest request, string bearer, CancellationToken ct) =>
        SendAsync<CategoryResponse>(HttpMethod.Post, "api/categories/defaults", request, bearer, ct);
    public Task<CategoryResult<CategoryResponse>> UpdateAsync(int id, CategoryWriteRequest request, string bearer, CancellationToken ct) =>
        SendAsync<CategoryResponse>(HttpMethod.Put, "api/categories/defaults/" + id.ToString(CultureInfo.InvariantCulture), request, bearer, ct);

    public Task<CategoryResult<CategoryResponse>> CreateMemberAsync(CategoryWriteRequest request, string bearer, CancellationToken ct) =>
        SendAsync<CategoryResponse>(HttpMethod.Post, "api/categories", request, bearer, ct);

    public Task<CategoryResult<CategoryResponse>> UpdateMemberAsync(int id, CategoryWriteRequest request, string bearer, CancellationToken ct) =>
        SendAsync<CategoryResponse>(HttpMethod.Put, "api/categories/" + id.ToString(CultureInfo.InvariantCulture), request, bearer, ct);

    public async Task<CategoryResult<bool>> DeleteMemberAsync(int id, string bearer, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Delete, "api/categories/" + id.ToString(CultureInfo.InvariantCulture));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            if (response.StatusCode == System.Net.HttpStatusCode.NoContent)
                return CategoryResult<bool>.Success(true);
            return CategoryResult<bool>.Fail((int)response.StatusCode switch
            {
                400 => CategoryFailure.BadRequest, 401 => CategoryFailure.Unauthorized,
                403 => CategoryFailure.Forbidden, 404 => CategoryFailure.NotFound,
                409 => CategoryFailure.Conflict, 429 => CategoryFailure.RateLimited,
                _ => CategoryFailure.Unavailable
            });
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException)
        {
            return CategoryResult<bool>.Fail(CategoryFailure.Unavailable);
        }
    }

    private async Task<CategoryResult<T>> SendAsync<T>(HttpMethod method, string route, CategoryWriteRequest? body, string bearer, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            if (!response.IsSuccessStatusCode)
            {
                // ProblemDetails and ValidationProblemDetails share status codes. Never echo
                // their arbitrary title/detail/errors strings, HTML, SQL details or trace data.
                return CategoryResult<T>.Fail((int)response.StatusCode switch
                {
                    400 => CategoryFailure.BadRequest, 401 => CategoryFailure.Unauthorized,
                    403 => CategoryFailure.Forbidden, 404 => CategoryFailure.NotFound,
                    409 => CategoryFailure.Conflict, 429 => CategoryFailure.RateLimited,
                    _ => CategoryFailure.Unavailable
                });
            }
            var expected = method == HttpMethod.Post ? 201 : 200;
            if ((int)response.StatusCode != expected || response.Content.Headers.ContentType?.MediaType != "application/json")
                return CategoryResult<T>.Fail(CategoryFailure.InvalidResponse);
            var value = await response.Content.ReadFromJsonAsync<T>(cancellationToken: ct);
            var valid = value switch
            {
                CategoryResponse category => ValidCategory(category),
                CategoryPageResponse page => page.Items is not null && page.Page >= 1 && page.PageSize is >= 1 and <= 100
                    && page.Items.Count <= page.PageSize && page.TotalCount >= page.Items.Count
                    && page.Items.All(ValidCategory) && page.Items.Select(c => c.CategoryId).Distinct().Count() == page.Items.Count,
                _ => false
            };
            return valid ? CategoryResult<T>.Success(value!) : CategoryResult<T>.Fail(CategoryFailure.InvalidResponse);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return CategoryResult<T>.Fail(CategoryFailure.Unavailable);
        }
    }

    private static bool ValidCategory(CategoryResponse? category) => category is { CategoryId: > 0 }
        && !string.IsNullOrWhiteSpace(category.Name) && category.Name.Length <= 50
        && category.Type is "Income" or "Expense"
        && (category.ColorHEX is null || Regex.IsMatch(category.ColorHEX, "^#[0-9A-Fa-f]{6}$", RegexOptions.CultureInvariant));
}
