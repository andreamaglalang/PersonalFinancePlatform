using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

public interface ICategoryApiClient
{
    Task<CategoryResult<CategoryPageResponse>> ListAsync(string? type, int page, int pageSize, string bearer, CancellationToken ct);
    Task<CategoryResult<CategoryResponse>> GetAsync(int id, string bearer, CancellationToken ct);
    Task<CategoryResult<CategoryResponse>> CreateAsync(CategoryWriteRequest request, string bearer, CancellationToken ct);
    Task<CategoryResult<CategoryResponse>> UpdateAsync(int id, CategoryWriteRequest request, string bearer, CancellationToken ct);
}
