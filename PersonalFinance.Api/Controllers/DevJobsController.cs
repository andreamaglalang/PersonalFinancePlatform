using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Jobs;

namespace PersonalFinance.Api.Controllers;

/// <summary>
/// Development-only manual run of a finance job, for demonstrations. Outside Development the whole /api/dev path is
/// answered 404 by <see cref="Authentication.FinanceTransportMiddleware"/> before authentication runs, and this action
/// also returns 404 as a second guard. Administrator role required. Runs the same job code as the Hangfire schedule
/// (no Hangfire storage needed), so the jobs' own deduplication applies: running twice creates nothing new.
/// Bill reminders are not offered because they need the recurring-bill schema (migration 4).
/// </summary>
[ApiController, Authorize(Roles = ApplicationRoles.Administrator), Route("api/dev/jobs")]
public sealed class DevJobsController(FinanceJobs jobs, IHostEnvironment environment) : ControllerBase
{
    public const string BudgetAlerts = "budget-alerts";
    public const string MonthlySummaries = "monthly-summaries";

    [HttpPost("{job}/run"), ProducesResponseType(200), ProducesResponseType(404)]
    public async Task<IActionResult> Run(string job, CancellationToken ct)
    {
        if (!environment.IsDevelopment()) return NotFound();
        int created;
        switch (job)
        {
            case BudgetAlerts: created = await jobs.SendBudgetAlertsAsync(ct); break;
            case MonthlySummaries: created = await jobs.GenerateMonthlySummariesAsync(ct); break;
            default: return NotFound();
        }
        return Ok(new { job, created });
    }
}
