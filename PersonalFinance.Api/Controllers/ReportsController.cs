using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Api.Controllers;

/// <summary>Financial reports for the signed-in member only (no cross-user or administrator aggregation).</summary>
[ApiController, Authorize, Route("api/reports")]
public sealed class ReportsController(IReportService reports) : ControllerBase
{
    /// <summary>GET api/reports/monthly?month=2026-11-01</summary>
    [HttpGet("monthly"), ProducesResponseType<MonthlyReportResponse>(200)]
    public async Task<IActionResult> Monthly([FromQuery] MonthlyReportQuery query, CancellationToken ct)
    {
        var result = await reports.MonthlyAsync(query, ct);
        return result.Succeeded ? Ok(result.Value) : Problem(statusCode: result.StatusCode, title: result.Error);
    }
}
