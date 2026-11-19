using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator"), RequestSizeLimit(16 * 1024)]
public sealed class BudgetsController(BudgetApiClient budgets, ICategoryApiClient categories, AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] BudgetIndexViewModel model, CancellationToken ct)
    {
        DateOnly? month = ParseMonth(model.Month);
        if (!ModelState.IsValid || (model.Month is not null && month is null)
            || ((long)model.Page - 1) * BudgetApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid month (YYYY-MM) and page number.";
            return View(model);
        }

        var result = await InSessionAsync(token => budgets.ListAsync(month, model.Page, token, ct), ct);
        if (SecurityResult(result.Status) is { } security) return security;
        if (result.Succeeded) model.Results = result.Value;
        else
        {
            Response.StatusCode = result.Status;
            model.Error = Message(result.Status);
        }
        return View(model);
    }

    [HttpGet]
    public Task<IActionResult> Create(CancellationToken ct) =>
        CreateViewAsync(new() { Month = DateTime.Today.ToString("yyyy-MM", CultureInfo.InvariantCulture) }, ct);

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] BudgetCreateViewModel model, CancellationToken ct)
    {
        var month = ParseMonth(model.Month);
        if (month is null) ModelState.AddModelError(nameof(model.Month), "Choose a valid month (YYYY-MM).");
        if (ModelState.IsValid)
        {
            var request = new CreateBudgetRequest(model.CategoryId!.Value, month!.Value, model.Amount, model.AlertThresholdPct!.Value);
            var result = await InSessionAsync(token => budgets.CreateAsync(request, token, ct), ct);
            if (result.Succeeded) return BudgetSaved("Budget created.");
            if (SecurityResult(result.Status) is { } security) return security;
            SetWriteError(result.Status);
        }
        else Response.StatusCode = 400;
        return await CreateViewAsync(model, ct);
    }

    [HttpGet]
    public async Task<IActionResult> Edit([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var result = await InSessionAsync(token => budgets.GetAsync(id, token, ct), ct);
        if (!result.Succeeded) return FailureView(result.Status);
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
        if (ModelState.IsValid)
        {
            var request = new UpdateBudgetRequest(model.Amount, model.AlertThresholdPct!.Value);
            var result = await InSessionAsync(token => budgets.UpdateAsync(id, request, token, ct), ct);
            if (result.Succeeded) return BudgetSaved("Budget saved.");
            if (SecurityResult(result.Status) is { } security) return security;
            SetWriteError(result.Status);
        }
        else Response.StatusCode = 400;

        var current = await InSessionAsync(token => budgets.GetAsync(id, token, ct), ct);
        if (!current.Succeeded) return FailureView(current.Status);
        ViewData["Budget"] = current.Value;
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var result = await InSessionAsync(token => budgets.GetAsync(id, token, ct), ct);
        return result.Succeeded ? View(result.Value) : FailureView(result.Status);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var result = await InSessionAsync(token => budgets.DeleteAsync(id, token, ct), ct);
        return result.Succeeded ? BudgetSaved("Budget deleted.") : FailureView(result.Status, mutation: true);
    }

    private IActionResult BudgetSaved(string message)
    {
        TempData["BudgetNotice"] = message;
        return RedirectToAction(nameof(Index));
    }

    private async Task<IActionResult> CreateViewAsync(BudgetCreateViewModel model, CancellationToken ct)
    {
        var result = await InSessionAsync(token => LoadExpenseCategoriesAsync(token, ct), ct);
        if (SecurityResult(result.Status) is { } security) return security;
        if (!result.Succeeded)
        {
            if (Response.StatusCode == 200) Response.StatusCode = result.Status;
            ModelState.AddModelError(string.Empty, "Expense categories could not be loaded. Reload the page before creating a budget.");
        }
        else model.Categories = result.Value!;
        return View(nameof(Create), model);
    }

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
                    CategoryFailure.Unauthorized => 401, CategoryFailure.Forbidden => 403,
                    CategoryFailure.RateLimited => 429, _ => 503
                });
            var data = result.Value!;
            eligible.AddRange(data.Items.Where(item => item.IsActive && item.Type == "Expense"));
            if ((long)page * size >= data.TotalCount)
                return new(200, eligible.DistinctBy(item => item.CategoryId).ToList());
        }
        return new(503);
    }

    private async Task<BudgetResult<T>> InSessionAsync<T>(Func<string, Task<BudgetResult<T>>> operation, CancellationToken ct)
    {
        var session = sessions.Find(User.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return new(401);
        }
        BudgetResult<T> result;
        await session.Gate.WaitAsync(ct);
        try
        {
            result = sessions.IsCurrent(session) && User.IsInRole("Administrator")
                && session.User.Roles.Contains("Administrator", StringComparer.Ordinal)
                && User.FindFirstValue(ClaimTypes.NameIdentifier) == session.User.Id.ToString(CultureInfo.InvariantCulture)
                && session.Tokens.AccessTokenExpiresAtUtc > DateTime.UtcNow
                ? await operation(session.Tokens.AccessToken) : new(401);
            if (result.Status == 401) sessions.Invalidate(session);
        }
        finally { session.Gate.Release(); }
        if (result.Status == 401) await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
        return result;
    }

    private static DateOnly? ParseMonth(string? month) =>
        DateOnly.TryParseExact(month + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var value)
            ? value : null;

    private IActionResult? SecurityResult(int status) => status switch
    {
        401 => RedirectToAction("Login", "Account"),
        403 => Forbid(),
        _ => null
    };

    private IActionResult FailureView(int status, bool mutation = false)
    {
        if (SecurityResult(status) is { } security) return security;
        Response.StatusCode = status;
        return View("Error", Message(status, mutation));
    }

    private void SetWriteError(int status)
    {
        Response.StatusCode = status;
        ModelState.AddModelError(string.Empty, Message(status, mutation: true));
    }

    private static string Message(int status, bool mutation = false) => status switch
    {
        400 => "The API rejected the budget details. Check the amount, threshold, month and category.",
        404 => "This budget is no longer available.",
        409 => "The change conflicts with an existing budget, a concurrent change, or related records. Reload the list and review before retrying.",
        429 => "Too many requests. Wait before trying again.",
        _ => mutation
            ? "The API could not confirm the change. Check the budget list before submitting again."
            : "The budget API is unavailable or returned an invalid response. Try again later."
    };
}
