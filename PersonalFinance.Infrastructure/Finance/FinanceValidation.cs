using System.ComponentModel.DataAnnotations;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.Infrastructure.Finance;

public static class FinanceValidation
{
    public const decimal MaximumMoney = 9999999999999999.99m;
    public const decimal MaximumRate = 9999999999.99999999m;

    public static string UserLock(int userId) => $"finance:write:user:{userId}";
    public static string CategoryLock(int id) => $"finance:write:category:{id}";

    public static bool Valid(object value) => Validator.TryValidateObject(value, new ValidationContext(value), [], true);
    public static bool ValidType(string? type) => type is nameof(TransactionType.Income) or nameof(TransactionType.Expense);
    public static TransactionType ParseType(string type) => Enum.Parse<TransactionType>(type);

    public static void ValidateMoney(decimal amount)
    {
        if (amount <= 0 || amount > MaximumMoney || ((decimal.GetBits(amount)[3] >> 16) & 0x7F) > 2)
            throw new FinanceException(400, "Amount must be positive, within decimal(18,2), and have at most two decimal places.");
    }

    public static decimal NormalizeRate(decimal rate)
    {
        if (rate <= 0 || rate > MaximumRate)
            throw new FinanceException(503, "No acceptable exchange rate is available.");
        var rounded = decimal.Round(rate, 8, MidpointRounding.AwayFromZero);
        if (rounded <= 0 || rounded > MaximumRate)
            throw new FinanceException(503, "No acceptable exchange rate is available.");
        return rounded;
    }

    public static decimal ConvertAmount(decimal amount, decimal roundedRate)
    {
        ValidateMoney(amount);
        try
        {
            var converted = decimal.Round(checked(amount * roundedRate), 2, MidpointRounding.AwayFromZero);
            if (converted < 0 || converted > MaximumMoney)
                throw new FinanceException(400, "The converted amount exceeds the supported monetary range.");
            return converted;
        }
        catch (OverflowException) { throw new FinanceException(400, "The converted amount exceeds the supported monetary range."); }
    }

    public static bool ValidPage(int page, int size) =>
        page >= 1 && size is >= 1 and <= 100 && (long)(page - 1) * size <= int.MaxValue;

    public static bool CurrencyCode(string value) => value.Length == 3 && value.All(c => c is >= 'A' and <= 'Z');
}

