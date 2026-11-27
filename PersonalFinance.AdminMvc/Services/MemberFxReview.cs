using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.DataProtection;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>
/// Binds a fetched exchange-rate quote to a single save. The review step issues a short-lived protected token that
/// commits to the account, the amount, currency, date and the exact rate shown; the save is refused without a token
/// that still matches, so a stale or hand-made confirmation cannot authorise a foreign-currency transaction.
/// </summary>
public static class MemberFxReview
{
    public const string ProtectorPurpose = "owli.member.fxreview.v1";
    public static readonly TimeSpan Validity = TimeSpan.FromMinutes(10);

    public sealed record Quote(decimal Amount, string CurrencyCode, DateOnly TransactionDate, decimal Rate,
        DateOnly RateDate, decimal BaseAmount, string BaseCurrency, string Source, DateTimeOffset ExpiresUtc);

    public static string Issue(IDataProtectionProvider protection, string? userId, Quote quote)
    {
        var payload = string.Create(CultureInfo.InvariantCulture,
            $"{quote.Amount:0.00}|{quote.CurrencyCode}|{quote.TransactionDate:yyyy-MM-dd}|{quote.Rate:0.########}|{quote.RateDate:yyyy-MM-dd}|{quote.BaseCurrency}|{quote.Source}|{quote.ExpiresUtc.Ticks}");
        // The account is part of the protector purpose, so a token minted for one account fails for another.
        return MemberCurrencyPreferences.Base64Url.Encode(
            protection.CreateProtector(ProtectorPurpose + "|" + (userId ?? string.Empty))
                .Protect(Encoding.UTF8.GetBytes(payload)));
    }

    /// <summary>True only when the token is intact, unexpired and matches the submitted transaction and the current quote.</summary>
    public static bool Matches(IDataProtectionProvider protection, string? proof, string? userId,
        decimal amount, string currency, DateOnly date, Quote expected)
    {
        if (string.IsNullOrEmpty(proof) || userId is null) return false;
        try
        {
            var plain = Encoding.UTF8.GetString(
                protection.CreateProtector(ProtectorPurpose + "|" + userId)
                    .Unprotect(MemberCurrencyPreferences.Base64Url.Decode(proof)));
            var parts = plain.Split('|');
            if (parts.Length != 8) return false;
            return decimal.TryParse(parts[0], NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var a)
                && a == expected.Amount && a == amount
                && string.Equals(parts[1], expected.CurrencyCode, StringComparison.Ordinal)
                && string.Equals(parts[1], currency, StringComparison.Ordinal)
                && DateOnly.TryParseExact(parts[2], "yyyy-MM-dd", CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.None, out var d)
                && d == date
                && decimal.TryParse(parts[3], NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var r)
                && r == expected.Rate
                && DateOnly.TryParseExact(parts[4], "yyyy-MM-dd", CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.None, out var rd)
                && rd == expected.RateDate
                && string.Equals(parts[5], expected.BaseCurrency, StringComparison.Ordinal)
                && string.Equals(parts[6], expected.Source, StringComparison.Ordinal)
                && long.TryParse(parts[7], NumberStyles.Integer, CultureInfo.InvariantCulture, out var ticks)
                // The expiry is the one issued with the token; the fresh quote on confirmation carries its own later expiry.
                && ticks >= DateTimeOffset.MinValue.UtcTicks && ticks <= DateTimeOffset.MaxValue.UtcTicks
                && DateTimeOffset.UtcNow <= new DateTimeOffset(ticks, TimeSpan.Zero);
        }
        catch (CryptographicException) { return false; }
        catch (FormatException) { return false; }
    }
}
