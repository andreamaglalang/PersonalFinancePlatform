using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.AdminMvc.Services;

// Mirrors PersonalFinance.Infrastructure.Finance.Contracts.SavingsGoalContracts exactly. No extra properties:
// RespectRequiredConstructorParameters would reject a body the API does not define.
public sealed record SavingsGoalResponse(int GoalId, string Name, decimal TargetAmount, DateOnly? TargetDate,
    string Status, decimal SavedAmount, decimal RemainingAmount, decimal SurplusAmount,
    decimal ProgressPercentage, string BaseCurrency, string Version);

public sealed record GoalContributionResponse(int ContributionId, int GoalId, decimal Amount,
    DateOnly ContributedOn, string? Note, string BaseCurrency, string Version);

public sealed record GoalContributionPage(IReadOnlyList<GoalContributionResponse> Items, int Page, int PageSize, long TotalCount);
public sealed record GoalContributionMutationResponse(GoalContributionResponse Contribution, SavingsGoalResponse Goal);
public sealed record ContributionDeletionResponse(int ContributionId, bool Deleted, SavingsGoalResponse Goal);
public sealed record UpdateGoalContributionRequest(decimal Amount, DateOnly ContributedOn, string? Note);

public sealed record GoalContributionReceipt(long RequestId, Guid ClientId, int GoalId, int ContributionId,
    decimal OriginalAmount, DateOnly OriginalContributedOn, string? OriginalNote, short CanonicalVersion,
    DateTime RecordedAtUtc, DateTime? DeletedAtUtc, string BaseCurrency);

public sealed record ContributionCreateResponse(GoalContributionReceipt Receipt, bool Replayed, bool Deleted,
    GoalContributionResponse? CurrentContribution, SavingsGoalResponse Goal);

public sealed record SavingsGoalPage(IReadOnlyList<SavingsGoalResponse> Items, int Page, int PageSize, long TotalCount);

public sealed record CreateSavingsGoalRequest(string Name, decimal TargetAmount, DateOnly? TargetDate);

public sealed record UpdateSavingsGoalRequest(string Name, decimal TargetAmount, DateOnly? TargetDate);

public sealed record CreateContributionRequest(Guid ClientId, decimal Amount, DateOnly ContributedOn, string? Note);

public sealed record SavingsGoalResult<T>(int Status, T? Value = default)
{
    public bool Succeeded => Status is 200 or 201 or 204;
}

/// <summary>api/savings-goals. Cancellation needs the goal's row version as a strong If-Match tag.</summary>
public sealed class SavingsGoalApiClient(HttpClient http)
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

    public Task<SavingsGoalResult<SavingsGoalPage>> ListAsync(string? status, int page, string bearer, CancellationToken ct)
    {
        var route = FormattableString.Invariant($"api/savings-goals?Page={page}&PageSize={PageSize}");
        if (!string.IsNullOrWhiteSpace(status)) route += "&Status=" + Uri.EscapeDataString(status);
        return SendAsync<SavingsGoalPage>(HttpMethod.Get, route, null, Ok, bearer, ct, response =>
            response.Page == page && response.PageSize == PageSize && response.Items is not null
            && response.Items.Count <= PageSize && response.TotalCount >= response.Items.Count
            && response.Items.All(Valid) && (status is null || response.Items.All(g => g.Status == status)));
    }

    public Task<SavingsGoalResult<SavingsGoalResponse>> GetAsync(int id, string bearer, CancellationToken ct) =>
        SendAsync<SavingsGoalResponse>(HttpMethod.Get, Route(id), null, Ok, bearer, ct,
            response => Valid(response) && response.GoalId == id);

    public Task<SavingsGoalResult<SavingsGoalResponse>> CreateAsync(CreateSavingsGoalRequest request, string bearer,
        CancellationToken ct) =>
        SendAsync<SavingsGoalResponse>(HttpMethod.Post, "api/savings-goals", request, Created, bearer, ct,
            response => Valid(response) && response.Name == request.Name
                && response.TargetAmount == request.TargetAmount && response.TargetDate == request.TargetDate);

    public Task<SavingsGoalResult<SavingsGoalResponse>> UpdateAsync(int id, UpdateSavingsGoalRequest request,
        string version, string bearer, CancellationToken ct) =>
        SendAsync<SavingsGoalResponse>(HttpMethod.Put, Route(id), request, Ok, bearer, ct,
            response => Valid(response) && response.GoalId == id, version);

    public Task<SavingsGoalResult<SavingsGoalResponse>> CancelAsync(int id, string version, string bearer,
        CancellationToken ct) =>
        SendAsync<SavingsGoalResponse>(HttpMethod.Post, Route(id) + "/cancel", null, Ok, bearer, ct,
            response => Valid(response) && response.GoalId == id, version);

    public Task<SavingsGoalResult<bool>> DeleteAsync(int id, string version, string bearer, CancellationToken ct) =>
        SendAsync<bool>(HttpMethod.Delete, Route(id), null, NoContent, bearer, ct, _ => true, version);

    public Task<SavingsGoalResult<GoalContributionPage>> ListContributionsAsync(int id, int page, string bearer,
        CancellationToken ct)
    {
        var route = FormattableString.Invariant($"{Route(id)}/contributions?Page={page}&PageSize={PageSize}");
        return SendAsync<GoalContributionPage>(HttpMethod.Get, route, null, Ok, bearer, ct, response =>
            response.Page == page && response.PageSize == PageSize && response.Items is not null
            && response.Items.Count <= PageSize && response.TotalCount >= response.Items.Count
            && response.Items.All(row => ValidContribution(row) && row.GoalId == id));
    }

    public Task<SavingsGoalResult<GoalContributionResponse>> GetContributionAsync(int id, int contributionId,
        string bearer, CancellationToken ct) =>
        SendAsync<GoalContributionResponse>(HttpMethod.Get,
            $"{Route(id)}/contributions/{contributionId.ToString(CultureInfo.InvariantCulture)}", null, Ok,
            bearer, ct, response => ValidContribution(response) && response.GoalId == id
                && response.ContributionId == contributionId);

    public Task<SavingsGoalResult<GoalContributionMutationResponse>> UpdateContributionAsync(int id, int contributionId,
        UpdateGoalContributionRequest request, string version, string bearer, CancellationToken ct) =>
        SendAsync<GoalContributionMutationResponse>(HttpMethod.Put,
            $"{Route(id)}/contributions/{contributionId.ToString(CultureInfo.InvariantCulture)}", request, Ok,
            bearer, ct, response => ValidContribution(response.Contribution) && response.Contribution.GoalId == id
                && response.Contribution.ContributionId == contributionId && Valid(response.Goal)
                && response.Goal.GoalId == id, version);

    public Task<SavingsGoalResult<ContributionDeletionResponse>> DeleteContributionAsync(int id, int contributionId,
        string version, string bearer, CancellationToken ct) =>
        SendAsync<ContributionDeletionResponse>(HttpMethod.Delete,
            $"{Route(id)}/contributions/{contributionId.ToString(CultureInfo.InvariantCulture)}", null, Ok,
            bearer, ct, response => response.Deleted && response.ContributionId == contributionId
                && Valid(response.Goal) && response.Goal.GoalId == id, version);

    /// <summary>The server keeps the receipt: a repeated ClientId replays instead of contributing twice.</summary>
    public Task<SavingsGoalResult<ContributionCreateResponse>> ContributeAsync(int id, CreateContributionRequest request,
        string bearer, CancellationToken ct) =>
        SendAsync<ContributionCreateResponse>(HttpMethod.Post, Route(id) + "/contributions", request,
            CreatedOrReplayed, bearer, ct,
            response => response.Goal is { } goal && Valid(goal) && goal.GoalId == id
                && response.Receipt.GoalId == id && response.Receipt.ClientId == request.ClientId
                && response.Receipt.OriginalAmount == request.Amount);

    private static string Route(int id) => "api/savings-goals/" + id.ToString(CultureInfo.InvariantCulture);

    internal static bool Valid(SavingsGoalResponse? goal) => goal is
    {
        GoalId: > 0, TargetAmount: > 0, SavedAmount: >= 0, ProgressPercentage: >= 0,
        Status: "Active" or "Completed" or "Cancelled", BaseCurrency.Length: 3, Version.Length: 12
    } && !string.IsNullOrWhiteSpace(goal.Name)
        && goal.BaseCurrency.All(c => c is >= 'A' and <= 'Z')
        // Surplus and remaining are the server's own figures; a contradiction is not trusted.
        && goal.RemainingAmount == goal.TargetAmount - goal.SavedAmount
        && goal.SurplusAmount == Math.Max(0m, goal.SavedAmount - goal.TargetAmount);

    private static bool ValidContribution(GoalContributionResponse? row) => row is
    {
        ContributionId: > 0, GoalId: > 0, Amount: > 0, BaseCurrency.Length: 3, Version.Length: 12
    } && row.BaseCurrency.All(c => c is >= 'A' and <= 'Z') && (row.Note?.Length ?? 0) <= 200
        && CanonicalVersion(row.Version);

    private static bool CanonicalVersion(string version)
    {
        Span<byte> bytes = stackalloc byte[8];
        return Convert.TryFromBase64String(version, bytes, out var written) && written == 8
            && Convert.ToBase64String(bytes) == version;
    }

    private async Task<SavingsGoalResult<T>> SendAsync<T>(HttpMethod method, string route, object? body,
        HashSet<int> ok, string bearer, CancellationToken ct, Func<T, bool> validate, string? version = null)
    {
        try
        {
            using var request = new HttpRequestMessage(method, route);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            // A strong ETag is the API's concurrency precondition; a stale one must fail, never overwrite.
            if (version is { Length: 12 }) request.Headers.TryAddWithoutValidation("If-Match", $"\"{version}\"");
            if (body is not null) request.Content = JsonContent.Create(body, body.GetType(), options: Json);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct);
            var status = (int)response.StatusCode;
            if (status is 400 or 401 or 403 or 404 or 409 or 428 or 429) return new(status);
            if (!ok.Contains(status)) return new(503);
            if (status == 204) return new(status);
            if (response.Content.Headers.ContentType?.MediaType != "application/json") return new(503);
            var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
            return value is not null && validate(value) ? new(status, value) : new(503);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException
            or JsonException or NotSupportedException or InvalidOperationException or FormatException)
        {
            return new(503);
        }
    }
}
