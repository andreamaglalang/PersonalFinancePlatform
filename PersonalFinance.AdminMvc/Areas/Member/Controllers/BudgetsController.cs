using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member budgets. Same API contracts and validation as the administrator surface, but authorized with the User
/// policy and scoped to the caller's own session through <see cref="MemberSessionGate"/> — never an Administrator
/// role check. Every write posts a fresh antiforgery token through the form tag helper.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class BudgetsController(BudgetApiClient budgets, ICategoryApiClient categories,
    AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] MemberBudgetIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["BudgetNotice"] as string;
        model.Error = TempData["BudgetError"] as string;
        model.Month ??= BudgetPlanSummary.PhilippineToday.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var month = ParseMonth(model.Month);
        if (model.Page < 1 || (model.Month is not null && month is null)
            || (long)(model.Page - 1) * BudgetApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid month (YYYY-MM) and page number.";
            return View(model);
        }

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await budgets.ListAsync(month, model.Page, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (result.Succeeded)
        {
            model.Results = result.Value!;
            // Aggregate every API page; a partial result must never look like a complete financial total.
            var complete = new List<BudgetResponse>(result.Value!.Items);
            var consistent = true;
            for (var page = 1; (long)(page - 1) * BudgetApiClient.PageSize < result.Value.TotalCount; page++)
            {
                if (page == model.Page) continue;
                var next = await budgets.ListAsync(month, page, token!, ct);
                if (next.Status == 401)
                {
                    await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                    return RedirectToAction("Login", "Account", new { area = "" });
                }
                if (!next.Succeeded || next.Value!.TotalCount != result.Value.TotalCount)
                {
                    consistent = false;
                    model.Error = "The complete budget summary could not be loaded. Refresh to try again.";
                    break;
                }
                complete.AddRange(next.Value.Items);
            }
            if (consistent && complete.Count == result.Value.TotalCount && complete.DistinctBy(b => b.BudgetId).Count() == complete.Count)
                model.Summary = BudgetPlanSummary.Calculate(complete, month!.Value, BudgetPlanSummary.PhilippineToday);
            else model.Error = "The complete budget summary could not be verified. Refresh to try again.";
        }
        else
        {
            Response.StatusCode = result.Status;
            model.Error = MemberSessionGate.Message(result.Status);
        }
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Create(CancellationToken ct, string? month = null)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var draft = new BudgetCreateViewModel { Month = (ParseMonth(month) ?? BudgetPlanSummary.PhilippineToday).ToString("yyyy-MM", CultureInfo.InvariantCulture) };
        return await CreateViewAsync(draft, token!, ct);
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] BudgetCreateViewModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var month = ParseMonth(model.Month);
        if (month is null) ModelState.AddModelError(nameof(model.Month), "Choose a valid month (YYYY-MM).");
        if (ModelState.IsValid)
        {
            var request = new CreateBudgetRequest(model.CategoryId!.Value, month!.Value, model.Amount,
                model.AlertThresholdPct!.Value);
            var result = await budgets.CreateAsync(request, token!, ct);
            if (result.Succeeded)
            {
                TempData["BudgetNotice"] = "Budget created.";
                return RedirectToAction(nameof(Index), new { Month = model.Month });
            }
            if (result.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account");
            }
            Response.StatusCode = result.Status is 400 or 404 or 409 or 429 ? result.Status : 503;
            ModelState.AddModelError(string.Empty, MemberSessionGate.Message(result.Status, true, result.Message));
        }
        else Response.StatusCode = 400;
        return await CreateViewAsync(model, token!, ct);
    }

    [HttpGet]
    public async Task<IActionResult> Edit([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await budgets.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!result.Succeeded)
        {
            TempData["BudgetError"] = MemberSessionGate.Message(result.Status);
            return RedirectToAction(nameof(Index));
        }
        var budget = result.Value!;
        ViewData["Budget"] = budget;
        return View(new BudgetEditViewModel
        {
            LimitAmount = budget.LimitAmount.ToString("0.00", CultureInfo.InvariantCulture),
            AlertThresholdPct = budget.AlertThresholdPct
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit([FromRoute] int id, [FromForm] BudgetEditViewModel model, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        if (ModelState.IsValid)
        {
            var request = new UpdateBudgetRequest(model.Amount, model.AlertThresholdPct!.Value);
            var result = await budgets.UpdateAsync(id, request, token!, ct);
            if (result.Succeeded)
            {
                TempData["BudgetNotice"] = "Budget saved.";
                return RedirectToAction(nameof(Index));
            }
            if (result.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account");
            }
            // Redirect rather than re-render so a retried POST cannot apply the change twice.
            TempData["BudgetError"] = MemberSessionGate.Message(result.Status, true, result.Message);
            return RedirectToAction(nameof(Index));
        }

        Response.StatusCode = 400;
        var current = await budgets.GetAsync(id, token!, ct);
        if (current.Succeeded) ViewData["Budget"] = current.Value;
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await budgets.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!result.Succeeded)
        {
            TempData["BudgetError"] = MemberSessionGate.Message(result.Status);
            return RedirectToAction(nameof(Index));
        }
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await budgets.DeleteAsync(id, token!, ct);
        if (result.Succeeded)
        {
            TempData["BudgetNotice"] = "Budget deleted.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        TempData["BudgetError"] = MemberSessionGate.Message(result.Status, true, result.Message);
        return RedirectToAction(nameof(Index));
    }

    private async Task<IActionResult> CreateViewAsync(BudgetCreateViewModel model, string bearer, CancellationToken ct)
    {
        var loaded = await LoadExpenseCategoriesAsync(bearer, ct);
        if (loaded.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (loaded.Succeeded) model.Categories = loaded.Value!;
        else ModelState.AddModelError(string.Empty,
            "Expense categories could not be loaded. Reload the page before creating a budget.");
        return View(nameof(Create), model);
    }

    /// <summary>Pages until the API says the list is complete; never assumes one page holds every category.</summary>
    private async Task<BudgetResult<List<CategoryResponse>>> LoadExpenseCategoriesAsync(string bearer, CancellationToken ct)
    {
        const int size = 100;
        var eligible = new List<CategoryResponse>();
        for (var page = 1; (long)(page - 1) * size <= int.MaxValue; page++)
        {
            var result = await categories.ListAsync("Expense", page, size, bearer, ct);
            if (!result.Succeeded)
                return new(result.Failure switch
                {
                    CategoryFailure.Unauthorized => 401,
                    CategoryFailure.Forbidden => 403,
                    CategoryFailure.RateLimited => 429,
                    _ => 503
                });
            var data = result.Value!;
            eligible.AddRange(data.Items.Where(item => item.IsActive && item.Type == "Expense"));
            if ((long)page * size >= data.TotalCount)
                return new(200, eligible.DistinctBy(item => item.CategoryId).ToList());
        }
        return new(503);
    }

    internal static DateOnly? ParseMonth(string? month) =>
        DateOnly.TryParseExact(month + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var value) ? value : null;
}
