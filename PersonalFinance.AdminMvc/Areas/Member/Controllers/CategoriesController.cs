using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class CategoriesController(ICategoryApiClient categories, AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] MemberCategoryIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["MemberCategoryNotice"] as string;
        model.Error = TempData["MemberCategoryError"] as string;
        if (model.Page < 1 || (long)(model.Page - 1) * model.PageSize > int.MaxValue
            || (model.Type is not null and not "Expense" and not "Income"))
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid category type and page.";
            return View(model);
        }
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var all = new List<CategoryResponse>();
        long? total = null;
        for (var page = 1; page <= 100; page++)
        {
            var result = await categories.ListAsync(model.Type, page, 100, token!, ct);
            if (result.Failure == CategoryFailure.Unauthorized)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account", new { area = "" });
            }
            if (!result.Succeeded)
            {
                Response.StatusCode = Status(result.Failure);
                model.Error = Message(result.Failure);
                return View(model);
            }
            total ??= result.Value!.TotalCount;
            all.AddRange(result.Value!.Items);
            if (result.Value.TotalCount != total || all.Count > total || all.Select(x => x.CategoryId).Distinct().Count() != all.Count
                || (all.Count < total && result.Value.Items.Count == 0))
            {
                Response.StatusCode = 502;
                model.Error = "The category list changed while loading. Refresh to try again.";
                return View(model);
            }
            if (all.Count == total)
            {
                model.Items = all;
                model.TotalCount = all.Count;
                model.Page = 1;
                return View(model);
            }
        }
        Response.StatusCode = 502;
        model.Error = "The full category list could not be loaded. Try again.";
        return View(model);
    }

    [HttpGet]
    public IActionResult Create() => View(new MemberCategoryFormViewModel());

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] MemberCategoryFormViewModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        if (!ModelState.IsValid) { Response.StatusCode = 400; return View(model); }
        var result = await categories.CreateMemberAsync(Write(model), token!, ct);
        if (result.Succeeded)
        {
            TempData["MemberCategoryNotice"] = "Personal category created.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        Response.StatusCode = Status(result.Failure);
        model.Error = result.Message ?? Message(result.Failure, true);
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Edit([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var result = await categories.GetAsync(id, token!, ct);
        if (result.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!result.Succeeded) return CategoryErrorRedirect(result.Failure);
        if (result.Value!.IsSystemDefault) return Forbid();
        ViewData["CategoryId"] = id;
        return View("Create", new MemberCategoryFormViewModel
        {
            IsEdit = true, Name = result.Value.Name, Type = result.Value.Type,
            ColorHEX = result.Value.ColorHEX, IsActive = result.Value.IsActive
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit([FromRoute] int id, [FromForm] MemberCategoryFormViewModel model, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var current = await categories.GetAsync(id, token!, ct);
        if (current.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!current.Succeeded) return CategoryErrorRedirect(current.Failure);
        if (current.Value!.IsSystemDefault) return Forbid();
        model.IsEdit = true;
        ViewData["CategoryId"] = id;
        if (!ModelState.IsValid) { Response.StatusCode = 400; return View("Create", model); }
        var result = await categories.UpdateMemberAsync(id, Write(model), token!, ct);
        if (result.Succeeded)
        {
            TempData["MemberCategoryNotice"] = model.IsActive ? "Personal category saved." : "Personal category deactivated.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        Response.StatusCode = Status(result.Failure);
        model.Error = result.Message ?? Message(result.Failure, true);
        return View("Create", model);
    }

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var result = await categories.GetAsync(id, token!, ct);
        if (result.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!result.Succeeded) return CategoryErrorRedirect(result.Failure);
        if (result.Value!.IsSystemDefault) return Forbid();
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var current = await categories.GetAsync(id, token!, ct);
        if (current.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!current.Succeeded) return CategoryErrorRedirect(current.Failure);
        if (current.Value!.IsSystemDefault) return Forbid();
        var result = await categories.DeleteMemberAsync(id, token!, ct);
        if (result.Succeeded)
        {
            TempData["MemberCategoryNotice"] = "Personal category deleted.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        TempData["MemberCategoryError"] = result.Message ?? (result.Failure == CategoryFailure.Conflict
            ? "This category is referenced by financial records and cannot be deleted. Deactivate it instead."
            : Message(result.Failure, true));
        return RedirectToAction(nameof(Index));
    }

    private static CategoryWriteRequest Write(MemberCategoryFormViewModel model) => new()
    {
        Name = model.Name.Trim(), Type = model.Type, ColorHEX = model.ColorHEX, IsActive = model.IsActive
    };

    private IActionResult CategoryErrorRedirect(CategoryFailure failure)
    {
        if (failure == CategoryFailure.Forbidden) return Forbid();
        if (failure == CategoryFailure.NotFound) return NotFound();
        TempData["MemberCategoryError"] = Message(failure);
        return RedirectToAction(nameof(Index));
    }

    private static int Status(CategoryFailure failure) => failure switch
    {
        CategoryFailure.BadRequest => 400, CategoryFailure.Conflict or CategoryFailure.Stale => 409,
        CategoryFailure.Forbidden => 403, CategoryFailure.NotFound => 404, CategoryFailure.RateLimited => 429, _ => 503
    };

    private static string Message(CategoryFailure failure, bool mutation = false) => failure switch
    {
        CategoryFailure.BadRequest => "Check the category name, type and color.",
        CategoryFailure.Conflict => "This change conflicts with another category or with records that use it. Reload and try again.",
        CategoryFailure.RateLimited => "Too many category requests. Wait before trying again.",
        _ => mutation ? "The API could not confirm the change. Reload the categories before trying again; no automatic retry was made."
            : "Categories could not be loaded. Try again later."
    };
}
