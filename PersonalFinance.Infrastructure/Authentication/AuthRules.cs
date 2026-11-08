using System.ComponentModel.DataAnnotations;
using System.Globalization;

namespace PersonalFinance.Infrastructure.Authentication;

public static class AuthRules
{
    public const string InvalidCredentials = "Invalid email or password.";
    public const string InvalidRefresh = "Invalid or expired refresh token.";
    public static string UserLock(int id) => "finance:auth:user:" + id.ToString(CultureInfo.InvariantCulture);
    public static bool IsValid(object request) => Validator.TryValidateObject(request, new ValidationContext(request), [], true);
}

