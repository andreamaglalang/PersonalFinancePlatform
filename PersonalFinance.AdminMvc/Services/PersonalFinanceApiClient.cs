using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.WebUtilities;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Services;

public sealed record PersonalFinanceResult<T>(int Status, T? Value = null, string? Error = null) where T : class;

public sealed class PersonalFinanceApiClient(HttpClient http, BudgetApiClient budgets, ReportsApiClient reports,
    [FromKeyedServices(PersonalSession.Scheme)] AdminSessionStore sessions)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    public Task<PersonalFinanceResult<PersonalPage<PersonalTransaction>>> TransactionsAsync(
        ClaimsPrincipal user, TransactionFilters filters, CancellationToken ct) =>
        SendAsync<PersonalPage<PersonalTransaction>>(user, HttpMethod.Get,
            QueryHelpers.AddQueryString("api/transactions", new Dictionary<string, string?>
            {
                ["page"] = Number(filters.Page), ["pageSize"] = "20", ["type"] = filters.Type,
                ["categoryId"] = filters.CategoryId is int id ? Number(id) : null,
                ["fromDate"] = Date(filters.EffectiveFrom), ["toDate"] = Date(filters.EffectiveTo), ["search"] = filters.Search,
                ["sortBy"] = filters.SortBy, ["sortDirection"] = filters.SortDirection
            }), null, ct, validate: page => ValidPage(page.Items, page.Page, page.PageSize, page.TotalCount, filters.Page, 20)
                && page.Items.All(ValidTransaction));

    public Task<PersonalFinanceResult<PersonalTransaction>> TransactionAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        SendAsync<PersonalTransaction>(user, HttpMethod.Get, "api/transactions/" + Number(id), null, ct,
            validate: row => ValidTransaction(row) && row.TransactionId == id);

    public Task<PersonalFinanceResult<PersonalTransaction>> CreateAsync(ClaimsPrincipal user, Guid clientId,
        TransactionInput input, CancellationToken ct) => SendAsync<PersonalTransaction>(user, HttpMethod.Post, "api/transactions",
            new CreatePersonalTransaction(clientId, input.Type, input.CategoryId, input.Amount, input.CurrencyCode,
                input.TransactionDate, input.Description), ct, allowCreated: true,
            validate: row => ValidTransaction(row) && row.ClientId == clientId);

    public Task<PersonalFinanceResult<PersonalTransaction>> UpdateAsync(ClaimsPrincipal user, int id,
        TransactionInput input, CancellationToken ct) =>
        SendAsync<PersonalTransaction>(user, HttpMethod.Put, "api/transactions/" + Number(id), input, ct,
            validate: row => ValidTransaction(row) && row.TransactionId == id);

    public Task<PersonalFinanceResult<PersonalTransactionDeletion>> DeleteAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        SendAsync<PersonalTransactionDeletion>(user, HttpMethod.Delete, "api/transactions/" + Number(id), null, ct,
            validate: result => result.TransactionId == id && result.Deleted && ValidBudgets(result.AffectedBudgets));

    public Task<PersonalFinanceResult<CategoryPageResponse>> CategoriesAsync(ClaimsPrincipal user, int page, int size,
        CancellationToken ct, string? type = null) => SendAsync<CategoryPageResponse>(user, HttpMethod.Get,
            FormattableString.Invariant($"api/categories?includeInactive=true&page={page}&pageSize={size}") + (type is null ? "" : "&type=" + Uri.EscapeDataString(type)), null, ct,
            validate: result => ValidPage(result.Items, result.Page, result.PageSize, result.TotalCount, page, size)
                && result.Items.All(row => ValidCategory(row) && (type is null || row.Type == type)));

    public Task<PersonalFinanceResult<CategoryResponse>> CategoryAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        SendAsync<CategoryResponse>(user, HttpMethod.Get, "api/categories/" + Number(id), null, ct,
            validate: row => ValidCategory(row) && row.CategoryId == id);

    public Task<PersonalFinanceResult<CategoryResponse>> SaveCategoryAsync(ClaimsPrincipal user, int? id,
        CategoryWriteRequest request, CancellationToken ct) =>
        SendAsync<CategoryResponse>(user, id.HasValue ? HttpMethod.Put : HttpMethod.Post,
            id is int categoryId ? "api/categories/" + Number(categoryId) : "api/categories", request, ct,
            allowCreated: !id.HasValue, validate: row => ValidCategory(row) && !row.IsSystemDefault
                && (!id.HasValue || row.CategoryId == id));

    public async Task<PersonalFinanceResult<IReadOnlyList<CategoryResponse>>> CategoryChoicesAsync(
        ClaimsPrincipal user, CancellationToken ct)
    {
        const int size = 100;
        var categories = new Dictionary<int, CategoryResponse>();
        for (var page = 1; ; page++)
        {
            var result = await CategoriesAsync(user, page, size, ct);
            if (result.Value is not { } data) return new(result.Status, Error: result.Error);
            foreach (var row in data.Items) categories[row.CategoryId] = row;
            if ((long)page * size >= data.TotalCount || data.Items.Count < size)
                return new(200, categories.Values.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToArray());
            if ((long)page * size > int.MaxValue) return new(503);
        }
    }

    public Task<PersonalFinanceResult<PersonalOverview>> OverviewAsync(ClaimsPrincipal user, DateOnly? month, CancellationToken ct,
        TransactionFilters? filters = null) =>
        SendAsync<PersonalOverview>(user, HttpMethod.Get,
            QueryHelpers.AddQueryString("api/reports/overview", new Dictionary<string, string?>
            {
                ["month"] = Date(month), ["type"] = filters?.Type,
                ["categoryId"] = filters?.CategoryId is int id ? Number(id) : null,
                ["fromDate"] = Date(filters?.EffectiveFrom), ["toDate"] = Date(filters?.EffectiveTo),
                ["search"] = filters?.Search
            }),
            null, ct, validate: value => value.Month == month && value.BaseCurrency is { Length: 3 }
                && value.TransactionCount >= 0 && value.Days is not null && value.Months is not null
                && value.ExpenseByCategory is not null);

    public Task<PersonalFinanceResult<PersonalPage<GoalSummary>>> GoalsAsync(ClaimsPrincipal user, int page, CancellationToken ct) =>
        SendAsync<PersonalPage<GoalSummary>>(user, HttpMethod.Get,
            "api/savings-goals?pageSize=20&page=" + Number(page), null, ct,
            validate: value => ValidPage(value.Items, value.Page, value.PageSize, value.TotalCount, page, 20)
                && value.Items.All(item => item is { GoalId: > 0, BaseCurrency.Length: 3 }));

    public Task<PersonalFinanceResult<GoalSummary>> GoalAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        SendAsync<GoalSummary>(user, HttpMethod.Get, "api/savings-goals/" + Number(id), null, ct,
            validate: goal => goal.GoalId == id);

    public Task<PersonalFinanceResult<GoalSummary>> CreateGoalAsync(ClaimsPrincipal user, PersonalGoalForm goal, CancellationToken ct) =>
        SendAsync<GoalSummary>(user, HttpMethod.Post, "api/savings-goals", goal, ct,
            validate: row => row.GoalId > 0, allowCreated: true);

    public Task<PersonalFinanceResult<PersonalPage<PersonalContribution>>> ContributionsAsync(
        ClaimsPrincipal user, int id, int page, CancellationToken ct) =>
        SendAsync<PersonalPage<PersonalContribution>>(user, HttpMethod.Get,
            FormattableString.Invariant($"api/savings-goals/{id}/contributions?pageSize=20&page={page}"),
            null, ct, validate: response => ValidPage(response.Items, response.Page, response.PageSize, response.TotalCount, page, 20)
                && response.Items.All(row => row is not null && row.GoalId == id));

    public Task<PersonalFinanceResult<ContributionSaved>> ContributeAsync(ClaimsPrincipal user, int id,
        ContributionForm entry, CancellationToken ct) =>
        SendAsync<ContributionSaved>(user, HttpMethod.Post, "api/savings-goals/" + Number(id) + "/contributions",
            entry, ct, validate: row => row.Goal?.GoalId == id, allowCreated: true);

    public Task<PersonalFinanceResult<PersonalPage<PersonalBill>>> BillsAsync(ClaimsPrincipal user, int page, CancellationToken ct) =>
        SendAsync<PersonalPage<PersonalBill>>(user, HttpMethod.Get, "api/recurring-bills?page=" + Number(page), null, ct,
            validate: result => ValidPage(result.Items, result.Page, result.PageSize, result.TotalCount, page, 20)
                && result.Items.All(row => row is { BillId: > 0, CurrencyCode.Length: 3 }));

    public Task<PersonalFinanceResult<object>> ChangePasswordAsync(
        ClaimsPrincipal user, PersonalPassword password, CancellationToken ct) =>
        WithSessionAsync<object>(user, async bearer =>
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, "api/auth/password")
            {
                Content = JsonContent.Create(password, options: Json)
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            using var response = await http.SendAsync(request, ct);
            var status = (int)response.StatusCode;
            return new(status is 204 or 400 or 401 or 403 or 409 or 429 ? status : 503);
        }, ct);

    public Task<PersonalFinanceResult<CurrentUserResponse>> SaveDisplayNameAsync(ClaimsPrincipal user, string fullName, CancellationToken ct) =>
        SendAsync<CurrentUserResponse>(user, HttpMethod.Put, "api/auth/profile", new { fullName }, ct,
            validate: profile => ContractValidation.RegularUser(profile)
                && Number(profile.Id) == user.FindFirstValue(ClaimTypes.NameIdentifier));

    public Task<PersonalFinanceResult<PersonalCurrencyQuote>> ConvertAsync(ClaimsPrincipal user, PersonalConversion values, CancellationToken ct) =>
        SendAsync<PersonalCurrencyQuote>(user, HttpMethod.Get,
            QueryHelpers.AddQueryString("api/transactions/conversion", new Dictionary<string, string?>
            {
                ["amount"] = values.Amount!.Value.ToString(CultureInfo.InvariantCulture),
                ["source"] = values.Source, ["target"] = values.Target
            }), null, ct, validate: quote => quote.Source == values.Source && quote.Target == values.Target
                && quote.Amount == values.Amount && quote.Rate > 0 && quote.ConvertedAmount >= 0);

    public Task<PersonalFinanceResult<MonthlyReportResponse>> MonthlyAsync(
        ClaimsPrincipal user, DateOnly month, CancellationToken ct) =>
        WithSessionAsync<MonthlyReportResponse>(user, async bearer =>
        {
            var result = await reports.GetMonthlyAsync(month, bearer, ct);
            return new(result.Status, result.Report);
        }, ct);

    public Task<PersonalFinanceResult<BudgetPage>> BudgetsAsync(
        ClaimsPrincipal user, DateOnly? month, int? categoryId, int page, CancellationToken ct) =>
        BudgetAsync(user, bearer => budgets.ListAsync(month, page, bearer, ct, categoryId), ct);

    public Task<PersonalFinanceResult<BudgetResponse>> BudgetAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        BudgetAsync(user, bearer => budgets.GetAsync(id, bearer, ct), ct);

    public Task<PersonalFinanceResult<BudgetResponse>> CreateBudgetAsync(ClaimsPrincipal user, CreateBudgetRequest request, CancellationToken ct) =>
        BudgetAsync(user, bearer => budgets.CreateAsync(request, bearer, ct), ct);

    public Task<PersonalFinanceResult<BudgetResponse>> UpdateBudgetAsync(
        ClaimsPrincipal user, int id, UpdateBudgetRequest request, CancellationToken ct) =>
        BudgetAsync(user, bearer => budgets.UpdateAsync(id, request, bearer, ct), ct);

    public Task<PersonalFinanceResult<object>> DeleteBudgetAsync(ClaimsPrincipal user, int id, CancellationToken ct) =>
        WithSessionAsync<object>(user, async bearer => new((await budgets.DeleteAsync(id, bearer, ct)).Status), ct);

    private Task<PersonalFinanceResult<T>> BudgetAsync<T>(ClaimsPrincipal user, Func<string, Task<BudgetResult<T>>> operation,
        CancellationToken ct) where T : class =>
        WithSessionAsync<T>(user, async bearer =>
        {
            var result = await operation(bearer);
            return new(result.Status, result.Value);
        }, ct);

    private Task<PersonalFinanceResult<T>> SendAsync<T>(ClaimsPrincipal user, HttpMethod method, string route,
        object? input, CancellationToken ct, Func<T, bool> validate, bool allowCreated = false) where T : class =>
        WithSessionAsync<T>(user, async bearer =>
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            if (input is not null) request.Content = JsonContent.Create(input, options: Json);
            using var response = await http.SendAsync(request, ct);
            var status = (int)response.StatusCode;
            if (!response.IsSuccessStatusCode)
                return new(status is 400 or 401 or 403 or 404 or 409 or 429 ? status : 503,
                    Error: await ValidationMessageAsync(response, ct));
            if ((status != 200 && !(allowCreated && status == 201))
                || response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return value is not null && validate(value) ? new(status, value) : new(503);
        }, ct);

    private async Task<PersonalFinanceResult<T>> WithSessionAsync<T>(ClaimsPrincipal user,
        Func<string, Task<PersonalFinanceResult<T>>> operation, CancellationToken ct) where T : class
    {
        if (user.Identity?.AuthenticationType != PersonalSession.Scheme || !user.IsInRole("User")
            || user.IsInRole("Administrator")) return new(403);
        var session = sessions.Find(user.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null) return new(401);

        await session.Gate.WaitAsync(ct);
        try
        {
            if (!sessions.IsCurrent(session) || session.Tokens.AccessTokenExpiresAtUtc <= DateTime.UtcNow)
                return new(401);
            if (!ContractValidation.RegularUser(session.User)
                || user.FindFirstValue(ClaimTypes.NameIdentifier) != Number(session.User.Id)) return new(403);

            var result = await operation(session.Tokens.AccessToken);
            if (result.Status == 401) sessions.Invalidate(session);
            return result;
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return new(503); }
        catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException or NotSupportedException)
        {
            return new(503);
        }
        finally { session.Gate.Release(); }
    }

    private static async Task<string?> ValidationMessageAsync(HttpResponseMessage response, CancellationToken ct)
    {
        if ((int)response.StatusCode is not (400 or 409 or 503)
            || response.Content.Headers.ContentType?.MediaType != "application/problem+json") return null;
        using var body = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(ct));
        if (body.RootElement.ValueKind != JsonValueKind.Object) return null;
        if (body.RootElement.TryGetProperty("title", out var title) && title.ValueKind == JsonValueKind.String)
        {
            var known = title.GetString() switch
            {
                "Category is unavailable or incompatible with the transaction type." =>
                    "Choose an available category that matches the transaction type.",
                "The currency is not supported by the exchange-rate provider." =>
                    "The exchange-rate provider does not support that currency.",
                "No acceptable exchange rate is available." => "No exchange rate is available for that currency and date.",
                "The converted amount exceeds the supported monetary range." => "The converted amount exceeds the supported limit.",
                "Bill-payment transactions cannot be modified or deleted." => "Bill-linked transactions cannot be edited or deleted here.",
                "A category with the same name and type already exists." => "A category already uses that name and type.",
                "Referenced categories cannot change type. Rename or archive the category instead." =>
                    "This category is in use. Rename or archive it to preserve transaction history.",
                _ => null
            };
            if (known is not null) return known;
        }
        if (!body.RootElement.TryGetProperty("errors", out var errors) || errors.ValueKind != JsonValueKind.Object)
            return null;
        var supported = new[] { "Type", "CategoryId", "Amount", "CurrencyCode", "TransactionDate", "Description", "Name", "ColorHEX" };
        var fields = supported.Where(field => errors.EnumerateObject().Any(x =>
            string.Equals(x.Name.TrimStart('$', '.'), field, StringComparison.OrdinalIgnoreCase)));
        var labels = fields.Select(field => field switch
        {
            "CategoryId" => "Category", "CurrencyCode" => "Currency", "TransactionDate" => "Date",
            "ColorHEX" => "Color", _ => field
        }).ToArray();
        return labels.Length == 0 ? null : "Check these fields: " + string.Join(", ", labels) + ".";
    }

    private static string Number(int value) => value.ToString(CultureInfo.InvariantCulture);
    private static string? Date(DateOnly? value) => value?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    private static bool ValidPage<T>(IReadOnlyList<T>? items, int page, int size, long count, int expectedPage, int expectedSize) =>
        items is not null && page == expectedPage && size == expectedSize && items.Count <= size && count >= items.Count;
    private static bool ValidCategory(CategoryResponse? row) => row is { CategoryId: > 0 }
        && row.Type is "Income" or "Expense" && !string.IsNullOrWhiteSpace(row.Name);
    private static bool ValidTransaction(PersonalTransaction? row) => row is { TransactionId: > 0, CategoryId: > 0, Amount: > 0 }
        && row.ClientId != Guid.Empty && row.Type is "Income" or "Expense" && row.CurrencyCode is { Length: 3 }
        && !string.IsNullOrWhiteSpace(row.CategoryName)
        && (row.AffectedBudgets is null || ValidBudgets(row.AffectedBudgets));
    private static bool ValidBudgets(IReadOnlyList<BudgetResponse>? rows) =>
        rows is not null && rows.All(row => row is not null && BudgetApiClient.Valid(row));
}
