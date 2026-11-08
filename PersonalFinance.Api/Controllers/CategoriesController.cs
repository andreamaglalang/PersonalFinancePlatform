using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize, Route("api/categories"), RequestSizeLimit(16 * 1024)]
public sealed class CategoriesController(ICategoryService categories) : ControllerBase
{
    [HttpGet, ProducesResponseType<PagedResponse<CategoryResponse>>(200)]
    public async Task<IActionResult> List([FromQuery] CategoryQuery query, CancellationToken ct) =>
        Respond(await categories.ListAsync(query, ct));

    [HttpGet("{id:int}"), ProducesResponseType<CategoryResponse>(200)]
    public async Task<IActionResult> Get(int id, CancellationToken ct) => Respond(await categories.GetAsync(id, ct));

    [HttpPost, ProducesResponseType<CategoryResponse>(201)]
    public async Task<IActionResult> Create([FromBody] CategoryWriteRequest request, CancellationToken ct) =>
        RespondCreated(await categories.CreateAsync(request, false, ct));

    [HttpPut("{id:int}"), ProducesResponseType<CategoryResponse>(200)]
    public async Task<IActionResult> Update(int id, [FromBody] CategoryWriteRequest request, CancellationToken ct) =>
        Respond(await categories.UpdateAsync(id, request, false, ct));

    [HttpDelete("{id:int}"), ProducesResponseType(204)]
    public async Task<IActionResult> Delete(int id, CancellationToken ct) => Respond(await categories.DeleteAsync(id, false, ct));

    [HttpPost("defaults"), Authorize(Roles = ApplicationRoles.Administrator), ProducesResponseType<CategoryResponse>(201)]
    public async Task<IActionResult> CreateDefault([FromBody] CategoryWriteRequest request, CancellationToken ct) =>
        RespondCreated(await categories.CreateAsync(request, true, ct));

    [HttpPut("defaults/{id:int}"), Authorize(Roles = ApplicationRoles.Administrator), ProducesResponseType<CategoryResponse>(200)]
    public async Task<IActionResult> UpdateDefault(int id, [FromBody] CategoryWriteRequest request, CancellationToken ct) =>
        Respond(await categories.UpdateAsync(id, request, true, ct));

    [HttpDelete("defaults/{id:int}"), Authorize(Roles = ApplicationRoles.Administrator), ProducesResponseType(204)]
    public async Task<IActionResult> DeleteDefault(int id, CancellationToken ct) => Respond(await categories.DeleteAsync(id, true, ct));

    private IActionResult RespondCreated(FinanceResult<CategoryResponse> result) =>
        result.Succeeded ? CreatedAtAction(nameof(Get), new { id = result.Value!.CategoryId }, result.Value) : Respond(result);

    private IActionResult Respond<T>(FinanceResult<T> result) =>
        !result.Succeeded ? Problem(statusCode: result.StatusCode, title: result.Error)
        : result.StatusCode == 204 ? NoContent() : StatusCode(result.StatusCode, result.Value);
}

