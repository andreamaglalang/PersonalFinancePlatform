using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

/// <summary>
/// Member recurring bills. Same API contracts as the app, authorized with the User policy and scoped to the caller's
/// own session. Paying mints a fresh ClientId; the server keeps the receipt so a replay cannot pay twice.
/// </summary>
[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class BillsController(RecurringBillApiClient bills, ICategoryApiClient categories,
    AdminSessionStore sessions) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] MemberBillIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["BillNotice"] as string;
        model.Error = TempData["BillError"] as string;
        if (model.Page < 1 || (long)(model.Page - 1) * RecurringBillApiClient.PageSize > int.MaxValue)
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid page number.";
            return View(model);
        }

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await bills.ListAsync(true, model.Page, token!, ct);
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
    public async Task<IActionResult> Create(CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var loaded = await LoadExpenseCategoriesAsync(token!, ct);
        if (loaded.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (loaded.Status != 200) ViewData["CategoryError"] = "Expense categories are unavailable. Try again before saving a bill.";
        return View(new MemberBillFormModel { CurrencyCode = "PHP" });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] MemberBillFormModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var loaded = await LoadExpenseCategoriesAsync(token!, ct);
        if (loaded.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!ModelState.IsValid || loaded.Status != 200)
        {
            if (loaded.Status != 200) ViewData["CategoryError"] = "Expense categories are unavailable. The bill was not saved.";
            return View(model);
        }

        var result = await bills.CreateAsync(new(model.Name!.Trim(), model.CategoryId!.Value, model.Value,
            model.CurrencyCode!.ToUpperInvariant(), model.ReminderDaysBefore!.Value, model.IsActive,
            model.Frequency!, model.FirstDueDate!.Value), token!, ct);
        if (result.Succeeded)
        {
            TempData["BillNotice"] = "Bill created.";
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

        var result = await bills.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        var bill = result.Value!;
        ViewData["BillId"] = bill.BillId;
        var loaded = await LoadExpenseCategoriesAsync(token!, ct, bill.CategoryId);
        if (loaded.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (loaded.Status != 200)
        {
            Response.StatusCode = 503;
            ViewData["CategoryError"] = "Expense categories are unavailable. Try again before editing a bill.";
        }
        return View(nameof(Create), new MemberBillFormModel
        {
            Name = bill.Name,
            CategoryId = bill.CategoryId,
            Amount = bill.Amount.ToString("0.00", System.Globalization.CultureInfo.InvariantCulture),
            CurrencyCode = bill.CurrencyCode,
            ReminderDaysBefore = bill.ReminderDaysBefore,
            IsActive = bill.IsActive,
            Frequency = bill.Frequency,
            FirstDueDate = bill.FirstDueDate
        });
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit([FromRoute] int id, [FromForm] MemberBillFormModel model, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        ViewData["BillId"] = id;
        var current = await bills.GetAsync(id, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded) return RedirectToIndex(current.Status, reason: current.Message);
        var loaded = await LoadExpenseCategoriesAsync(token!, ct, current.Value!.CategoryId);
        if (loaded.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!ModelState.IsValid || loaded.Status != 200)
        {
            if (loaded.Status != 200) ViewData["CategoryError"] = "Expense categories are unavailable. The bill was not saved.";
            return View(nameof(Create), model);
        }

        var result = await bills.UpdateAsync(id, new(model.Name!.Trim(), model.CategoryId!.Value, model.Value,
            model.CurrencyCode!.ToUpperInvariant(), model.ReminderDaysBefore!.Value, model.IsActive), token!, ct);
        if (result.Succeeded)
        {
            TempData["BillNotice"] = "Bill saved.";
            return RedirectToAction(nameof(Index));
        }
        return WriteError(result.Status, model, reason: result.Message);
    }

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await bills.GetAsync(id, token!, ct);
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var result = await bills.DeleteAsync(id, token!, ct);
        if (result.Succeeded)
        {
            TempData["BillNotice"] = "Bill deleted.";
            return RedirectToAction(nameof(Index));
        }
        return RedirectToIndex(result.Status, mutation: true, reason: result.Message);
    }

    [HttpGet]
    public async Task<IActionResult> Pay([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");
        var result = await bills.GetAsync(id, token!, ct);
        if (!result.Succeeded) return RedirectToIndex(result.Status, reason: result.Message);
        // The API only accepts payment for an occurrence that is due (Philippine calendar); don't offer an early payment.
        if (result.Value!.NextDueDate > BudgetPlanSummary.PhilippineToday)
        {
            TempData["BillError"] = $"This bill can be paid from its due date, {result.Value.NextDueDate:MMMM d, yyyy}.";
            return RedirectToAction(nameof(Index));
        }
        ViewData["DueDate"] = result.Value.NextDueDate;
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Pay)), ValidateAntiForgeryToken]
    public async Task<IActionResult> PayConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account");

        var current = await bills.GetAsync(id, token!, ct);
        if (current.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account");
        }
        if (!current.Succeeded) return RedirectToIndex(current.Status, reason: current.Message);
        // A fresh ClientId per submission: the server replays the retained receipt instead of paying twice.
        var result = await bills.PayAsync(id, new(Guid.NewGuid(), current.Value!.NextDueDate), token!, ct);
        if (result.Succeeded)
        {
            TempData["BillNotice"] = "Payment recorded.";
            return RedirectToAction(nameof(Index));
        }
        return RedirectToIndex(result.Status, mutation: true, reason: result.Message);
    }

    private IActionResult WriteError<T>(int status, T model, string? reason = null)
    {
        Response.StatusCode = status is 400 or 404 or 409 or 429 ? status : 503;
        ModelState.AddModelError(string.Empty, MemberSessionGate.Message(status, true, reason));
        return View(nameof(Create), model);
    }

    private async Task<(IReadOnlyList<CategoryResponse> Items, int Status)> LoadExpenseCategoriesAsync(
        string bearer, CancellationToken ct, int? preserveCategoryId = null)
    {
        const int pageSize = 100;
        var all = new List<CategoryResponse>();
        for (var page = 1; page <= 100; page++)
        {
            var result = await categories.ListAsync("Expense", page, pageSize, bearer, ct);
            if (!result.Succeeded) return ([], result.Failure switch
            {
                CategoryFailure.Unauthorized => 401,
                CategoryFailure.Forbidden => 403,
                CategoryFailure.BadRequest => 400,
                CategoryFailure.RateLimited => 429,
                _ => 503
            });
            var value = result.Value!;
            all.AddRange(value.Items);
            if (all.Count >= value.TotalCount) break;
        }
        var available = all.Where(category => category.IsActive || category.CategoryId == preserveCategoryId).ToArray();
        ViewData["BillCategories"] = available;
        return (available, 200);
    }

    private IActionResult RedirectToIndex(int status, bool mutation = false, string? reason = null)
    {
        if (status == 401)
        {
            MemberSessionGate.RejectAsync(User, sessions, HttpContext).GetAwaiter().GetResult();
            return RedirectToAction("Login", "Account");
        }
        Response.StatusCode = status is 400 or 404 or 409 or 429 ? status : 503;
        TempData["BillError"] = MemberSessionGate.Message(status, mutation, reason);
        return RedirectToAction(nameof(Index));
    }
}
