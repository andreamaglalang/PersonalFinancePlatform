using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/transactions"), RequestSizeLimit(16 * 1024)]
public sealed class TransactionsController(ITransactionService transactions) : ControllerBase
{
    [HttpPost, ProducesResponseType<TransactionResponse>(201)]
    public async Task<IActionResult> Create([FromBody] CreateTransactionRequest request, CancellationToken ct)
    {
        var result = await transactions.CreateAsync(request, ct);
        if (result.Succeeded && result.StatusCode == 201)
            return CreatedAtAction(nameof(Get), new { id = result.Value!.TransactionId }, result.Value);
        return Respond(result);
    }

    [HttpGet, ProducesResponseType<PagedResponse<TransactionResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] TransactionQuery query, CancellationToken ct) =>
        Respond(await transactions.ListAsync(query, ct));

    [HttpGet("{id:int}"), ProducesResponseType<TransactionResponse>(200)]
    public async Task<IActionResult> Get(int id, CancellationToken ct) => Respond(await transactions.GetAsync(id, ct));

    [HttpPut("{id:int}"), ProducesResponseType<TransactionResponse>(200)]
    public async Task<IActionResult> Update(int id, [FromBody] TransactionWriteRequest request, CancellationToken ct) =>
        Respond(await transactions.UpdateAsync(id, request, ct));

    [HttpDelete("{id:int}"), ProducesResponseType<TransactionDeletionResponse>(200)]
    public async Task<IActionResult> Delete(int id, CancellationToken ct) => Respond(await transactions.DeleteAsync(id, ct));

    private IActionResult Respond<T>(FinanceResult<T> result) =>
        !result.Succeeded ? Problem(statusCode: result.StatusCode, title: result.Error)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}

