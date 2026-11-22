namespace PersonalFinance.AdminMvc.ViewModels;

public static class PersonalMoneyDisplay
{
    public static string Format(decimal amount, string currency, bool signed = false)
    {
        var sign = amount < 0 ? "−" : signed && amount > 0 ? "+" : "";
        var number = Math.Abs(amount).ToString("N2");
        return currency == "PHP" ? $"{sign}₱{number}" : $"{sign}{number} {currency}";
    }
}
