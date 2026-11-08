using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/savings-goals"), RequestSizeLimit(16 * 1024)]
public sealed class SavingsGoalsController(ISavingsGoalService goals) : ControllerBase
{
    [HttpPost, ProducesResponseType<SavingsGoalResponse>(201)]
    public async Task<IActionResult> Create([FromBody] CreateSavingsGoalRequest request, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        var result = await goals.CreateAsync(request, ct);
        if (!result.Succeeded) return Respond(result);
        if (SetETag(result.Value!.Version) is { } versionError) return versionError;
        return CreatedAtAction(nameof(Get), new { id = result.Value.GoalId }, result.Value);
    }

    [HttpGet, ProducesResponseType<PagedResponse<SavingsGoalResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] SavingsGoalQuery query, CancellationToken ct)
    {
        if (QueryProblem(nameof(query.Status), nameof(query.Page), nameof(query.PageSize)) is { } queryError) return queryError;
        return Respond(await goals.ListAsync(query, ct));
    }

    [HttpGet("{id:int}"), ProducesResponseType<SavingsGoalResponse>(200)]
    public async Task<IActionResult> Get(int id, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        return RespondVersioned(await goals.GetAsync(id, ct), goal => goal.Version);
    }

    [HttpPut("{id:int}"), ProducesResponseType<SavingsGoalResponse>(200)]
    public async Task<IActionResult> Update(int id, [FromBody] UpdateSavingsGoalRequest request, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        if (ReadIfMatch(out var version) is { } preconditionError) return preconditionError;
        return RespondVersioned(await goals.UpdateAsync(id, request, version, ct), goal => goal.Version);
    }

    [HttpPost("{id:int}/cancel"), ProducesResponseType<SavingsGoalResponse>(200)]
    public async Task<IActionResult> Cancel(int id, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        if (NoBodyProblem() is { } bodyError) return bodyError;
        if (ReadIfMatch(out var version) is { } preconditionError) return preconditionError;
        return RespondVersioned(await goals.CancelAsync(id, version, ct), goal => goal.Version);
    }

    [HttpDelete("{id:int}"), ProducesResponseType(204)]
    public async Task<IActionResult> Delete(int id, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        if (NoBodyProblem() is { } bodyError) return bodyError;
        if (ReadIfMatch(out var version) is { } preconditionError) return preconditionError;
        return Respond(await goals.DeleteAsync(id, version, ct));
    }

    [HttpPost("{id:int}/contributions")]
    [ProducesResponseType<ContributionCreateResponse>(201), ProducesResponseType<ContributionCreateResponse>(200)]
    public async Task<IActionResult> CreateContribution(int id, [FromBody] CreateGoalContributionRequest request, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        var result = await goals.CreateContributionAsync(id, request, ct);
        if (!result.Succeeded) return Respond(result);
        var response = result.Value!;
        // A retained deletion receipt has no current contribution and therefore no contribution ETag.
        // The parent goal's version remains in the body; it is never substituted into this header.
        if (response.CurrentContribution is { } contribution
            && SetETag(contribution.Version) is { } versionError) return versionError;
        return result.StatusCode == 201
            ? CreatedAtAction(nameof(GetContribution), new { id, contributionId = response.Receipt.ContributionId }, response)
            : Respond(result);
    }

    [HttpGet("{id:int}/contributions"), ProducesResponseType<PagedResponse<GoalContributionResponse>>(200)]
    public async Task<IActionResult> ListContributions(int id, [FromQuery] GoalContributionQuery query, CancellationToken ct)
    {
        if (QueryProblem(nameof(query.FromDate), nameof(query.ToDate), nameof(query.Page), nameof(query.PageSize)) is { } queryError)
            return queryError;
        return Respond(await goals.ListContributionsAsync(id, query, ct));
    }

    [HttpGet("{id:int}/contributions/{contributionId:int}"), ProducesResponseType<GoalContributionResponse>(200)]
    public async Task<IActionResult> GetContribution(int id, int contributionId, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        return RespondVersioned(await goals.GetContributionAsync(id, contributionId, ct), contribution => contribution.Version);
    }

    [HttpPut("{id:int}/contributions/{contributionId:int}"), ProducesResponseType<GoalContributionMutationResponse>(200)]
    public async Task<IActionResult> UpdateContribution(int id, int contributionId,
        [FromBody] UpdateGoalContributionRequest request, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        if (ReadIfMatch(out var version) is { } preconditionError) return preconditionError;
        return RespondVersioned(await goals.UpdateContributionAsync(id, contributionId, request, version, ct),
            response => response.Contribution.Version);
    }

    [HttpDelete("{id:int}/contributions/{contributionId:int}"), ProducesResponseType<ContributionDeletionResponse>(200)]
    public async Task<IActionResult> DeleteContribution(int id, int contributionId, CancellationToken ct)
    {
        if (QueryProblem() is { } queryError) return queryError;
        if (NoBodyProblem() is { } bodyError) return bodyError;
        if (ReadIfMatch(out var version) is { } preconditionError) return preconditionError;
        // The deleted contribution has no current ETag. The receipt includes the updated parent goal version.
        return Respond(await goals.DeleteContributionAsync(id, contributionId, version, ct));
    }

    private IActionResult? ReadIfMatch(out string? version)
    {
        version = null;
        if (!Request.Headers.TryGetValue("If-Match", out var values))
            return Problem(statusCode: 428, title: "If-Match is required.");
        if (values.Count != 1 || values[0] is not string raw)
            return InvalidIfMatch();
        // Permit HTTP optional whitespace around the field value, never inside the token.
        var tag = raw.Trim(' ', '\t');
        if (tag.Length != 14 || tag[0] != '"' || tag[^1] != '"') return InvalidIfMatch();
        var candidate = tag[1..^1];
        if (!IsCanonicalVersion(candidate)) return InvalidIfMatch();
        version = candidate;
        return null;
    }

    private IActionResult InvalidIfMatch() => Problem(statusCode: 400,
        title: "If-Match must contain exactly one strong quoted resource version.");

    private IActionResult? SetETag(string? version)
    {
        if (!IsCanonicalVersion(version))
            return Problem(statusCode: 503, title: "A financial record version is unavailable.");
        Response.Headers.ETag = $"\"{version}\"";
        return null;
    }

    private static bool IsCanonicalVersion(string? version)
    {
        if (version is null || version.Length != 12) return false;
        Span<byte> bytes = stackalloc byte[8];
        return Convert.TryFromBase64String(version, bytes, out var written) && written == 8
            && Convert.ToBase64String(bytes) == version;
    }

    private IActionResult? QueryProblem(params string[] allowedNames)
    {
        var allowed = new HashSet<string>(allowedNames, StringComparer.OrdinalIgnoreCase);
        foreach (var parameter in Request.Query)
        {
            if (!allowed.Contains(parameter.Key) || parameter.Value.Count != 1 || string.IsNullOrWhiteSpace(parameter.Value[0]))
                return Problem(statusCode: 400, title: "Unsupported or repeated query parameter.");
        }
        return null;
    }

    private IActionResult? NoBodyProblem() =>
        Request.ContentLength is > 0 || Request.Headers.ContainsKey("Transfer-Encoding")
        || HttpContext.Features.Get<IHttpRequestBodyDetectionFeature>()?.CanHaveBody == true
            ? Problem(statusCode: 400, title: "This operation does not accept a request body.") : null;

    private IActionResult RespondVersioned<T>(FinanceResult<T> result, Func<T, string> version)
    {
        if (!result.Succeeded) return Respond(result);
        if (SetETag(version(result.Value!)) is { } versionError) return versionError;
        return Respond(result);
    }

    private IActionResult Respond<T>(FinanceResult<T> result) =>
        !result.Succeeded ? Problem(statusCode: result.StatusCode, title: result.Error)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}
