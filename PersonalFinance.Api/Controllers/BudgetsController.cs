using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/budgets"), RequestSizeLimit(16 * 1024)]
public sealed class BudgetsController(IBudgetService budgets) : ControllerBase
{
    [HttpPost, ProducesResponseType<BudgetResponse>(201)]
    public async Task<IActionResult> Create([FromBody] CreateBudgetRequest request, CancellationToken ct)
    {
        var result = await budgets.CreateAsync(request, ct);
        return result.Succeeded ? CreatedAtAction(nameof(Get), new { id = result.Value!.BudgetId }, result.Value) : Respond(result);
    }

    [HttpGet, ProducesResponseType<PagedResponse<BudgetResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] BudgetQuery query, CancellationToken ct) => Respond(await budgets.ListAsync(query, ct));

    [HttpGet("{id:int}"), ProducesResponseType<BudgetResponse>(200)]
    public async Task<IActionResult> Get(int id, CancellationToken ct) => Respond(await budgets.GetAsync(id, ct));

    [HttpPut("{id:int}"), ProducesResponseType<BudgetResponse>(200)]
    public async Task<IActionResult> Update(int id, [FromBody] UpdateBudgetRequest request, CancellationToken ct) =>
        Respond(await budgets.UpdateAsync(id, request, ct));

    [HttpDelete("{id:int}"), ProducesResponseType(204)]
    public async Task<IActionResult> Delete(int id, CancellationToken ct) => Respond(await budgets.DeleteAsync(id, ct));

    private IActionResult Respond<T>(FinanceResult<T> result) =>
        !result.Succeeded ? Problem(statusCode: result.StatusCode, title: result.Error)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}