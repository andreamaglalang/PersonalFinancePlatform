using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.AdminMvc.ViewModels;

public sealed class LoginViewModel
{
    [Required, EmailAddress, StringLength(256)]
    public string Email { get; set; } = string.Empty;
    [Required, StringLength(128), DataType(DataType.Password)]
    public string Password { get; set; } = string.Empty;
    public string? ReturnUrl { get; set; }
    public string? Message { get; set; }
}
