using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator"), RequestSizeLimit(16 * 1024)]
public sealed class CategoriesController(ICategoryManagementService categories) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] CategoryIndexViewModel query, CancellationToken ct)
    {
        // Query input is never used as a backend URL; only supported, bounded fields are sent.
        if ((long)(query.Page - 1) * query.PageSize > int.MaxValue) ModelState.AddModelError("Page", "API page is out of range.");
        if (!ModelState.IsValid)
        {
            query.Error = "Choose Income or Expense, an API page of at least 1, and a page size from 1 to 100.";
            Response.StatusCode = 400;
            return View(query);
        }
        var result = await categories.ListAsync(query, ct);
        if (result.Succeeded) return View(result.Value);
        if (SecurityResult(result.Failure) is { } security) return security;
        query.Error = Message(result.Failure);
        Response.StatusCode = Status(result.Failure);
        return View(query);
    }

    [HttpGet]
    public IActionResult Create() => View(new CategoryCreateViewModel());

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] CategoryCreateViewModel model, CancellationToken ct)
    {
        if (!ModelState.IsValid) { Response.StatusCode = 400; return View(model); }
        var result = await categories.CreateAsync(model, ct);
        if (result.Succeeded) return RedirectToAction(nameof(Edit), new { id = result.Value!.CategoryId });
        if (SecurityResult(result.Failure) is { } security) return security;
        ModelState.AddModelError(string.Empty, Message(result.Failure, mutation: true));
        Response.StatusCode = Status(result.Failure);
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Edit(int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var result = await categories.GetEditAsync(id, ct);
        if (result.Succeeded) return View(result.Value);
        if (SecurityResult(result.Failure) is { } security) return security;
        Response.StatusCode = Status(result.Failure);
        return View("Error", Message(result.Failure));
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit(int id, [FromForm] CategoryEditViewModel model, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        string? uncertainUpdateWarning = null;
        if (ModelState.IsValid)
        {
            var result = await categories.UpdateAsync(id, model, ct);
            if (result.Succeeded) return RedirectToAction(nameof(Edit), new { id });
            if (SecurityResult(result.Failure) is { } security) return security;
            if (result.Failure is CategoryFailure.Unavailable or CategoryFailure.InvalidResponse)
                uncertainUpdateWarning = "The update may have succeeded. Reload and check the category before another attempt; no automatic retry was made.";
            ModelState.AddModelError(string.Empty, uncertainUpdateWarning ?? Message(result.Failure, mutation: true));
            Response.StatusCode = Status(result.Failure);
        }
        else Response.StatusCode = 400;
        // Rehydrate display-only Type from the API, never a posted value. Keep the original
        // protected snapshot: a stale form must be explicitly reloaded, not silently rebased.
        var current = await categories.GetEditAsync(id, ct);
        if (!current.Succeeded)
        {
            // Authentication/authorization failures retain their existing safe handling.
            if (current.Failure is CategoryFailure.Unauthorized or CategoryFailure.Forbidden)
                return SecurityResult(current.Failure)!;
            if (uncertainUpdateWarning is null && SecurityResult(current.Failure) is { } security) return security;
            Response.StatusCode = Status(current.Failure);
            var readError = Message(current.Failure);
            return View("Error", uncertainUpdateWarning is null ? readError : uncertainUpdateWarning + " " + readError);
        }
        model.CurrentType = current.Value!.CurrentType;
        return View(model);
    }

    private IActionResult? SecurityResult(CategoryFailure failure) => failure switch
    {
        CategoryFailure.Unauthorized => RedirectToAction("Login", "Account"),
        CategoryFailure.Forbidden => Forbid(),
        CategoryFailure.NotFound => NotFound(),
        _ => null
    };
    private static int Status(CategoryFailure failure) => failure switch
    {
        CategoryFailure.BadRequest or CategoryFailure.ConfirmationRequired => 400,
        CategoryFailure.Conflict or CategoryFailure.Stale => 409,
        CategoryFailure.RateLimited => 429,
        CategoryFailure.NotFound => 404,
        _ => 503
    };
    private static string Message(CategoryFailure failure, bool mutation = false) => failure switch
    {
        CategoryFailure.BadRequest => "The API rejected the category details. Check the name, type and color, then reload if needed.",
        CategoryFailure.Conflict => "The change conflicts with an existing category or related records. A category with this name and type may already exist, including an inactive category. Reload and review before trying again.",
        CategoryFailure.Stale => "This form has expired or the category changed since it was opened. Reload the category before editing again; your changes were not submitted.",
        CategoryFailure.ConfirmationRequired => "Confirm that deactivating this shared category affects all users before saving.",
        CategoryFailure.RateLimited => "Too many requests. Wait before trying again. No automatic retry was made.",
        CategoryFailure.NotFound => "The shared default category was not found or is not available for administrator management.",
        _ => mutation
            ? "The category service could not confirm the outcome. The change may have reached the API. Reload and check the category before submitting again; no automatic retry was made."
            : "The category service is unavailable or returned an invalid response. Categories could not be loaded. Please try again later."
    };
}
