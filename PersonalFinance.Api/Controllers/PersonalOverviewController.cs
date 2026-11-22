using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Api.Authentication;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

[ApiController, Authorize]
public sealed class PersonalOverviewController(ApplicationDbContext db, IConversionService conversion, TimeProvider clock)
    : ControllerBase
{
    [HttpGet("api/reports/overview")]
    public async Task<ActionResult<OverviewResponse>> Overview(DateOnly? month, [FromQuery] TransactionQuery filters, CancellationToken ct)
    {
        if (CurrentUser.ReadUserId(User) is not int owner) return Unauthorized();
        if (!TransactionSelection.IsValid(filters) || (month is { } selected && (selected.Day != 1 || selected.Year is < 2000 or > 9998)))
            return BadRequest();
        var currency = await db.Users.Where(user => user.Id == owner).Select(user => user.BaseCurrency).SingleOrDefaultAsync(ct);
        if (currency is null) return Unauthorized();

        var owned = db.Transactions.AsNoTracking().Where(row => row.UserId == owner);
        var rows = TransactionSelection.Apply(owned, filters);
        if (month is { } start)
        {
            var end = start.AddMonths(1);
            rows = rows.Where(row => row.TransactionDate >= start && row.TransactionDate < end);
        }

        var breakdown = await rows.GroupBy(row => new { row.Type, row.CategoryId, row.Category.Name })
            .Select(group => new
            {
                group.Key.Type,
                Category = new ReportCategoryTotal(group.Key.CategoryId, group.Key.Name,
                    group.Sum(row => row.AmountBase), group.Count())
            }).ToListAsync(ct);
        var expenses = breakdown.Where(row => row.Type == TransactionType.Expense).Select(row => row.Category)
            .OrderByDescending(row => row.Total).ThenBy(row => row.CategoryId).ToArray();
        var income = breakdown.Where(row => row.Type == TransactionType.Income).Sum(row => row.Category.Total);
        var expense = expenses.Sum(row => row.Total);
        var daily = await rows.GroupBy(row => row.TransactionDate).OrderByDescending(group => group.Key).Take(31)
            .Select(group => new DailyTotal(group.Key,
                group.Sum(row => row.Type == TransactionType.Income ? row.AmountBase : 0m),
                group.Sum(row => row.Type == TransactionType.Expense ? row.AmountBase : 0m)))
            .ToListAsync(ct);
        var periods = await owned.GroupBy(row => new { row.TransactionDate.Year, row.TransactionDate.Month })
            .Select(group => new
            {
                group.Key.Year, group.Key.Month,
                Income = group.Sum(row => row.Type == TransactionType.Income ? row.AmountBase : 0m),
                Expenses = group.Sum(row => row.Type == TransactionType.Expense ? row.AmountBase : 0m)
            })
            .OrderByDescending(row => row.Year).ThenByDescending(row => row.Month).Take(12).ToListAsync(ct);
        return new OverviewResponse(month, currency, income, expense, income - expense,
            breakdown.Sum(row => row.Category.TransactionCount), expenses,
            daily.OrderBy(row => row.Date).ToArray(),
            periods.Select(row => new MonthTotal(new DateOnly(row.Year, row.Month, 1), row.Income, row.Expenses)).ToArray());
    }

    [HttpGet("api/transactions/conversion")]
    public async Task<ActionResult<CurrencyQuote>> Convert(decimal amount, string source, string target, CancellationToken ct)
    {
        var date = DateOnly.FromDateTime(clock.GetUtcNow().UtcDateTime);
        try
        {
            var quote = await conversion.ConvertAsync(amount, source, target, date, ct);
            return new CurrencyQuote(amount, source, target, quote.Rate, quote.RateDate, quote.AmountBase);
        }
        catch (FinanceException ex)
        {
            return Problem(statusCode: ex.StatusCode, title: ex.Message);
        }
    }

    [HttpGet("api/recurring-bills")]
    public async Task<ActionResult<PagedResponse<BillSummary>>> Bills(int page = 1, CancellationToken ct = default)
    {
        if (CurrentUser.ReadUserId(User) is not int owner) return Unauthorized();
        const int size = 20;
        if (!FinanceValidation.ValidPage(page, size)) return BadRequest();
        var owned = db.RecurringBills.AsNoTracking().Where(row => row.UserId == owner);
        var payments = db.Transactions.AsNoTracking().Where(row => row.UserId == owner && row.BillId.HasValue);
        var count = await owned.LongCountAsync(ct);
        // Project the schedule columns present before the payment-receipt migration.
        var rows = await owned.OrderByDescending(row => row.IsActive)
            .ThenBy(row => row.NextDueDate).ThenBy(row => row.BillId).Skip((page - 1) * size).Take(size)
            .Select(row => new
            {
                row.BillId, row.Name, row.Amount, row.CurrencyCode, row.Frequency, row.NextDueDate, row.IsActive,
                CurrentPaid = payments.Any(payment => payment.BillId == row.BillId && payment.BillDueDate == row.NextDueDate),
                LastPayment = payments.Where(payment => payment.BillId == row.BillId)
                    .Max(payment => (DateOnly?)payment.TransactionDate)
            }).ToListAsync(ct);
        var today = DateOnly.FromDateTime(clock.GetUtcNow().UtcDateTime);
        return new PagedResponse<BillSummary>(rows.Select(row => new BillSummary(row.BillId, row.Name,
            row.Amount, row.CurrencyCode, row.Frequency.ToString(), row.NextDueDate,
            !row.IsActive ? "Inactive" : row.CurrentPaid ? "Paid" : row.NextDueDate < today ? "Overdue"
                : row.NextDueDate == today ? "Due today" : "Upcoming", row.LastPayment)).ToArray(), page, size, count);
    }

    public sealed record BillSummary(int BillId, string Name, decimal Amount, string CurrencyCode, string Frequency,
        DateOnly NextDueDate, string Status, DateOnly? LastPayment);

    public sealed record DailyTotal(DateOnly Date, decimal Income, decimal Expenses);
    public sealed record MonthTotal(DateOnly Month, decimal Income, decimal Expenses);
    public sealed record OverviewResponse(DateOnly? Month, string BaseCurrency, decimal TotalIncome, decimal TotalExpenses,
        decimal NetCashFlow, int TransactionCount, ReportCategoryTotal[] ExpenseByCategory, DailyTotal[] Days, MonthTotal[] Months);
    public sealed record CurrencyQuote(decimal Amount, string Source, string Target, decimal Rate, DateOnly RateDate, decimal ConvertedAmount);
}

[ApiController, Authorize(Roles = ApplicationRoles.User), Route("api/auth/profile"), RequestSizeLimit(4096)]
public sealed class PersonalProfileController(UserManager<ApplicationUser> users, IAuthSessionStore sessions) : ControllerBase
{
    [HttpPut]
    public async Task<IActionResult> Update(NameInput input, CancellationToken ct)
    {
        if (CurrentUser.ReadUserId(User) is not int owner || User.IsInRole(ApplicationRoles.Administrator))
            return Forbid();
        return await sessions.ExecuteAsync<IActionResult>(AuthRules.UserLock(owner), async token =>
        {
            var user = await users.FindByIdAsync(owner.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if (user is null) return Unauthorized();
            await sessions.ReloadUserAsync(user, token);
            if (await users.IsLockedOutAsync(user)) return Unauthorized();
            var roles = await users.GetRolesAsync(user);
            if (!roles.Contains(ApplicationRoles.User) || roles.Contains(ApplicationRoles.Administrator)) return Forbid();
            user.FullName = input.FullName.Trim();
            if (!(await users.UpdateAsync(user)).Succeeded)
                return Conflict();
            return Ok(new PersonalFinance.Infrastructure.Authentication.Contracts.CurrentUserResponse(
                user.Id, user.FullName, user.Email!, user.BaseCurrency, roles.ToArray()));
        }, ct);
    }

    [HttpPut("/api/auth/password"), EnableRateLimiting(AuthenticationConfiguration.AuthRateLimit)]
    public async Task<IActionResult> ChangePassword(PasswordInput input, [FromServices] TimeProvider clock, CancellationToken ct)
    {
        if (CurrentUser.ReadUserId(User) is not int owner || User.IsInRole(ApplicationRoles.Administrator))
            return Forbid();
        return await sessions.ExecuteAsync<IActionResult>(AuthRules.UserLock(owner), async token =>
        {
            var user = await users.FindByIdAsync(owner.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if (user is null) return Unauthorized();
            await sessions.ReloadUserAsync(user, token);
            if (await users.IsLockedOutAsync(user)) return Unauthorized();
            var roles = await users.GetRolesAsync(user);
            if (!roles.Contains(ApplicationRoles.User) || roles.Contains(ApplicationRoles.Administrator)) return Forbid();

            var version = User.FindFirst(CredentialVersion.Claim)?.Value;
            if (!await CredentialVersion.AllowsAsync(users, user, version)) return Unauthorized();
            if (input.CurrentPassword == input.NewPassword)
                return Problem(statusCode: 400, title: "Choose a different password.");
            var changed = await users.ChangePasswordAsync(user, input.CurrentPassword, input.NewPassword);
            if (!changed.Succeeded)
                return Problem(statusCode: 400, title: "Check your current password and the new password requirements.");

            var marked = await users.SetAuthenticationTokenAsync(user, CredentialVersion.Provider,
                CredentialVersion.Marker, "1");
            if (!marked.Succeeded)
                throw new InvalidOperationException("Password change could not invalidate older access tokens.");
            await sessions.RevokeAllAsync(owner, clock.GetUtcNow().UtcDateTime, token);
            return NoContent();
        }, ct);
    }

    public sealed class PasswordInput
    {
        [Required, StringLength(128)] public string CurrentPassword { get; set; } = "";
        [Required, StringLength(128, MinimumLength = 12)] public string NewPassword { get; set; } = "";
        [Required, StringLength(128), Compare(nameof(NewPassword))] public string ConfirmPassword { get; set; } = "";
    }

    public sealed record NameInput([Required, StringLength(100)] string FullName);
}
