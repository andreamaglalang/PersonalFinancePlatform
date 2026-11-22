using System.Globalization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Route("Personal/Reports")]
public sealed class PersonalReportsController(PersonalFinanceApiClient api) : PersonalFinanceController
{
    [HttpGet("")]
    public async Task<IActionResult> Index(string? month, CancellationToken ct)
    {
        if (!PersonalMonth.TryParse(month ?? PersonalMonth.Format(PersonalMonth.Current), out var selected)
            || !PersonalMonth.IsReportMonth(selected))
        {
            Response.StatusCode = 400;
            return View(new PersonalMonthlyView(null, null, "Choose a valid month from 2000 through 9998."));
        }
        var result = await api.MonthlyAsync(User, selected, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        var history = await api.OverviewAsync(User, selected, ct);
        if (await AccessFailure(history.Status) is { } historyDenied) return historyDenied;
        return View(new PersonalMonthlyView(selected, result.Value, result.Value is null ? Error(result) : null,
            Overview: history.Value));
    }
}

[Route("Personal/Budgets")]
public sealed class PersonalBudgetsController(PersonalFinanceApiClient api) : PersonalFinanceController
{
    [HttpGet("")]
    public async Task<IActionResult> Index(string? month, int? categoryId, CancellationToken ct, int page = 1)
    {
        DateOnly? period = PersonalMonth.TryParse(month, out var parsed) ? parsed : null;
        if (!ModelState.IsValid || (month is not null && period is null) || categoryId is <= 0
            || page < 1 || (long)(page - 1) * BudgetApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            return View(new PersonalBudgetList(month, categoryId, page, null, [], "Check the month, category, or page number."));
        }

        var result = await api.BudgetsAsync(User, period, categoryId, page, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        var categories = await api.CategoryChoicesAsync(User, ct);
        if (await AccessFailure(categories.Status) is { } categoriesDenied) return categoriesDenied;
        IReadOnlyList<BudgetResponse>? monthlySummary = null;
        if (period is { } summaryMonth && PersonalMonth.IsReportMonth(summaryMonth) && result.Value is { } budgets)
        {
            if (categoryId is null && page == 1 && budgets.Items.Count == budgets.TotalCount)
            {
                monthlySummary = budgets.Items;
            }
            else
            {
                var report = await api.MonthlyAsync(User, summaryMonth, ct);
                if (await AccessFailure(report.Status) is { } reportDenied) return reportDenied;
                monthlySummary = report.Value?.Budgets;
            }
        }
        return View(new PersonalBudgetList(period is { } selected ? PersonalMonth.Format(selected) : null, categoryId, page,
            result.Value, categories.Value?.Where(x => x.Type == "Expense").ToArray() ?? [],
            result.Value is null ? Error(result) : null, categories.Value is not null, monthlySummary));
    }

    [HttpGet("{id:int:min(1)}")]
    public async Task<IActionResult> Details(int id, CancellationToken ct) =>
        await ShowBudgetAsync(nameof(Details), id, ct);

    [HttpGet("Create")]
    public Task<IActionResult> Create(string? month, CancellationToken ct) =>
        RenderCreateAsync(new BudgetCreateViewModel
        {
            Month = PersonalMonth.Format(PersonalMonth.TryParse(month, out var selected) ? selected : PersonalMonth.Current)
        }, ct);

    [HttpPost("Create"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] BudgetCreateViewModel form, CancellationToken ct)
    {
        if (!PersonalMonth.TryParse(form.Month, out var month))
            ModelState.AddModelError(nameof(form.Month), "Choose a valid month.");
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return await RenderCreateAsync(form, ct);
        }

        var result = await api.CreateBudgetAsync(User,
            new CreateBudgetRequest(form.CategoryId!.Value, month, form.Amount, form.AlertThresholdPct!.Value), ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is { } saved) return Saved("Budget created.", saved.BudgetId);

        var error = Error(result);
        ModelState.AddModelError(string.Empty, result.Status switch
        {
            400 => "Choose an active expense category and check the month, limit, and alert threshold.",
            409 => "A budget may already exist for this category and month. Open the budget list to check.",
            _ => error
        });
        return await RenderCreateAsync(form, ct);
    }

    [HttpGet("{id:int:min(1)}/Edit")]
    public async Task<IActionResult> Edit(int id, CancellationToken ct)
    {
        var result = await api.BudgetAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is not { } budget) return DetailsError(result);
        return RenderEdit(budget, new BudgetEditViewModel
        {
            LimitAmount = budget.LimitAmount.ToString("0.00", CultureInfo.InvariantCulture),
            AlertThresholdPct = budget.AlertThresholdPct
        });
    }

    [HttpPost("{id:int:min(1)}/Edit"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit(int id, [FromForm] BudgetEditViewModel form, CancellationToken ct)
    {
        if (ModelState.IsValid)
        {
            var result = await api.UpdateBudgetAsync(User, id,
                new UpdateBudgetRequest(form.Amount, form.AlertThresholdPct!.Value), ct);
            if (await AccessFailure(result.Status) is { } denied) return denied;
            if (result.Value is { } saved) return Saved("Budget updated.", saved.BudgetId);
            ModelState.AddModelError(string.Empty, Error(result));
        }
        else Response.StatusCode = 400;

        var current = await api.BudgetAsync(User, id, ct);
        if (await AccessFailure(current.Status) is { } rejected) return rejected;
        return current.Value is { } row ? RenderEdit(row, form) : DetailsError(current);
    }

    [HttpGet("{id:int:min(1)}/Delete")]
    public Task<IActionResult> Delete(int id, CancellationToken ct) => ShowBudgetAsync(nameof(Delete), id, ct);

    [HttpPost("{id:int:min(1)}/Delete"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Delete(int id, [FromForm] bool confirmed, CancellationToken ct)
    {
        string error;
        if (confirmed && ModelState.IsValid)
        {
            var result = await api.DeleteBudgetAsync(User, id, ct);
            if (await AccessFailure(result.Status) is { } denied) return denied;
            if (result.Status == 204)
            {
                TempData["PersonalBudgetNotice"] = "Budget deleted.";
                return RedirectToAction(nameof(Index));
            }
            error = Error(result);
        }
        else
        {
            Response.StatusCode = 400;
            error = "Confirm deletion to continue.";
        }

        var current = await api.BudgetAsync(User, id, ct);
        if (await AccessFailure(current.Status) is { } rejected) return rejected;
        return current.Value is { } row ? View(new PersonalBudgetDetails(row, error)) : DetailsError(current);
    }

    private async Task<IActionResult> ShowBudgetAsync(string view, int id, CancellationToken ct)
    {
        var result = await api.BudgetAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        return result.Value is { } row ? View(view, new PersonalBudgetDetails(row)) : DetailsError(result);
    }

    private async Task<IActionResult> RenderCreateAsync(BudgetCreateViewModel form, CancellationToken ct)
    {
        var choices = await api.CategoryChoicesAsync(User, ct);
        if (await AccessFailure(choices.Status) is { } denied) return denied;
        if (choices.Value is { } categories)
            form.Categories = categories.Where(x => x.Type == "Expense" && x.IsActive).ToArray();
        else
        {
            if (Response.StatusCode == 200) Response.StatusCode = choices.Status is 429 ? 429 : 503;
            ModelState.AddModelError(string.Empty, "Expense categories could not be loaded. Reload before creating a budget.");
        }
        return View(nameof(Create), form);
    }

    private ViewResult RenderEdit(BudgetResponse row, BudgetEditViewModel form)
    {
        ViewData["PersonalBudget"] = row;
        return View(nameof(Edit), form);
    }

    private ViewResult DetailsError(PersonalFinanceResult<BudgetResponse> failure) =>
        View(nameof(Details), new PersonalBudgetDetails(null, Error(failure)));

    private IActionResult Saved(string notice, int id)
    {
        TempData["PersonalBudgetNotice"] = notice;
        return RedirectToAction(nameof(Details), new { id });
    }
}

[Route("Personal/Plan")]
public sealed class PersonalPlanController(PersonalFinanceApiClient api) : PersonalFinanceController
{
    [HttpGet("")]
    public IActionResult Index() => RedirectToAction("Index", "PersonalBudgets");

    [HttpGet("Goals")]
    public async Task<IActionResult> Goals(CancellationToken ct, int page = 1)
    {
        if (page < 1 || (long)(page - 1) * 20 > int.MaxValue)
        {
            Response.StatusCode = 400;
            return View(new GoalOverview(null, "Choose a valid page."));
        }
        var result = await api.GoalsAsync(User, page, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is null) return View(new GoalOverview(null, Error(result)));
        return View(new GoalOverview(result.Value, null));
    }

    [HttpGet("Goals/Create")]
    public async Task<IActionResult> CreateGoal(CancellationToken ct)
    {
        var availability = await api.GoalsAsync(User, 1, ct);
        if (await AccessFailure(availability.Status) is { } denied) return denied;
        if (availability.Value is null) return View(nameof(Goals), new GoalOverview(null, Error(availability)));
        return View(new PersonalGoalForm());
    }

    [HttpPost("Goals/Create"), ValidateAntiForgeryToken]
    public async Task<IActionResult> CreateGoal([FromForm] PersonalGoalForm form, CancellationToken ct)
    {
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View(form);
        }
        var result = await api.CreateGoalAsync(User, form, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is not { } saved)
        {
            ModelState.AddModelError(string.Empty, Error(result));
            return View(form);
        }
        TempData["PersonalGoalNotice"] = "Savings goal created.";
        return RedirectToAction(nameof(Goal), new { id = saved.GoalId });
    }

    [HttpGet("Goals/{id:int:min(1)}")]
    public Task<IActionResult> Goal(int id, CancellationToken ct, int page = 1) =>
        RenderGoalAsync(id, page, new ContributionForm
        {
            ClientId = Guid.NewGuid(),
            ContributedOn = DateOnly.FromDateTime(DateTime.Today)
        }, ct);

    [HttpPost("Goals/{id:int:min(1)}/Contributions"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Contribute(int id, [FromForm] ContributionForm form, CancellationToken ct)
    {
        if (form.ClientId == Guid.Empty) ModelState.AddModelError(string.Empty, "Reload the goal before contributing.");
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return await RenderGoalAsync(id, 1, form, ct);
        }
        var result = await api.ContributeAsync(User, id, form, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        if (result.Value is not { } saved)
        {
            ModelState.AddModelError(string.Empty, Error(result));
            return await RenderGoalAsync(id, 1, form, ct);
        }
        TempData["PersonalGoalNotice"] = saved.Deleted ? "This contribution was previously recorded and removed."
            : saved.Replayed ? "This contribution was already recorded." : "Contribution recorded.";
        return RedirectToAction(nameof(Goal), new { id });
    }

    private async Task<IActionResult> RenderGoalAsync(int id, int page, ContributionForm form, CancellationToken ct)
    {
        if (page < 1 || (long)(page - 1) * 20 > int.MaxValue)
        {
            Response.StatusCode = 400;
            return View(nameof(Goals), new GoalOverview(null, "Choose a valid page."));
        }
        var goal = await api.GoalAsync(User, id, ct);
        if (await AccessFailure(goal.Status) is { } denied) return denied;
        if (goal.Value is not { } selected) return View(nameof(Goals), new GoalOverview(null, Error(goal)));
        var contributions = await api.ContributionsAsync(User, id, page, ct);
        if (await AccessFailure(contributions.Status) is { } contributionsDenied) return contributionsDenied;
        return View(nameof(Goal), new PersonalGoalDetails(selected, contributions.Value,
            contributions.Value is null ? Error(contributions) : null, form));
    }

    [HttpGet("Bills")]
    public async Task<IActionResult> Bills(CancellationToken ct, int page = 1)
    {
        if (page < 1 || (long)(page - 1) * 20 > int.MaxValue)
        {
            Response.StatusCode = 400;
            return View(new PersonalBillOverview(null, "Choose a valid page."));
        }

        var result = await api.BillsAsync(User, page, ct);
        if (await AccessFailure(result.Status) is { } denied) return denied;
        return View(new PersonalBillOverview(result.Value, result.Value is null ? Error(result) : null));
    }
}