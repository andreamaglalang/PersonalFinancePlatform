using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace PersonalFinance.AdminMvc.Services;

// Mirrors PersonalFinance.Infrastructure.Finance.Contracts.RecurringBillContracts exactly.
public sealed record RecurringBillResponse(int BillId, string Name, int CategoryId, string CategoryName,
    decimal Amount, string CurrencyCode, string Frequency, DateOnly FirstDueDate, DateOnly NextDueDate,
    int ReminderDaysBefore, bool IsActive);

public sealed record RecurringBillPage(IReadOnlyList<RecurringBillResponse> Items, int Page, int PageSize,
    long TotalCount);

public sealed record CreateRecurringBillRequest(string Name, int CategoryId, decimal Amount, string CurrencyCode,
    int ReminderDaysBefore, bool IsActive, string Frequency, DateOnly FirstDueDate);

public sealed record UpdateRecurringBillRequest(string Name, int CategoryId, decimal Amount, string CurrencyCode,
    int ReminderDaysBefore, bool IsActive);

public sealed record PayBillRequest(Guid ClientId, DateOnly DueDate);

// Mirrors the controller's public payment projection: the internal UserId is deliberately omitted.
public sealed record BillPaymentReceiptResponse(long ReceiptId, Guid ClientId, int BillId, int TransactionId,
    DateOnly DueDate, int CategoryId, decimal Amount, string CurrencyCode, decimal FxRate,
    DateOnly FxRateDate, decimal AmountBase, string BaseCurrency, DateOnly PaidOn,
    short CanonicalVersion, DateTime RecordedAtUtc);

public sealed record BillPaymentResponse(BillPaymentReceiptResponse Receipt, bool Replayed,
    IReadOnlyList<BudgetResponse>? AffectedBudgets = null);

public sealed record RecurringBillResult<T>(int Status, T? Value = default)
{
    public bool Succeeded => Status is 200 or 201 or 204;
}

/// <summary>api/recurring-bills. Paying mints a fresh ClientId so a replay cannot pay the same due date twice.</summary>
public sealed class RecurringBillApiClient(HttpClient http)
{
    public const int PageSize = 20;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        RespectRequiredConstructorParameters = true
    };

    private static readonly HashSet<int> Created = [201];
    private static readonly HashSet<int> Ok = [200];
    private static readonly HashSet<int> CreatedOrReplayed = [201, 200];
    private static readonly HashSet<int> NoContent = [204];

    public Task<RecurringBillResult<RecurringBillPage>> ListAsync(bool includeInactive, int page, string bearer,
        CancellationToken ct)
    {
        var route = FormattableString.Invariant(
            $"api/recurring-bills?IncludeInactive={includeInactive.ToString().ToLowerInvariant()}&Page={page}&PageSize={PageSize}");
        return SendAsync<RecurringBillPage>(HttpMethod.Get, route, null, Ok, bearer, ct, response =>
            response.Page == page && response.PageSize == PageSize && response.Items is not null
            && response.Items.Count <= PageSize && response.TotalCount >= response.Items.Count
            && response.Items.All(Valid));
    }

    public Task<RecurringBillResult<RecurringBillResponse>> GetAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<RecurringBillResponse>(HttpMethod.Get, Route(id), null, Ok, bearer, ct,
            response => Valid(response) && response.BillId == id);

    public Task<RecurringBillResult<RecurringBillResponse>> CreateAsync(CreateRecurringBillRequest request,
        string bearer, CancellationToken ct) =>
        SendAsync<RecurringBillResponse>(HttpMethod.Post, "api/recurring-bills", request, Created, bearer, ct,
            response => Valid(response) && response.Name == request.Name && response.CategoryId == request.CategoryId
                && response.Amount == request.Amount && response.CurrencyCode == request.CurrencyCode
                && response.Frequency == request.Frequency && response.FirstDueDate == request.FirstDueDate);

    public Task<RecurringBillResult<RecurringBillResponse>> UpdateAsync(int id, UpdateRecurringBillRequest request,
        string bearer, CancellationToken ct) =>
        SendAsync<RecurringBillResponse>(HttpMethod.Put, Route(id), request, Ok, bearer, ct,
            response => Valid(response) && response.BillId == id && response.Name == request.Name
                && response.Amount == request.Amount);

    public Task<RecurringBillResult<RecurringBillResponse>> DeleteAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<RecurringBillResponse>(HttpMethod.Delete, Route(id), null, NoContent, bearer, ct, null);

    /// <summary>201 paid, 200 replayed from a retained receipt: both mean the due date is settled.</summary>
    public Task<RecurringBillResult<BillPaymentResponse>> PayAsync(int id, PayBillRequest request, string bearer,
        CancellationToken ct) =>
        SendAsync<BillPaymentResponse>(HttpMethod.Post, Route(id) + "/payments", request, CreatedOrReplayed,
            bearer, ct, response => response.Receipt is
            {
                ReceiptId: > 0, TransactionId: > 0, CategoryId: > 0, Amount: > 0,
                FxRate: > 0, AmountBase: >= 0, CanonicalVersion: 1,
                CurrencyCode.Length: 3, BaseCurrency.Length: 3
            } receipt
            && receipt.BillId == id && receipt.ClientId == request.ClientId && receipt.DueDate == request.DueDate
            && receipt.DueDate <= receipt.PaidOn && receipt.FxRateDate <= receipt.PaidOn
            && receipt.CurrencyCode.All(c => c is >= 'A' and <= 'Z')
            && receipt.BaseCurrency.All(c => c is >= 'A' and <= 'Z')
            && (response.AffectedBudgets is null || response.AffectedBudgets.All(BudgetApiClient.Valid)));

    private static string Route(int id) => "api/recurring-bills/" + id.ToString(CultureInfo.InvariantCulture);

    internal static bool Valid(RecurringBillResponse? bill) => bill is
    {
        BillId: > 0, CategoryId: > 0, Amount: > 0, ReminderDaysBefore: >= 0 and <= 30,
        Frequency: "Weekly" or "Monthly" or "Quarterly" or "Yearly", CurrencyCode.Length: 3
    } && !string.IsNullOrWhiteSpace(bill.Name) && !string.IsNullOrWhiteSpace(bill.CategoryName)
        && bill.CurrencyCode.All(c => c is >= 'A' and <= 'Z')
        && bill.NextDueDate >= bill.FirstDueDate;

    private async Task<RecurringBillResult<T>> SendAsync<T>(HttpMethod method, string route, object? body,
        HashSet<int> ok, string bearer, CancellationToken ct, Func<T, bool>? validate)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            if (body is not null) request.Content = JsonContent.Create(body, body.GetType(), options: Json);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 409 or 429) return new(status);
            if (!ok.Contains(status)) return new(503);
            if (status == 204) return new(status);
            if (response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return value is not null && (validate is null || validate(value)) ? new(status, value) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return new(503);
        }
    }
}
