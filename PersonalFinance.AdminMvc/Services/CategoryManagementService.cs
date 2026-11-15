using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.DataProtection;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Services;

public sealed class CategoryManagementService(ICategoryApiClient api, AdminSessionStore store,
    IHttpContextAccessor accessor, IDataProtectionProvider protection) : ICategoryManagementService
{
    private sealed record EditSnapshot(int Id, string Name, string Type, string? Color, bool Active, DateTimeOffset IssuedUtc);

    public Task<CategoryResult<CategoryIndexViewModel>> ListAsync(CategoryIndexViewModel query, CancellationToken ct) =>
        InSessionAsync(async session =>
        {
            var result = await api.ListAsync(query.Type, query.Page, query.PageSize, session.Tokens.AccessToken, ct);
            if (!result.Succeeded) return CategoryResult<CategoryIndexViewModel>.Fail(result.Failure);
            var page = result.Value!;
            return CategoryResult<CategoryIndexViewModel>.Success(new()
            {
                Type = query.Type, Page = page.Page, PageSize = page.PageSize,
                Categories = page.Items.Where(c => c.IsSystemDefault).ToArray(),
                HasNext = page.Page < int.MaxValue && (long)page.Page * page.PageSize <= int.MaxValue
                    && (long)page.Page * page.PageSize < page.TotalCount
            });
        }, ct);

    public Task<CategoryResult<CategoryResponse>> CreateAsync(CategoryCreateViewModel model, CancellationToken ct) =>
        InSessionAsync(async session =>
        {
            var result = await api.CreateAsync(new() { Name = model.Name, Type = model.Type, ColorHEX = model.ColorHEX, IsActive = model.IsActive }, session.Tokens.AccessToken, ct);
            return RequireDefault(result);
        }, ct);

    public Task<CategoryResult<CategoryEditViewModel>> GetEditAsync(int id, CancellationToken ct) =>
        InSessionAsync(async session =>
        {
            var result = RequireDefault(await api.GetAsync(id, session.Tokens.AccessToken, ct), id);
            if (!result.Succeeded) return CategoryResult<CategoryEditViewModel>.Fail(result.Failure);
            var category = result.Value!;
            var snapshot = new EditSnapshot(id, category.Name, category.Type, category.ColorHEX, category.IsActive, DateTimeOffset.UtcNow);
            return CategoryResult<CategoryEditViewModel>.Success(new()
            {
                Name = category.Name, CurrentType = category.Type, ColorHEX = category.ColorHEX, IsActive = category.IsActive,
                OriginalState = Protector(session).Protect(JsonSerializer.Serialize(snapshot))
            });
        }, ct);

    public Task<CategoryResult<CategoryResponse>> UpdateAsync(int id, CategoryEditViewModel model, CancellationToken ct) =>
        InSessionAsync(async session =>
        {
            EditSnapshot? original;
            try { original = JsonSerializer.Deserialize<EditSnapshot>(Protector(session).Unprotect(model.OriginalState)); }
            catch (Exception ex) when (ex is CryptographicException or JsonException or ArgumentException)
            { return CategoryResult<CategoryResponse>.Fail(CategoryFailure.Stale); }
            if (original is null || original.Id != id || original.IssuedUtc > DateTimeOffset.UtcNow
                || DateTimeOffset.UtcNow - original.IssuedUtc > TimeSpan.FromMinutes(30))
                return CategoryResult<CategoryResponse>.Fail(CategoryFailure.Stale);
            var currentResult = RequireDefault(await api.GetAsync(id, session.Tokens.AccessToken, ct), id);
            if (!currentResult.Succeeded) return currentResult;
            var current = currentResult.Value!;
            if (current.Name != original.Name || current.Type != original.Type || current.ColorHEX != original.Color || current.IsActive != original.Active)
                return CategoryResult<CategoryResponse>.Fail(CategoryFailure.Stale);
            if (current.IsActive && !model.IsActive && !model.ConfirmDeactivation)
                return CategoryResult<CategoryResponse>.Fail(CategoryFailure.ConfirmationRequired);
            // Type/ownership never come from posted form values. Backend has no atomic ETag:
            // another administrator may still update between this GET and PUT.
            return RequireDefault(await api.UpdateAsync(id, new()
            {
                Name = model.Name, Type = current.Type, ColorHEX = model.ColorHEX, IsActive = model.IsActive
            }, session.Tokens.AccessToken, ct), id);
        }, ct);

    private IDataProtector Protector(AdminSession session) => protection.CreateProtector("OWLI.CategoryEdit.v1", session.Id);
    private static CategoryResult<CategoryResponse> RequireDefault(CategoryResult<CategoryResponse> result, int? expectedId = null) =>
        !result.Succeeded ? result
        : !result.Value!.IsSystemDefault ? CategoryResult<CategoryResponse>.Fail(CategoryFailure.NotFound)
        : expectedId.HasValue && result.Value.CategoryId != expectedId ? CategoryResult<CategoryResponse>.Fail(CategoryFailure.InvalidResponse)
        : result;

    private async Task<CategoryResult<T>> InSessionAsync<T>(Func<AdminSession, Task<CategoryResult<T>>> operation, CancellationToken ct)
    {
        var context = accessor.HttpContext ?? throw new InvalidOperationException("An HTTP request is required.");
        var session = store.Find(context.User.FindFirstValue(AdminSessionStore.SessionClaim));
        if (session is null) { await context.SignOutAsync(AdminSessionStore.Scheme); return CategoryResult<T>.Fail(CategoryFailure.Unauthorized); }
        CategoryResult<T> result;
        await session.Gate.WaitAsync(ct);
        try
        {
            if (!store.IsCurrent(session) || !context.User.IsInRole("Administrator")
                || !session.User.Roles.Contains("Administrator", StringComparer.Ordinal)
                || context.User.FindFirstValue(ClaimTypes.NameIdentifier) != session.User.Id.ToString(System.Globalization.CultureInfo.InvariantCulture)
                || session.Tokens.AccessTokenExpiresAtUtc <= DateTime.UtcNow)
                result = CategoryResult<T>.Fail(CategoryFailure.Unauthorized);
            else result = await operation(session);
            if (result.Failure == CategoryFailure.Unauthorized) store.Invalidate(session);
        }
        finally { session.Gate.Release(); }
        // Cookie sign-out calls the same store. Never sign out while holding its gate.
        if (result.Failure == CategoryFailure.Unauthorized) await context.SignOutAsync(AdminSessionStore.Scheme);
        return result;
    }
}
