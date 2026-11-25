using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.DataProtection;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>
/// Browser-local currency preferences for the signed-in member, stored in an encrypted cookie. The cookie records the
/// account that wrote it, so one account's settings can never surface in another account's browser session. It holds
/// no tokens and no financial data, and it never synchronises across devices.
/// </summary>
public static class MemberCurrencyPreferences
{
    public const string CookieName = ".owli.member.currency";
    public const string AccountCurrency = "PHP";
    public const string ProtectorPurpose = "owli.member.currency.v1";

    /// <summary>Matches Android's SupportedCurrencies: the ECB reference set, which includes PHP.</summary>
    public static readonly string[] Supported =
        ["PHP", "USD", "EUR", "JPY", "GBP", "AUD", "CAD", "SGD", "HKD", "CNY", "KRW", "CHF"];

    public sealed record Preferences(string DefaultCurrency, bool ShowOriginalAmounts, bool ReviewRateBeforeSaving)
    {
        public static Preferences Defaults => new(AccountCurrency, ShowOriginalAmounts: true, ReviewRateBeforeSaving: false);
    }

    public static bool IsSupported(string? code) => code is not null && Supported.Contains(code);

    public static string Normalise(string? code) => IsSupported(code) ? code! : AccountCurrency;

    /// <summary>True when the currency differs from the account currency and therefore needs a server-side rate.</summary>
    public static bool NeedsRate(string? code, string accountCurrency) =>
        IsSupported(code) && !string.Equals(code, accountCurrency, StringComparison.Ordinal);

    public static Preferences Read(HttpContext http, IDataProtectionProvider protection, string? userId)
    {
        var raw = http.Request.Cookies[CookieName];
        if (string.IsNullOrEmpty(raw) || userId is null) return Preferences.Defaults;
        try
        {
            var plain = Encoding.UTF8.GetString(
                protection.CreateProtector(ProtectorPurpose).Unprotect(Base64Url.Decode(raw)));
            return TryUnpack(plain, userId, out var value) ? value : Preferences.Defaults;
        }
        catch (CryptographicException) { return Preferences.Defaults; }
        catch (FormatException) { return Preferences.Defaults; }
    }

    public static void Write(HttpContext http, IDataProtectionProvider protection, string? userId, Preferences value)
    {
        if (userId is null) return;
        var payload = Encoding.UTF8.GetBytes(Pack(userId, value));
        var protectedPayload = protection.CreateProtector(ProtectorPurpose).Protect(payload);
        http.Response.Cookies.Append(CookieName, Base64Url.Encode(protectedPayload), new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Lax,
            Expires = DateTimeOffset.UtcNow.AddYears(1),
            IsEssential = true
        });
    }

    /// <summary>Serialises preferences for the cookie. Kept pure so the round-trip is unit-testable.</summary>
    public static string Pack(string userId, Preferences value) =>
        "v1|" + userId + "|" + Normalise(value.DefaultCurrency) + "|"
        + (value.ShowOriginalAmounts ? "1" : "0") + "|" + (value.ReviewRateBeforeSaving ? "1" : "0");

    /// <summary>
    /// Restores preferences. Fails closed: a malformed payload or one written for another account yields defaults,
    /// never another member's settings.
    /// </summary>
    public static bool TryUnpack(string payload, string? userId, out Preferences value)
    {
        var parts = payload.Split('|');
        if (parts.Length != 5 || parts[0] != "v1" || userId is null
            || !string.Equals(parts[1], userId, StringComparison.Ordinal))
        {
            value = Preferences.Defaults;
            return false;
        }
        value = new Preferences(Normalise(parts[2]), parts[3] == "1", parts[4] == "1");
        return true;
    }

    /// <summary>URL-safe base64: a protected payload is binary and must survive a cookie value round-trip.</summary>
    public static class Base64Url
    {
        public static string Encode(byte[] value) =>
            Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        public static byte[] Decode(string value)
        {
            var raw = value.Replace('-', '+').Replace('_', '/');
            return Convert.FromBase64String(raw.PadRight(raw.Length + (4 - raw.Length % 4) % 4, '='));
        }
    }
}
