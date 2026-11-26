using System.ComponentModel.DataAnnotations;
using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

static void Check(bool condition, string description)
{
    if (!condition) throw new InvalidOperationException(description);
}

try
{
await AuthorizationChecks.Run();
AuthenticationPreservationChecks.Run();
RoleClaimsChecks.Run();
OwliLandingChecks.Run();
MemberAreaChecks.Run();
RegistrationChecks.Run();
await RegistrationChecks.RunStatusContractAsync();
MemberShellChecks.Run();
DesignTokenChecks.Run();
MemberLayoutChecks.Run();
MemberDashboardChecks.Run();
MemberSessionGateChecks.Run();
TransactionApiChecks.Run();
await MemberActivityRedesignChecks.RunAsync();
await ActivityParityChecks.RunAsync();
await ActivityRenderChecks.RunAsync();
MemberActivityWriteChecks.Run();
MemberDashboardDataChecks.Run();
await MemberDashboardAllTimeChecks.RunAsync();
MemberBudgetChecks.Run();
PlanBudgetSummaryChecks.Run();
MemberInsightsChecks.Run();
await InsightsRedesignChecks.RunAsync();
await InsightsRenderChecks.RunAsync();
MemberGoalBillChecks.Run();
MemberNotificationChecks.Run();
MemberCurrencyChecks.Run();
await MemberAccountCurrencyConverterChecks.RunAsync();
await AccountRenderChecks.RunAsync();
await AccountWorkflowChecks.RunAsync();
await MemberTransactionEditChecks.RunAsync();
await MemberCategoryManagementChecks.RunAsync();
await SavingsGoalContributionMvcChecks.RunAsync();
await SavingsGoalResponseChecks.RunAsync();
await BillPaymentResponseChecks.RunAsync();

var categories = new PageFixture();
var controller = new CategoriesController(categories)
{
    ControllerContext = new() { HttpContext = new DefaultHttpContext() }
};
var query = new CategoryIndexViewModel { Type = "Expense", Page = 2, Search = " travel ", State = "active", Sort = "name-desc" };
var page = (CategoryIndexViewModel)((ViewResult)await controller.Index(query, default)).Model!;
Check(page.Categories.Select(row => row.Name).SequenceEqual(new[] { "Travel rail", "Travel bus" }),
    "Search, active status and descending sort apply to the returned page.");
Check(page.HasPrevious && page.HasNext && page.Page == 2 && categories.RequestedPage == 2
    && categories.RequestedType == "Expense" && page.Search == query.Search && page.State == query.State && page.Sort == query.Sort,
    "The API filters, mixed-page navigation, and UI query remain intact.");
var invalid = new CategoryIndexViewModel { Sort = "unknown", State = "other" };
var errors = new List<ValidationResult>();
Check(!Validator.TryValidateObject(invalid, new(invalid), errors, true) && errors.Count == 2, "Invalid filters are rejected.");

foreach (var type in new[] { typeof(CategoriesController), typeof(BudgetsController), typeof(ReportsController) })
{
    Check(type.GetCustomAttribute<AuthorizeAttribute>()?.Policy == "Administrator", $"{type.Name} requires Administrator.");
    foreach (var post in type.GetMethods().Where(method => method.IsDefined(typeof(HttpPostAttribute))))
        Check(post.IsDefined(typeof(ValidateAntiForgeryTokenAttribute)), $"{post.Name} requires antiforgery.");
}
var account = typeof(AccountController);
Check(account.GetMethod(nameof(AccountController.Index))?.GetCustomAttribute<AuthorizeAttribute>()?.Policy == "Administrator",
    "Account requires Administrator.");
Check(account.GetMethod(nameof(AccountController.Logout))!.IsDefined(typeof(ValidateAntiForgeryTokenAttribute)),
    "Sign-out requires antiforgery.");

Console.WriteLine("PASS: page-local category filtering and sorting, pagination, input validation, Administrator and antiforgery metadata.");
await ReportsChecks.RunAsync();
}
catch (Exception error)
{
    Console.Error.WriteLine(error);
    Environment.ExitCode = 1;
}

sealed class PageFixture : ICategoryManagementService
{
    public int RequestedPage { get; private set; }
    public string? RequestedType { get; private set; }

    public Task<CategoryResult<CategoryIndexViewModel>> ListAsync(CategoryIndexViewModel query, CancellationToken ct)
    {
        RequestedPage = query.Page;
        RequestedType = query.Type;
        return Task.FromResult(CategoryResult<CategoryIndexViewModel>.Success(new()
        {
            Type = query.Type, Page = query.Page, PageSize = query.PageSize, HasNext = true,
            Categories = [Row(1, "Travel bus", true), Row(2, "Travel inactive", false), Row(3, "Food", true), Row(4, "Travel rail", true)]
        }));
    }

    private static CategoryResponse Row(int id, string name, bool active) => new()
    {
        CategoryId = id, Name = name, Type = "Expense", IsActive = active, IsSystemDefault = true, ColorHEX = null
    };

    public Task<CategoryResult<CategoryResponse>> CreateAsync(CategoryCreateViewModel model, CancellationToken ct) => throw new NotSupportedException();
    public Task<CategoryResult<CategoryEditViewModel>> GetEditAsync(int id, CancellationToken ct) => throw new NotSupportedException();
    public Task<CategoryResult<CategoryResponse>> UpdateAsync(int id, CategoryEditViewModel model, CancellationToken ct) => throw new NotSupportedException();
}
