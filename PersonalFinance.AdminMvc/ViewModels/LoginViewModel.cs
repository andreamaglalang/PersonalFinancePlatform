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

public sealed class RegisterViewModel
{
    [Required, StringLength(100)]
    [Display(Name = "Full name")]
    public string FullName { get; set; } = string.Empty;

    [Required, EmailAddress, StringLength(256)]
    [Display(Name = "Email address")]
    public string Email { get; set; } = string.Empty;

    [Required, StringLength(128, MinimumLength = 12, ErrorMessage = "Use at least 12 characters.")]
    [DataType(DataType.Password)]
    [Display(Name = "Password")]
    public string Password { get; set; } = string.Empty;

    [Required, Compare(nameof(Password), ErrorMessage = "The passwords do not match.")]
    [DataType(DataType.Password)]
    [Display(Name = "Confirm password")]
    public string ConfirmPassword { get; set; } = string.Empty;
}
