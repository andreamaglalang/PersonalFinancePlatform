namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class DashboardViewModel
{
    public required string AdministratorDisplayName { get; init; }
    public string AdministratorRoleLabel { get; } = "Administrator";
    public PersonalFinance.AdminMvc.Services.AdminStatistics? Statistics { get; init; }
}
