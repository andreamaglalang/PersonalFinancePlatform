using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member savings goals. Same API contracts as the app, authorized with the User policy and scoped to the caller's
/// own session. Contributions mint a fresh ClientId; cancellation sends the goal's row version as If-Match.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class SavingsGoalsController(SavingsGoalApiClient goals, AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] MemberSavingsGoalIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["GoalNotice"] as string;
        model.Error = TempData["GoalError"] as string;
        if (model.Page < 1 || (long)(model.Page - 1) * SavingsGoalApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid page number.";
            return View(model);
        }

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await goals.ListAsync(null, model.Page, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (result.Succeeded) model.Results = result.Value!;
        else
        {
            Response.StatusCode = result.Status;
            model.Error = MemberSessionGate.Message(result.Status);
        }
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Details([FromRoute] int id, [FromQuery] int page = 1, CancellationToken ct = default)
    {
        if (id <= 0) return NotFound();
        if (page < 1 || (long)(page - 1) * SavingsGoalApiClient.PageSize > int.MaxValue)
            return BadRequest("Choose a valid contribution page.");
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var goal = await goals.GetAsync(id, token!, ct);
        if (goal.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!goal.Succeeded) return RedirectToIndex(goal.Status, reason: goal.Message);

        var contributions = await goals.ListContributionsAsync(id, page, token!, ct);
        if (contributions.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!contributions.Succeeded || contributions.Value is null)
        {
            Response.StatusCode = contributions.Status is 400 or 404 or 429 ? contributions.Status : 503;
            return View(new MemberSavingsGoalDetailViewModel
            {
                Goal = goal.Value!,
                Contributions = new([], page, SavingsGoalApiClient.PageSize, 0),
                Error = MemberSessionGate.Message(contributions.Status),
                Notice = TempData["GoalNotice"] as string
            });
        }
        return View(new MemberSavingsGoalDetailViewModel
        {
            Goal = goal.Value!, Contributions = contributions.Value,
            Error = TempData["GoalError"] as string, Notice = TempData["GoalNotice"] as string
        });
    }

    [HttpGet]
    public IActionResult Create() => View(new MemberSavingsGoalFormModel());

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] MemberSavingsGoalFormModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        if (!ModelState.IsValid) return View(model);
        var result = await goals.CreateAsync(new(model.Name!.Trim(), model.Amount, model.TargetDate), token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Savings goal created.";
            return RedirectToAction(nameof(Index));
        }
        return WriteError(result.Status, model, reason: result.Message);
    }

    [HttpGet]
    public async Task<IActionResult> Edit([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await goals.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        var goal = result.Value!;
        ViewData["GoalId"] = goal.GoalId;
        return View(nameof(Create), new MemberSavingsGoalFormModel
        {
            Name = goal.Name,
            TargetAmount = goal.TargetAmount.ToString("0.00", System.Globalization.CultureInfo.InvariantCulture),
            TargetDate = goal.TargetDate,
            Version = goal.Version
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit([FromRoute] int id, [FromForm] MemberSavingsGoalFormModel model,
        CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        if (!ModelState.IsValid)
        {
            ViewData["GoalId"] = id;
            return View(nameof(Create), model);
        }
        var current = await goals.GetAsync(id, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded) return RedirectToIndex(current.Status, reason: current.Message);
        if (string.IsNullOrEmpty(model.Version)) return Expired(id);
        // The version this form was opened with, not the current one: a stale form must not overwrite a newer change.
        var result = await goals.UpdateAsync(id, new(model.Name!.Trim(), model.Amount, model.TargetDate),
            model.Version, token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Savings goal saved.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Status is 412 or 428 || result.Status == 409 && result.Message is null)
        {
            // The version changed underneath this page: reload rather than overwrite someone else's edit.
            TempData["GoalError"] = ChangedElsewhere;
            return RedirectToAction(nameof(Index));
        }
        ViewData["GoalId"] = id;
        return WriteError(result.Status, model, nameof(Create), result.Message);
    }

    [HttpGet]
    public async Task<IActionResult> Contribute([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await goals.GetAsync(id, token!, ct);
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        ViewData["Goal"] = result.Value!;
        return View(new MemberContributionFormModel { ContributedOn = BudgetPlanSummary.PhilippineToday, ClientId = Guid.NewGuid() });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Contribute([FromRoute] int id, [FromForm] MemberContributionFormModel model,
        CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        if (!ModelState.IsValid)
        {
            var current = await goals.GetAsync(id, token!, ct);
            if (current.Succeeded) ViewData["Goal"] = current.Value!;
            Response.StatusCode = 400;
            return View(model);
        }
        if (model.ClientId is not { } clientId || clientId == Guid.Empty) return Expired(id);
        // The identity minted when this form opened: resubmitting the same form (double click, retry after a lost
        // response, a second tab with the same page) replays the one contribution instead of adding another.
        var result = await goals.ContributeAsync(id,
            new(clientId, model.Value, model.ContributedOn!.Value, Note(model.Note)), token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Contribution saved.";
            return RedirectToAction(nameof(Index));
        }
        return RedirectToIndex(result.Status, mutation: true, reason: result.Message);
    }

    [HttpGet]
    public async Task<IActionResult> EditContribution([FromRoute] int id, [FromQuery] int contributionId, CancellationToken ct)
    {
        if (id <= 0 || contributionId <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var contribution = await goals.GetContributionAsync(id, contributionId, token!, ct);
        if (contribution.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!contribution.Succeeded || contribution.Value is null) return RedirectToIndex(contribution.Status, reason: contribution.Message);
        var goal = await goals.GetAsync(id, token!, ct);
        if (!goal.Succeeded) return RedirectToIndex(goal.Status, reason: goal.Message);
        ViewData["Goal"] = goal.Value!;
        ViewData["ContributionId"] = contributionId;
        return View(new MemberContributionFormModel
        {
            Amount = contribution.Value.Amount.ToString("0.00", System.Globalization.CultureInfo.InvariantCulture),
            ContributedOn = contribution.Value.ContributedOn,
            Note = contribution.Value.Note,
            Version = contribution.Value.Version
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> EditContribution([FromRoute] int id, [FromQuery] int contributionId,
        [FromForm] MemberContributionFormModel model, CancellationToken ct)
    {
        if (id <= 0 || contributionId <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var current = await goals.GetContributionAsync(id, contributionId, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded || current.Value is null) return RedirectToIndex(current.Status, reason: current.Message);
        var goal = await goals.GetAsync(id, token!, ct);
        if (!goal.Succeeded) return RedirectToIndex(goal.Status, reason: goal.Message);
        ViewData["Goal"] = goal.Value!;
        ViewData["ContributionId"] = contributionId;
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View(model);
        }
        if (string.IsNullOrEmpty(model.Version)) return Expired(id);
        var result = await goals.UpdateContributionAsync(id, contributionId,
            new UpdateGoalContributionRequest(model.Value, model.ContributedOn!.Value, Note(model.Note)),
            model.Version, token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Contribution updated.";
            return RedirectToAction(nameof(Details), new { id });
        }
        TempData["GoalError"] = result.Status == 412 ? ChangedElsewhere : MemberSessionGate.Message(result.Status, true, result.Message);
        return RedirectToAction(nameof(Details), new { id });
    }

    [HttpGet]
    public async Task<IActionResult> DeleteContribution([FromRoute] int id, [FromQuery] int contributionId, CancellationToken ct)
    {
        if (id <= 0 || contributionId <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var contribution = await goals.GetContributionAsync(id, contributionId, token!, ct);
        if (contribution.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!contribution.Succeeded || contribution.Value is null) return RedirectToIndex(contribution.Status, reason: contribution.Message);
        var goal = await goals.GetAsync(id, token!, ct);
        if (!goal.Succeeded) return RedirectToIndex(goal.Status, reason: goal.Message);
        ViewData["Goal"] = goal.Value!;
        return View(contribution.Value);
    }

    [HttpPost, ActionName(nameof(DeleteContribution)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteContributionConfirmed([FromRoute] int id, [FromQuery] int contributionId,
        [FromForm] string? version, CancellationToken ct)
    {
        if (id <= 0 || contributionId <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var current = await goals.GetContributionAsync(id, contributionId, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded || current.Value is null) return RedirectToIndex(current.Status, reason: current.Message);
        if (string.IsNullOrEmpty(version)) return Expired(id);
        var result = await goals.DeleteContributionAsync(id, contributionId, version, token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Contribution deleted.";
            return RedirectToAction(nameof(Details), new { id });
        }
        TempData["GoalError"] = result.Status == 412 ? ChangedElsewhere : MemberSessionGate.Message(result.Status, true, result.Message);
        return RedirectToAction(nameof(Details), new { id });
    }

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await goals.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, [FromForm] string? version, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var current = await goals.GetAsync(id, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded || current.Value is null) return RedirectToIndex(current.Status, reason: current.Message);
        if (string.IsNullOrEmpty(version)) return Expired(id);
        var result = await goals.DeleteAsync(id, version, token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Savings goal deleted.";
            return RedirectToAction(nameof(Index));
        }
        TempData["GoalError"] = result.Status == 412 ? ChangedElsewhere : result.Status == 409
            ? "This goal has protected financial history and cannot be deleted. Cancel it instead."
            : MemberSessionGate.Message(result.Status, true, result.Message);
        return RedirectToAction(nameof(Index));
    }

    [HttpGet]
    public async Task<IActionResult> Cancel([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await goals.GetAsync(id, token!, ct);
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Cancel)), ValidateAntiForgeryToken]
    public async Task<IActionResult> CancelConfirmed([FromRoute] int id, [FromForm] string? version, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var current = await goals.GetAsync(id, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded) return RedirectToIndex(current.Status, reason: current.Message);
        // The row version is the concurrency precondition: a stale page is refused, never applied blind.
        if (string.IsNullOrEmpty(version)) return Expired(id);
        var result = await goals.CancelAsync(id, version, token!, ct);
        if (result.Succeeded)
        {
            TempData["GoalNotice"] = "Savings goal cancelled.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Status is 412 or 428 or 409)
        {
            TempData["GoalError"] = ChangedElsewhere;
            return RedirectToAction(nameof(Index));
        }
        return RedirectToIndex(result.Status, mutation: true, reason: result.Message);
    }

    private static string? Note(string? note)
    {
        var trimmed = note?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed[..Math.Min(trimmed.Length, 200)];
    }

    private IActionResult WriteError<T>(int status, T model, string? viewName = null, string? reason = null)
    {
        Response.StatusCode = status is 400 or 404 or 409 or 429 ? status : 503;
        ModelState.AddModelError(string.Empty, MemberSessionGate.Message(status, true, reason));
        return View(viewName, model);
    }

    private const string ChangedElsewhere =
        "This goal or contribution was updated elsewhere, for example in the OWLI app. Nothing was changed. Review it and try again.";

    /// <summary>A form without its original identity or version cannot be applied safely.</summary>
    private IActionResult Expired(int id)
    {
        TempData["GoalError"] = "This form has expired. Nothing was saved. Open it again and retry.";
        return RedirectToAction(nameof(Details), new { id });
    }

    private IActionResult RedirectToIndex(int status, bool mutation = false, string? reason = null)
    {
        if (status == 401)
        {
            MemberSessionGate.RejectAsync(User, sessions, HttpContext).GetAwaiter().GetResult();
            return RedirectToAction("Login", "Account");
        }
        Response.StatusCode = status is 400 or 404 or 409 or 429 ? status : 503;
        TempData["GoalError"] = MemberSessionGate.Message(status, mutation, reason);
        return RedirectToAction(nameof(Index));
    }
}
