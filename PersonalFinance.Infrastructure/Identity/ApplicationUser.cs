using Microsoft.AspNetCore.Identity;

namespace PersonalFinance.Infrastructure.Identity;

public sealed class ApplicationUser : IdentityUser<int>
{
    public string FullName { get; set; } = string.Empty;
    public string BaseCurrency { get; set; } = "PHP";
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}

