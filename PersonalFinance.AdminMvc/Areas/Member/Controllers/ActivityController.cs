using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Areas.Member.Controllers;

[Area(MemberArea.Name)]
[Authorize(Policy = AuthorizationConfiguration.UserPolicy)]
[RequestSizeLimit(16 * 1024)]
public sealed class ActivityController(TransactionApiClient transactions, ICategoryApiClient categories,
    AdminSessionStore sessions, IDataProtectionProvider protection, ExchangeRateApiClient exchangeRates) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] ActivityIndexViewModel model, CancellationToken ct)
    {
        model.Notice = TempData["ActivityNotice"] as string;
        model.Error = TempData["ActivityError"] as string;
        // Currency display follows the member's saved preference; the account currency is always fixed server-side.
        var userId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        var currencyPreferences = MemberCurrencyPreferences.Read(HttpContext, protection, userId);
        model.BaseCurrency = MemberSessionGate.BaseCurrency(User, sessions);
        model.ShowOriginalAmounts = currencyPreferences.ShowOriginalAmounts;
        if (string.IsNullOrWhiteSpace(model.Period)) model.Period = "Month";
        if (model.FromDate is not null || model.ToDate is not null)
        {
            model.Period = "Range";
            model.Month = null;
            model.Day = null;
        }
        if (model.Period == "Month" && model.Month is null)
            model.Month = DateOnly.FromDateTime(DateTime.UtcNow.AddHours(8)).ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var month = ParseMonth(model.Month);
        var currentMonth = new DateOnly(BudgetPlanSummary.PhilippineToday.Year, BudgetPlanSummary.PhilippineToday.Month, 1);
        if (model.Period == "Month" && month > currentMonth) { month = currentMonth; model.Month = currentMonth.ToString("yyyy-MM", CultureInfo.InvariantCulture); }
        if (model.Period is not ("Month" or "AllTime" or "Range")
            || (model.Period == "Month" && (month is null || month.Value.Year < 2000))
            || (model.Period == "AllTime" && (model.Month is not null || model.AmountOrder is not null))
            || (model.Period == "Range" && (model.Month is not null || model.FromDate is null || model.ToDate is null
                || model.FromDate > model.ToDate || model.Day is not null))
            || (model.Period != "Range" && (model.FromDate is not null || model.ToDate is not null))
            || !ModelState.IsValid
            || model.Page < 1
            || (long)(model.Page - 1) * TransactionApiClient.PageSize > int.MaxValue
            || model.Search?.Length > 100
            || (model.Type is not null and not ("Income" or "Expense"))
            || model.CategoryId is <= 0
            || (model.AmountOrder is not null and not ("Largest" or "Smallest"))
            || (model.AmountOrder is not null && model.Period == "AllTime")
            || (model.Day is not null && (month is null || model.Day.Value.Year != month.Value.Year || model.Day.Value.Month != month.Value.Month)))
        {
            Response.StatusCode = 400;
            model.Error = "Choose a valid reporting period and filter values.";
            return View(model);
        }

        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        var fromDate = model.Period == "Range" ? model.FromDate : month;
        var toDate = model.Period == "Range" ? model.ToDate : MonthEnd(month);
        var result = await transactions.ListAllAsync(token!, ct, model.Type, model.CategoryId, fromDate, toDate);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (result.Succeeded)
        {
            // The API supplies stable, owner-scoped pages. Aggregate and search only after every page has
            // been verified, so the overview never uses the current display page as its data source.
            var allRows = result.Value!;
            var firstPage = ActivityProjection.Build(allRows, model.Type, model.CategoryId,
                fromDate, toDate, model.Search, model.Day, 1, TransactionApiClient.PageSize, model.AmountOrder);
            model.TotalCount = firstPage.TotalCount;
            model.SummaryCount = firstPage.SummaryCount;
            var lastPage = Math.Max(1, (int)Math.Ceiling(model.TotalCount / (double)TransactionApiClient.PageSize));
            model.Page = Math.Min(model.Page, lastPage);
            var projection = model.Page == 1 ? firstPage : ActivityProjection.Build(allRows, model.Type,
                model.CategoryId, fromDate, toDate, model.Search, model.Day, model.Page,
                TransactionApiClient.PageSize, model.AmountOrder);
            model.Items = projection.Items;
            model.IncomeTotal = projection.IncomeTotal;
            model.ExpenseTotal = projection.ExpenseTotal;
            model.DailyVolume = projection.DailyVolume;
            model.DailyNet = projection.DailyNet;
            model.DailyCount = projection.DailyCount;
            model.DataLoaded = true;
        }
        else
        {
            Response.StatusCode = result.Status;
            model.Error = MemberSessionGate.Message(result.Status);
        }

        var filter = await LoadCategoriesAsync(token!, ct);
        if (filter.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        model.Categories = filter.Succeeded ? filter.Value!.OrderBy(item => item.Name).ToList() : [];
        if (!filter.Succeeded) model.CategoryError = "Category filters are temporarily unavailable.";
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Create([FromQuery] string? type, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        var account = MemberSessionGate.BaseCurrency(User, sessions);
        var preferences = MemberCurrencyPreferences.Read(HttpContext, protection,
            User.FindFirstValue(ClaimTypes.NameIdentifier));
        var draft = new MemberTransactionDraftViewModel
        {
            Type = type is "Income" or "Expense" ? type : "Expense",
            CurrencyCode = MemberCurrencyPreferences.Normalise(preferences.DefaultCurrency),
            TransactionDate = BudgetPlanSummary.PhilippineToday,
            ClientId = Guid.NewGuid(),
            Currencies = MemberCurrencyPreferences.Supported,
            BaseCurrency = account,
            ShowOriginalAmounts = preferences.ShowOriginalAmounts
        };
        var prepared = await WithCategoriesAsync(draft, token!, ct);
        if (Response.StatusCode == 401) return RedirectToAction("Login", "Account", new { area = "" });
        return View(prepared);
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Create([FromForm] MemberTransactionDraftViewModel model, CancellationToken ct)
    {
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        model = await WithCategoriesAsync(model, token!, ct);
        if (Response.StatusCode == 401) return RedirectToAction("Login", "Account", new { area = "" });
        var userId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        var accountCurrency = MemberSessionGate.BaseCurrency(User, sessions);
        var preferences = MemberCurrencyPreferences.Read(HttpContext, protection, userId);
        model.Currencies = MemberCurrencyPreferences.Supported;
        model.BaseCurrency = accountCurrency;
        model.ShowOriginalAmounts = preferences.ShowOriginalAmounts;

        ValidateCategory(model);
        if (model.ClientId is not { } clientId || clientId == Guid.Empty)
        {
            // A form without its identity (opened before this release, or altered) cannot be saved safely.
            // Drop the posted value (empty or malformed) and its binding error so the new identity is what renders.
            ModelState.Remove(nameof(model.ClientId));
            model.ClientId = Guid.NewGuid();
            ModelState.AddModelError(string.Empty, "This form has expired. Check the details and save again.");
            Response.StatusCode = 400;
            return View(model);
        }
        if (ModelState.IsValid)
        {
            // Rate review: a foreign-currency save must be confirmed against a freshly fetched quote.
            if (preferences.ReviewRateBeforeSaving
                && MemberCurrencyPreferences.NeedsRate(model.CurrencyCode, accountCurrency))
            {
                var quote = await exchangeRates.QuoteAsync(model.CurrencyCode, accountCurrency, model.Amount!.Value,
                    token!, ct, model.TransactionDate);
                if (quote.Status == 401)
                {
                    await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                    return RedirectToAction("Login", "Account", new { area = "" });
                }
                if (quote.Status != 200 || quote.Quote is not { } fetched)
                {
                    Response.StatusCode = quote.Status is 400 or 404 or 429 ? quote.Status : 503;
                    ModelState.AddModelError(string.Empty,
                        "A live exchange rate is required before this transaction can be saved. Try again shortly.");
                    return View(model);
                }

                var preview = new MemberFxReview.Quote(model.Amount!.Value, model.CurrencyCode,
                    model.TransactionDate!.Value, fetched.Rate, fetched.RateDate, fetched.ConvertedAmount,
                    accountCurrency, fetched.Source, DateTimeOffset.UtcNow.Add(MemberFxReview.Validity));

                if (!model.FxConfirmed)
                {
                    // First pass: show the quote and ask for explicit confirmation. Nothing is saved.
                    model.FxQuote = preview;
                    model.FxProof = MemberFxReview.Issue(protection, userId, preview);
                    model.ReviewRequired = true;
                    return View(model);
                }

                if (!MemberFxReview.Matches(protection, model.PostedFxProof, userId, model.Amount!.Value,
                        model.CurrencyCode, model.TransactionDate!.Value, preview))
                {
                    // Re-show the current quote so the member can confirm what will actually be applied.
                    model.FxQuote = preview;
                    model.FxProof = MemberFxReview.Issue(protection, userId, preview);
                    model.ReviewRequired = true;
                    Response.StatusCode = 400;
                    ModelState.AddModelError(string.Empty,
                        "The exchange rate changed or the confirmation expired. Review the rate and confirm again.");
                    return View(model);
                }
            }

            // The identity minted when this form opened (Android's TransactionCreationCoordinator contract): resubmitting
            // the same form after a double click or an unconfirmed outcome replays the one transaction instead of adding another.
            var request = new CreateTransactionRequest(clientId, model.Type, model.CategoryId!.Value,
                model.Amount!.Value, model.CurrencyCode, model.TransactionDate!.Value, model.Description);
            var result = await transactions.CreateAsync(request, token!, ct);
            if (result.Succeeded)
            {
                TempData["ActivityNotice"] = "Transaction saved.";
                return RedirectToAction(nameof(Index));
            }
            if (result.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account", new { area = "" });
            }
            Response.StatusCode = result.Status is 400 or 404 or 409 or 429 ? result.Status : 503;
            ModelState.AddModelError(string.Empty, MemberSessionGate.Message(result.Status, true, result.Message));
        }
        else Response.StatusCode = 400;
        return View(model);
    }

    [HttpGet]
    public async Task<IActionResult> Edit([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        var result = await transactions.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (result.Status == 403) return Forbid();
        if (!result.Succeeded || result.Value is null)
        {
            TempData["ActivityError"] = result.Status == 404
                ? "That transaction is no longer available."
                : MemberSessionGate.Message(result.Status);
            return RedirectToAction(nameof(Index));
        }
        if (IsBillPayment(result.Value))
        {
            TempData["ActivityError"] = "Bill payment transactions are protected and cannot be edited here.";
            return RedirectToAction(nameof(Index));
        }

        var accountCurrency = MemberSessionGate.BaseCurrency(User, sessions);
        var preferences = MemberCurrencyPreferences.Read(HttpContext, protection,
            User.FindFirstValue(ClaimTypes.NameIdentifier));
        var draft = new MemberTransactionDraftViewModel
        {
            TransactionId = id,
            Version = result.Value.Version,
            Type = result.Value.Type,
            CategoryId = result.Value.CategoryId,
            Amount = result.Value.Amount,
            CurrencyCode = result.Value.CurrencyCode,
            TransactionDate = result.Value.TransactionDate,
            Description = result.Value.Description,
            Currencies = MemberCurrencyPreferences.Supported,
            BaseCurrency = accountCurrency,
            ShowOriginalAmounts = preferences.ShowOriginalAmounts
        };
        var prepared = await WithCategoriesAsync(draft, token!, ct, result.Value.CategoryId);
        if (Response.StatusCode == 401) return RedirectToAction("Login", "Account", new { area = "" });
        return View("Create", prepared);
    }

    [HttpPost, ValidateAntiForgeryToken]
    public async Task<IActionResult> Edit([FromRoute] int id, [FromForm] MemberTransactionDraftViewModel model,
        CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        // Re-read the owner-scoped row instead of trusting client-supplied type, currency, rate, or bill linkage.
        var originalResult = await transactions.GetAsync(id, token!, ct);
        if (originalResult.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (originalResult.Status == 403) return Forbid();
        if (!originalResult.Succeeded || originalResult.Value is null)
        {
            TempData["ActivityError"] = originalResult.Status == 404
                ? "That transaction is no longer available."
                : MemberSessionGate.Message(originalResult.Status);
            return RedirectToAction(nameof(Index));
        }
        var original = originalResult.Value;
        if (IsBillPayment(original))
        {
            TempData["ActivityError"] = "Bill payment transactions are protected and cannot be edited here.";
            return RedirectToAction(nameof(Index));
        }

        model.TransactionId = id;
        model = await WithCategoriesAsync(model, token!, ct, original.CategoryId);
        if (Response.StatusCode == 401) return RedirectToAction("Login", "Account", new { area = "" });
        model.Currencies = MemberCurrencyPreferences.Supported;
        var userId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        var accountCurrency = MemberSessionGate.BaseCurrency(User, sessions);
        var preferences = MemberCurrencyPreferences.Read(HttpContext, protection, userId);
        model.BaseCurrency = accountCurrency;
        model.ShowOriginalAmounts = preferences.ShowOriginalAmounts;
        ValidateCategory(model);
        if (!MemberCurrencyPreferences.IsSupported(model.CurrencyCode))
            ModelState.AddModelError(nameof(model.CurrencyCode), "Choose a currency supported by OWLI.");

        // Match Android: an amount-only edit keeps the API's saved rate. A currency/date change gets a fresh
        // review when the member enabled it; the backend remains authoritative for the saved rate either way.
        var rateIdentityChanged = model.CurrencyCode != original.CurrencyCode
            || model.TransactionDate != original.TransactionDate;
        if (ModelState.IsValid && preferences.ReviewRateBeforeSaving && rateIdentityChanged
            && MemberCurrencyPreferences.NeedsRate(model.CurrencyCode, accountCurrency))
        {
            var quote = await exchangeRates.QuoteAsync(model.CurrencyCode, accountCurrency, model.Amount!.Value,
                token!, ct, model.TransactionDate);
            if (quote.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account", new { area = "" });
            }
            if (quote.Status != 200 || quote.Quote is not { } fetched)
            {
                Response.StatusCode = quote.Status is 400 or 404 or 429 ? quote.Status : 503;
                ModelState.AddModelError(string.Empty, "A live exchange rate is required before this edit can be saved. Try again shortly.");
                return View("Create", model);
            }

            var preview = new MemberFxReview.Quote(model.Amount!.Value, model.CurrencyCode,
                model.TransactionDate!.Value, fetched.Rate, fetched.RateDate, fetched.ConvertedAmount,
                accountCurrency, fetched.Source, DateTimeOffset.UtcNow.Add(MemberFxReview.Validity));
            if (!model.FxConfirmed)
            {
                model.FxQuote = preview;
                model.FxProof = MemberFxReview.Issue(protection, userId, preview);
                model.ReviewRequired = true;
                return View("Create", model);
            }
            if (!MemberFxReview.Matches(protection, model.PostedFxProof, userId, model.Amount!.Value,
                    model.CurrencyCode, model.TransactionDate!.Value, preview))
            {
                model.FxQuote = preview;
                model.FxProof = MemberFxReview.Issue(protection, userId, preview);
                model.ReviewRequired = true;
                Response.StatusCode = 400;
                ModelState.AddModelError(string.Empty, "The exchange rate changed or the confirmation expired. Review and confirm again.");
                return View("Create", model);
            }
        }

        // C1: send the version the member saw when opening the form, not the one just re-read, so an edit made
        // elsewhere in the meantime (for example on Android) is reported instead of silently overwritten.
        if (ModelState.IsValid && (model.Version is null || !System.Text.RegularExpressions.Regex.IsMatch(model.Version, "^[0-9A-F]{64}$")))
        {
            Response.StatusCode = 400;
            ModelState.AddModelError(string.Empty, "This edit form is out of date. Reload the transaction and try again.");
            return View("Create", model);
        }
        if (ModelState.IsValid)
        {
            var request = new UpdateTransactionRequest(model.Type, model.CategoryId!.Value, model.Amount!.Value,
                model.CurrencyCode, model.TransactionDate!.Value, model.Description, model.Version!);
            var result = await transactions.UpdateAsync(id, request, token!, ct);
            if (result.Succeeded)
            {
                TempData["ActivityNotice"] = "Transaction updated.";
                return RedirectToAction(nameof(Index));
            }
            if (result.Status == 401)
            {
                await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
                return RedirectToAction("Login", "Account", new { area = "" });
            }
            Response.StatusCode = result.Status is 400 or 404 or 409 or 429 ? result.Status : 503;
            ModelState.AddModelError(string.Empty, result.Status == 409
                ? "This transaction was changed elsewhere, for example in the OWLI app. Reload it to see the latest version before saving your edit."
                : MemberSessionGate.Message(result.Status, true, result.Message));
        }
        else Response.StatusCode = 400;
        return View("Create", model);
    }

    private static bool IsBillPayment(TransactionResponse row) => row.BillId is not null || row.BillDueDate is not null;

    [HttpGet]
    public async Task<IActionResult> Delete([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        var result = await transactions.GetAsync(id, token!, ct);
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        if (!result.Succeeded)
        {
            TempData["ActivityError"] = MemberSessionGate.Message(result.Status);
            return RedirectToAction(nameof(Index));
        }
        if (IsBillPayment(result.Value!))
        {
            TempData["ActivityError"] = "Bill payment transactions are protected and cannot be deleted here.";
            return RedirectToAction(nameof(Index));
        }
        return View(result.Value);
    }

    [HttpPost, ActionName(nameof(Delete)), ValidateAntiForgeryToken]
    public async Task<IActionResult> DeleteConfirmed([FromRoute] int id, CancellationToken ct)
    {
        if (id <= 0) return NotFound();
        var (status, token) = await MemberSessionGate.AcquireAsync(User, sessions, HttpContext, ct);
        if (status == 401) return RedirectToAction("Login", "Account", new { area = "" });

        var result = await transactions.DeleteAsync(id, token!, ct);
        if (result.Succeeded)
        {
            TempData["ActivityNotice"] = "Transaction deleted.";
            return RedirectToAction(nameof(Index));
        }
        if (result.Status == 401)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            return RedirectToAction("Login", "Account", new { area = "" });
        }
        // Redirect rather than re-render so a retried POST cannot delete twice.
        TempData["ActivityError"] = MemberSessionGate.Message(result.Status, true, result.Message);
        return RedirectToAction(nameof(Index));
    }

    private void ValidateCategory(MemberTransactionDraftViewModel model)
    {
        if (model.TransactionDate is null)
            ModelState.AddModelError(nameof(model.TransactionDate), "Choose a date.");
        var category = model.Categories.FirstOrDefault(item => item.CategoryId == model.CategoryId);
        if (model.CategoryId is null)
            ModelState.AddModelError(nameof(model.CategoryId), "Choose a category.");
        else if (category is null)
            ModelState.AddModelError(nameof(model.CategoryId), "That category is not available.");
        else if (category.Type != model.Type)
            ModelState.AddModelError(nameof(model.CategoryId), $"Choose an {model.Type.ToLowerInvariant()} category.");
    }

    private async Task<MemberTransactionDraftViewModel> WithCategoriesAsync(
        MemberTransactionDraftViewModel model, string bearer, CancellationToken ct, int? existingCategoryId = null)
    {
        var filter = await LoadCategoriesAsync(bearer, ct);
        if (filter.Failure == CategoryFailure.Unauthorized)
        {
            await MemberSessionGate.RejectAsync(User, sessions, HttpContext);
            Response.StatusCode = 401;
            return model;
        }
        model.Categories = filter.Succeeded
            ? filter.Value!.Where(item => item.IsActive || item.CategoryId == existingCategoryId).OrderBy(item => item.Name).ToList()
            : [];
        if (!filter.Succeeded)
        {
            Response.StatusCode = 503;
            ModelState.AddModelError(string.Empty, "Categories could not be loaded. Refresh before saving a transaction.");
        }
        return model;
    }

    private async Task<CategoryResult<List<CategoryResponse>>> LoadCategoriesAsync(string bearer, CancellationToken ct)
    {
        const int size = 100;
        var all = new List<CategoryResponse>();
        for (var page = 1; (long)(page - 1) * size <= int.MaxValue; page++)
        {
            var result = await categories.ListAsync(null, page, size, bearer, ct);
            if (!result.Succeeded) return CategoryResult<List<CategoryResponse>>.Fail(result.Failure);
            all.AddRange(result.Value!.Items);
            if ((long)page * size >= result.Value.TotalCount)
                return CategoryResult<List<CategoryResponse>>.Success(all.DistinctBy(item => item.CategoryId).ToList());
            if (result.Value.Items.Count != size) return CategoryResult<List<CategoryResponse>>.Fail(CategoryFailure.InvalidResponse);
        }
        return CategoryResult<List<CategoryResponse>>.Fail(CategoryFailure.InvalidResponse);
    }

    internal static DateOnly? ParseMonth(string? month) =>
        DateOnly.TryParseExact(month + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var value) ? value : null;

    private static DateOnly? MonthEnd(DateOnly? month) =>
        month is { } value ? new DateOnly(value.Year, value.Month, DateTime.DaysInMonth(value.Year, value.Month)) : null;
}
