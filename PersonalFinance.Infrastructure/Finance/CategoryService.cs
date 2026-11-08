using Microsoft.Data.SqlClient;

using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class CategoryService(
    ApplicationDbContext db,
    ICurrentUser current,
    IFinancialWriteScope writes)
    : FinanceServiceBase(db, current, writes), ICategoryService
{
    public Task<FinanceResult<PagedResponse<CategoryResponse>>> ListAsync(
        CategoryQuery query,
        CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;

            if (!FinanceValidation.ValidPage(query.Page, query.PageSize) ||
                (query.Type is not null && !FinanceValidation.ValidType(query.Type)))
                throw new FinanceException(400, "Invalid category filters or pagination.");

            var rows = Db.Categories.AsNoTracking()
                .Where(c => c.UserId == userId || c.UserId == null);

            if (!query.IncludeInactive)
                rows = rows.Where(c => c.IsActive);

            if (query.Type is not null)
            {
                var type = FinanceValidation.ParseType(query.Type);
                rows = rows.Where(c => c.Type == type);
            }

            var count = await rows.LongCountAsync(ct);

            var items = await rows
                .OrderBy(c => c.Name)
                .ThenBy(c => c.CategoryId)
                .Skip((query.Page - 1) * query.PageSize)
                .Take(query.PageSize)
                .Select(FinanceData.CategoryProjection)
                .ToListAsync(ct);

            return FinanceResult<PagedResponse<CategoryResponse>>
                .Success(new(items, query.Page, query.PageSize, count));
        });

    public Task<FinanceResult<CategoryResponse>> GetAsync(
        int id,
        CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;

            var item = await Db.Categories.AsNoTracking()
                .Where(c => c.CategoryId == id &&
                            (c.UserId == userId || c.UserId == null))
                .Select(FinanceData.CategoryProjection)
                .SingleOrDefaultAsync(ct);

            return item is null
                ? FinanceResult<CategoryResponse>.Failure(404, "Category not found.")
                : FinanceResult<CategoryResponse>.Success(item);
        });

    public Task<FinanceResult<CategoryResponse>> CreateAsync(
        CategoryWriteRequest request,
        bool systemDefault,
        CancellationToken ct) => GuardCategoryWrite(async () =>
        {
            var userId = UserId;

            CheckAdministrator(systemDefault);
            Validate(request);

            return await Writes.ExecuteAsync(
                [FinanceValidation.UserLock(userId), CategoryScopeLock(systemDefault, userId)],
                async token =>
                {
                    var categoryOwnerId = systemDefault ? (int?)null : userId;

                    await EnsureUniqueNameAsync(
                        request.Name,
                        request.Type,
                        categoryOwnerId,
                        null,
                        token);

                    var row = new Category
                    {
                        UserId = categoryOwnerId,
                        Name = CategoryNameNormalization.Trim(request.Name),
                        Type = FinanceValidation.ParseType(request.Type),
                        ColorHEX = request.ColorHEX,
                        IsActive = request.IsActive ?? true
                    };

                    Db.Categories.Add(row);

                    await Db.SaveChangesAsync(token);

                    return FinanceResult<CategoryResponse>
                        .Success(Describe(row), 201);
                },
                ct);
        });

    public Task<FinanceResult<CategoryResponse>> UpdateAsync(
        int id,
        CategoryWriteRequest request,
        bool systemDefault,
        CancellationToken ct) => GuardCategoryWrite(async () =>
        {
            var userId = UserId;

            CheckAdministrator(systemDefault);
            Validate(request);

            return await Writes.ExecuteAsync(
                [
                    FinanceValidation.CategoryLock(id),
                    FinanceValidation.UserLock(userId), CategoryScopeLock(systemDefault, userId)
                ],
                async token =>
                {
                    var row = await WritableAsync(id, systemDefault, token);

                    var type = FinanceValidation.ParseType(request.Type);

                    if (row.Type != type &&
                        await FinanceData.HasReferencesAsync(Db, id, token))
                        throw new FinanceException(
                            409,
                            "Referenced categories cannot change type. Rename or archive the category instead.");

                    await EnsureUniqueNameAsync(
                        request.Name,
                        request.Type,
                        row.UserId,
                        id,
                        token);

                    row.Name = CategoryNameNormalization.Trim(request.Name);
                    row.Type = type;
                    row.ColorHEX = request.ColorHEX;

                    if (request.IsActive.HasValue)
                        row.IsActive = request.IsActive.Value;

                    await Db.SaveChangesAsync(token);

                    return FinanceResult<CategoryResponse>
                        .Success(Describe(row));
                },
                ct);
        });

    public Task<FinanceResult<bool>> DeleteAsync(
        int id,
        bool systemDefault,
        CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;

            CheckAdministrator(systemDefault);

            return await Writes.ExecuteAsync(
                [
                    FinanceValidation.CategoryLock(id),
                    FinanceValidation.UserLock(userId), CategoryScopeLock(systemDefault, userId)
                ],
                async token =>
                {
                    var row = await WritableAsync(id, systemDefault, token);

                    if (await FinanceData.HasReferencesAsync(Db, id, token))
                        throw new FinanceException(
                            409,
                            "Referenced categories cannot be deleted. Archive the category instead.");

                    Db.Categories.Remove(row);

                    await Db.SaveChangesAsync(token);

                    return FinanceResult<bool>.Success(true, 204);
                },
                ct);
        });

    private async Task<Category> WritableAsync(
        int id,
        bool systemDefault,
        CancellationToken ct)
    {
        var userId = UserId;

        var row = await FinanceData.LockedCategory(Db, id)
            .SingleOrDefaultAsync(
                c => c.UserId == userId || c.UserId == null,
                ct);

        if (row is null || (systemDefault && row.UserId is not null))
            throw new FinanceException(404, "Category not found.");

        if (!systemDefault && row.UserId is null)
            throw new FinanceException(
                403,
                "System categories require administrator management.");

        return row;
    }

    private async Task EnsureUniqueNameAsync(
        string name,
        string type,
        int? userId,
        int? excludeCategoryId,
        CancellationToken ct)
    {
        var normalizedName = CategoryNameNormalization.Trim(name);
        var categoryType = FinanceValidation.ParseType(type);

        var exists = await Db.Categories.AnyAsync(
            c => c.UserId == userId &&
                 c.Type == categoryType &&
                 (!excludeCategoryId.HasValue ||
                  c.CategoryId != excludeCategoryId.Value) &&
                 EF.Property<string>(c, CategoryNameNormalization.PropertyName) == normalizedName,
            ct);

        if (exists)
            throw new FinanceException(
                409,
                "A category with the same name and type already exists.");
    }

    private void CheckAdministrator(bool systemDefault)
    {
        if (systemDefault && !Current.IsAdministrator)
            throw new FinanceException(
                403,
                "Administrator authorization is required.");
    }

    private static void Validate(CategoryWriteRequest request)
    {
        if (!FinanceValidation.Valid(request) || CategoryNameNormalization.Trim(request.Name).Length == 0)
            throw new FinanceException(400, "Invalid category data.");
    }

    private static string CategoryScopeLock(bool systemDefault, int userId) =>
        systemDefault ? CategoryNameNormalization.SystemWriteLock : FinanceValidation.UserLock(userId);

    private Task<FinanceResult<T>> GuardCategoryWrite<T>(Func<Task<FinanceResult<T>>> operation) => Guard(async () =>
    {
        try { return await operation(); }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException sql &&
            sql.Errors.Cast<SqlError>().Any(e => e.Number is 2601 or 2627))
        {
            // ExecuteAsync has already unwound and disposed its transaction.
            // Clear failed tracked values so they cannot be saved by a later operation in this scope.
            Db.ChangeTracker.Clear();
            return FinanceResult<T>.Failure(409, "A category with the same name and type already exists.");
        }
    });

    private static CategoryResponse Describe(Category row) =>
        new(
            row.CategoryId,
            row.Name,
            row.Type.ToString(),
            row.ColorHEX,
            row.IsActive,
            row.UserId is null);
}
