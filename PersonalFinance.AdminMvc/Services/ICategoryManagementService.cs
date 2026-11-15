using PersonalFinance.AdminMvc.Services.Contracts;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Services;

public interface ICategoryManagementService
{
    Task<CategoryResult<CategoryIndexViewModel>> ListAsync(CategoryIndexViewModel query, CancellationToken ct);
    Task<CategoryResult<CategoryResponse>> CreateAsync(CategoryCreateViewModel model, CancellationToken ct);
    Task<CategoryResult<CategoryEditViewModel>> GetEditAsync(int id, CancellationToken ct);
    Task<CategoryResult<CategoryResponse>> UpdateAsync(int id, CategoryEditViewModel model, CancellationToken ct);
}
