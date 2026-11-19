using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

/// <summary>
/// Member recurring bills. Paying a bill reuses <see cref="IBillPaymentService"/>: one expense transaction per
/// (bill, due date), ClientId-idempotent, with the bill's next due date advanced atomically.
/// </summary>
[ApiController, Authorize, Route("api/recurring-bills"), RequestSizeLimit(16 * 1024)]
public sealed class RecurringBillsController(IRecurringBillService bills, IBillPaymentService payments) : ControllerBase
{
    [HttpPost, ProducesResponseType<RecurringBillResponse>(201)]
    public async Task<IActionResult> Create([FromBody] CreateRecurringBillRequest request, CancellationToken ct)
    {
        var result = await bills.CreateAsync(request, ct);
        return result.Succeeded ? CreatedAtAction(nameof(Get), new { id = result.Value!.BillId }, result.Value) : Respond(result);
    }

    [HttpGet, ProducesResponseType<PagedResponse<RecurringBillResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] RecurringBillQuery query, CancellationToken ct) => Respond(await bills.ListAsync(query, ct));

    [HttpGet("{id:int}"), ProducesResponseType<RecurringBillResponse>(200)]
    public async Task<IActionResult> Get(int id, CancellationToken ct) => Respond(await bills.GetAsync(id, ct));

    [HttpPut("{id:int}"), ProducesResponseType<RecurringBillResponse>(200)]
    public async Task<IActionResult> Update(int id, [FromBody] UpdateRecurringBillRequest request, CancellationToken ct) =>
        Respond(await bills.UpdateAsync(id, request, ct));

    [HttpDelete("{id:int}"), ProducesResponseType(204)]
    public async Task<IActionResult> Delete(int id, CancellationToken ct) => Respond(await bills.DeleteAsync(id, ct));

    /// <summary>Pays the bill's current due date. 201 on first acceptance, 200 when the same ClientId is replayed.</summary>
    [HttpPost("{id:int}/payments"), ProducesResponseType<BillPaymentResponse>(201), ProducesResponseType<BillPaymentResponse>(200)]
    public async Task<IActionResult> Pay(int id, [FromBody] PayBillRequest request, CancellationToken ct)
    {
        var result = await payments.PayAsync(id, request, ct);
        if (!result.Succeeded) return Respond(result);
        // Same receipt fields as the service contract, minus the owner's internal user ID (as in transaction DTOs).
        var r = result.Value!.Receipt;
        return StatusCode(result.StatusCode, new
        {
            receipt = new { r.ReceiptId, r.ClientId, r.BillId, r.TransactionId, r.DueDate, r.CategoryId, r.Amount, r.CurrencyCode,
                r.FxRate, r.FxRateDate, r.AmountBase, r.BaseCurrency, r.PaidOn, r.CanonicalVersion, r.RecordedAtUtc },
            replayed = result.Value.Replayed,
            affectedBudgets = result.Value.AffectedBudgets,
        });
    }

    private IActionResult Respond<T>(FinanceResult<T> result) =>
        !result.Succeeded ? Problem(statusCode: result.StatusCode, title: result.Error)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}
