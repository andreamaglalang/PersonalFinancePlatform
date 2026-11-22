using System.Security.Claims;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = PersonalSession.Policy)]
[RequestSizeLimit(16 * 1024)]
public abstract class PersonalFinanceController : Controller
{
    protected async Task<IActionResult?> AccessFailure(int status)
    {
        if (status == 403) return Forbid(PersonalSession.Scheme);
        if (status != 401) return null;
        await HttpContext.SignOutAsync(PersonalSession.Scheme);
        return RedirectToAction("Login", "Personal");
    }

    protected string Error<T>(PersonalFinanceResult<T> result) where T : class
    {
        Response.StatusCode = result.Status is 400 or 404 or 409 or 429 ? result.Status : 503;
        return result.Error ?? (result.Status switch
        {
            400 => "The API rejected these values. Check the fields and try again.",
            404 => "This record could not be found.",
            409 => "This change conflicts with an existing record or the record changed. Reload and try again.",
            429 => "Too many requests. Wait a moment and retry.",
            _ => "OWLI could not reach the finance service. Try again shortly."
        });
    }
}

[Route("Personal/Transactions")]
public sealed class PersonalTransactionsController(PersonalFinanceApiClient api, IDataProtectionProvider protection, IMemoryCache notices)
    : PersonalFinanceController
{
    [HttpGet("")]
    public async Task<IActionResult> Index([FromQuery] TransactionFilters filters, CancellationToken ct)
    {
        if (!ModelState.IsValid || !filters.IsValid)
        {
            Response.StatusCode = 400;
            return View(new TransactionHistory(filters, null, [], "Check the filters and date range.", true));
        }
        var rows = await api.TransactionsAsync(User, filters, ct);
        if (await AccessFailure(rows.Status) is { } access) return access;
        var categories = await api.CategoryChoicesAsync(User, ct);
        if (await AccessFailure(categories.Status) is { } categoryAccess) return categoryAccess;
        var overview = await api.OverviewAsync(User, filters.SelectedMonth, ct, filters);
        if (await AccessFailure(overview.Status) is { } overviewAccess) return overviewAccess;
        return View(new TransactionHistory(filters, rows.Value, categories.Value ?? [],
            rows.Value is null ? Error(rows) : null, categories.Value is not null, overview.Value, overview.Value is null));
    }

    [HttpGet("{id:int:min(1)}")]
    public async Task<IActionResult> Details(int id, CancellationToken ct)
    {
        var result = await api.TransactionAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        return View(new TransactionDetails(result.Value, result.Value is null ? Error(result) : null));
    }

    [HttpGet("Create")]
    public Task<IActionResult> Create(string? type, CancellationToken ct) =>
        RenderForm(new PersonalTransactionForm
        {
            Type = type == "Income" ? "Income" : "Expense",
            CurrencyCode = (HttpContext.Items[PersonalSession.ProfileItem] as CurrentUserResponse)?.BaseCurrency ?? "",
            TransactionDate = DateOnly.FromDateTime(DateTime.Today),
            DraftToken = Drafts().Protect(Guid.NewGuid().ToString("D"))
        }, null, ct);

    [HttpPost("Create"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] PersonalTransactionForm form, CancellationToken ct)
    {
        var clientId = ReadDraft(form.DraftToken);
        if (clientId == Guid.Empty)
            ModelState.AddModelError(string.Empty, "This form could not be verified. Open a new transaction form.");
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return await RenderForm(form, null, ct);
        }

        var result = await api.CreateAsync(User, clientId, form.ToInput(), ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is not { } row)
        {
            ModelState.AddModelError(string.Empty, Error(result));
            return await RenderForm(form, null, ct);
        }
        Remember(result.Status == 200 ? "This transaction was already saved. The original entry was kept." : "Transaction saved.",
            row.AffectedBudgets);
        return RedirectToAction(nameof(Details), new { id = row.TransactionId });
    }

    [HttpGet("{id:int:min(1)}/Edit")]
    public async Task<IActionResult> Edit(int id, CancellationToken ct)
    {
        var result = await api.TransactionAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is not { } row) return View(nameof(Details), new TransactionDetails(null, Error(result)));
        if (row.BillLinked) return Protected(row);
        return await RenderForm(new PersonalTransactionForm
        {
            Id = id, Type = row.Type, CategoryId = row.CategoryId, Amount = row.Amount, CurrencyCode = row.CurrencyCode,
            TransactionDate = row.TransactionDate, Description = row.Description
        }, row.CategoryId, ct);
    }

    [HttpPost("{id:int:min(1)}/Edit"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit(int id, [FromForm] PersonalTransactionForm form, CancellationToken ct)
    {
        var existing = await api.TransactionAsync(User, id, ct);
        if (await AccessFailure(existing.Status) is { } access) return access;
        if (existing.Value is not { } current) return View(nameof(Details), new TransactionDetails(null, Error(existing)));
        if (current.BillLinked) return Protected(current);
        form.Id = id;
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return await RenderForm(form, current.CategoryId, ct);
        }
        var result = await api.UpdateAsync(User, id, form.ToInput(), ct);
        if (await AccessFailure(result.Status) is { } rejected) return rejected;
        if (result.Value is not { } row)
        {
            ModelState.AddModelError(string.Empty, Error(result));
            return await RenderForm(form, current.CategoryId, ct);
        }
        Remember("Transaction updated.", row.AffectedBudgets);
        return RedirectToAction(nameof(Details), new { id });
    }

    [HttpGet("{id:int:min(1)}/Delete")]
    public async Task<IActionResult> Delete(int id, CancellationToken ct)
    {
        var result = await api.TransactionAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is not { } row) return View(nameof(Details), new TransactionDetails(null, Error(result)));
        return row.BillLinked ? Protected(row) : View(new TransactionDetails(row));
    }

    [HttpPost("{id:int:min(1)}/Delete"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Delete(int id, [FromForm] bool confirmed, CancellationToken ct)
    {
        var existing = await api.TransactionAsync(User, id, ct);
        if (await AccessFailure(existing.Status) is { } access) return access;
        if (existing.Value is not { } row) return View(nameof(Details), new TransactionDetails(null, Error(existing)));
        if (row.BillLinked) return Protected(row);
        if (!confirmed || !ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View(new TransactionDetails(row, "Confirm deletion to continue."));
        }

        var result = await api.DeleteAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } rejected) return rejected;
        if (result.Value is not { } deletion) return View(new TransactionDetails(row, Error(result)));
        Remember("Transaction deleted.", deletion.AffectedBudgets);
        return RedirectToAction(nameof(Index));
    }

    private async Task<IActionResult> RenderForm(PersonalTransactionForm form, int? keptCategoryId, CancellationToken ct)
    {
        var choices = await api.CategoryChoicesAsync(User, ct);
        if (await AccessFailure(choices.Status) is { } access) return access;
        form.CategoriesAvailable = choices.Value is not null;
        form.Categories = choices.Value?.Where(c => c.IsActive || c.CategoryId == keptCategoryId).ToArray() ?? [];
        if (!form.CategoriesAvailable) ModelState.AddModelError(string.Empty, "Categories are unavailable. Reload before submitting.");
        return View("Form", form);
    }

    private ViewResult Protected(PersonalTransaction row)
    {
        Response.StatusCode = 409;
        return View(nameof(Details), new TransactionDetails(row, "Bill-linked transactions cannot be edited or deleted here."));
    }

    private IDataProtector Drafts() => protection.CreateProtector("OWLI.Personal.TransactionDraft.v1",
        User.FindFirstValue(ClaimTypes.NameIdentifier)!);

    private Guid ReadDraft(string? token)
    {
        if (string.IsNullOrEmpty(token) || token.Length > 512) return Guid.Empty;
        try { return Guid.TryParseExact(Drafts().Unprotect(token), "D", out var id) ? id : Guid.Empty; }
        catch (System.Security.Cryptography.CryptographicException) { return Guid.Empty; }
    }

    private void Remember(string message, IReadOnlyList<BudgetResponse>? budgets)
    {
        TempData["PersonalFinanceNotice"] = message;
        if (budgets is not { Count: > 0 } || User.FindFirstValue(AdminSessionStore.SessionClaim) is not { } sessionId) return;
        var id = "budget:" + Guid.NewGuid().ToString("N");
        notices.Set(id, new PersonalBudgetNotice(sessionId, budgets.Select(budget =>
            $"{budget.CategoryName} · {budget.PeriodMonth:MMM yyyy} · {budget.SpentAmount:N2} {budget.BaseCurrency} spent · {budget.RemainingAmount:N2} {budget.BaseCurrency} remaining ({budget.Status})").ToArray()),
            TimeSpan.FromMinutes(2));
        TempData["AffectedBudgetNoticeId"] = id;
    }
}

[Route("Personal/Categories")]
public sealed class PersonalCategoriesController(PersonalFinanceApiClient api) : PersonalFinanceController
{
    [HttpGet("")]
    public async Task<IActionResult> Index(CancellationToken ct, int page = 1, string type = "Expense")
    {
        if (!ModelState.IsValid || type is not ("Expense" or "Income") || page < 1 || (long)(page - 1) * 30 > int.MaxValue)
        {
            Response.StatusCode = 400;
            return View(new PersonalCategoryList(null, "Choose a valid category type and page number."));
        }
        var result = await api.CategoriesAsync(User, page, 30, ct, type);
        if (await AccessFailure(result.Status) is { } access) return access;
        return View(new PersonalCategoryList(result.Value, result.Value is null ? Error(result) : null, type));
    }

    [HttpGet("Create")]
    public IActionResult Create(string? type) => View("Form", new PersonalCategoryForm
    {
        Active = true, Type = type == "Income" ? "Income" : "Expense"
    });

    [HttpPost("Create"), ValidateAntiForgeryToken]
    public Task<IActionResult> Create([FromForm] PersonalCategoryForm form, CancellationToken ct) =>
        Save(null, form, true, ct);

    [HttpGet("{id:int:min(1)}/Edit")]
    public async Task<IActionResult> Edit(int id, CancellationToken ct)
    {
        var result = await api.CategoryAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is not { } row) return View(nameof(Index), new PersonalCategoryList(null, Error(result)));
        if (row.IsSystemDefault) return Forbid(PersonalSession.Scheme);
        return View("Form", new PersonalCategoryForm
        {
            Id = id, Name = row.Name, Type = row.Type, ColorHEX = row.ColorHEX, Active = row.IsActive
        });
    }

    [HttpPost("{id:int:min(1)}/Edit"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit(int id, [FromForm] PersonalCategoryForm form, CancellationToken ct)
    {
        var result = await api.CategoryAsync(User, id, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is not { } row) return View(nameof(Index), new PersonalCategoryList(null, Error(result)));
        if (row.IsSystemDefault) return Forbid(PersonalSession.Scheme);
        return await Save(id, form, row.IsActive, ct);
    }

    [HttpPost("{id:int:min(1)}/Archive"), ValidateAntiForgeryToken]
    public async Task<IActionResult> Archive(int id, CancellationToken ct)
    {
        var existing = await api.CategoryAsync(User, id, ct);
        if (await AccessFailure(existing.Status) is { } access) return access;
        if (existing.Value is not { } row) return View(nameof(Index), new PersonalCategoryList(null, Error(existing)));
        if (row.IsSystemDefault) return Forbid(PersonalSession.Scheme);
        if (row.IsActive)
        {
            var result = await api.SaveCategoryAsync(User, id, new CategoryWriteRequest
            {
                Name = row.Name, Type = row.Type, ColorHEX = row.ColorHEX, IsActive = false
            }, ct);
            if (await AccessFailure(result.Status) is { } rejected) return rejected;
            if (result.Value is null) return View(nameof(Index), new PersonalCategoryList(null, Error(result)));
        }
        TempData["PersonalFinanceNotice"] = "Category archived. Existing transactions keep their category.";
        return RedirectToAction(nameof(Index), new { type = row.Type });
    }

    private async Task<IActionResult> Save(int? id, PersonalCategoryForm form, bool active, CancellationToken ct)
    {
        form.Id = id;
        form.Active = active;
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View("Form", form);
        }
        var result = await api.SaveCategoryAsync(User, id, new CategoryWriteRequest
        {
            Name = form.Name, Type = form.Type, ColorHEX = form.ColorHEX, IsActive = id.HasValue ? null : true
        }, ct);
        if (await AccessFailure(result.Status) is { } access) return access;
        if (result.Value is null)
        {
            ModelState.AddModelError(string.Empty, Error(result));
            return View("Form", form);
        }
        TempData["PersonalFinanceNotice"] = id.HasValue ? "Category updated." : "Category created.";
        return RedirectToAction(nameof(Index), new { type = result.Value.Type });
    }
}
