using System.Globalization;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>Projects a complete, owner-scoped API history into Activity totals and a server-paged result.</summary>
public static class ActivityProjection
{
    private static readonly string[] CategoryPalette =
    [
        "#6747C7", "#7E62D8", "#A55AA9", "#C9577A", "#CC5F49", "#B8691F", "#93791A", "#667F2A",
        "#2F8A5B", "#1F8A82", "#2F7FB0", "#4468C9", "#5F6F9A", "#8F6D5C", "#74508F"
    ];

    public static string DisplayColor(int categoryId, string? storedColor) =>
        storedColor is { Length: 7 } && storedColor[0] == '#'
            && storedColor.AsSpan(1).ToString().All(Uri.IsHexDigit)
            ? storedColor.ToUpperInvariant()
            : CategoryPalette[(int)((uint)categoryId % CategoryPalette.Length)];

    public static string IconKey(string name, string type)
    {
        var n = name.ToLowerInvariant();
        var rules = new (string[] Words, string Key)[]
        {
            (["health", "medic", "doctor", "pharm"], "heart"), (["fitness", "gym"], "dumbbell"),
            (["grocer"], "apple"), (["coffee", "cafe"], "coffee"), (["drink", "bar"], "cocktail"),
            (["food", "meal", "dining", "restaurant", "lunch"], "food"),
            (["transport", "commute", "bus", "fare"], "bus"), (["car", "parking"], "car"),
            (["fuel", "gas"], "fuel"), (["travel", "flight", "trip"], "plane"),
            (["utilit", "electric", "power"], "bolt"), (["water"], "drop"), (["internet", "wifi"], "wifi"),
            (["rent", "hous", "home"], "house"), (["furni"], "sofa"), (["shop"], "cart"),
            (["cloth", "apparel"], "shirt"), (["gift"], "gift"), (["personal care", "salon", "groom"], "scissors"),
            (["educat", "school", "tuition"], "cap"), (["book"], "book"), (["entertain", "game"], "gamepad"),
            (["music"], "music"), (["movie", "film"], "film"), (["pet"], "paw"),
            (["phone", "mobile"], "phone"), (["electronic", "gadget", "laptop"], "laptop"),
            (["salary", "wage", "payroll"], "brief"), (["freelance"], "edit"), (["business"], "wallet"),
            (["invest", "dividend", "interest"], "trend"), (["bonus"], "trophy"), (["allowance"], "coins")
        };
        return rules.FirstOrDefault(rule => rule.Words.Any(n.Contains)).Key
            ?? (n.StartsWith("other", StringComparison.Ordinal) ? "dots" : type == "Income" ? "coins" : "tag");
    }

    public static ActivityPageProjection Build(IEnumerable<TransactionResponse> source, string? type,
        int? categoryId, DateOnly? fromDate, DateOnly? toDate, string? search, DateOnly? day,
        int page, int pageSize, string? amountOrder = null)
    {
        var period = source.Where(row => (type is null || row.Type == type)
            && (categoryId is null || row.CategoryId == categoryId)
            && (fromDate is null || row.TransactionDate >= fromDate)
            && (toDate is null || row.TransactionDate <= toDate)
            && MatchesSearch(row, search)).ToArray();

        var daily = period.GroupBy(row => row.TransactionDate)
            .ToDictionary(group => group.Key, group => group.Sum(row => row.AmountBase));
        var visible = day is null ? period : period.Where(row => row.TransactionDate == day).ToArray();
        var ordered = amountOrder switch
        {
            "Largest" => visible.OrderByDescending(row => row.AmountBase).ThenByDescending(row => row.TransactionId).ToArray(),
            "Smallest" => visible.OrderBy(row => row.AmountBase).ThenBy(row => row.TransactionId).ToArray(),
            _ => visible.OrderByDescending(row => row.TransactionDate).ThenByDescending(row => row.TransactionId).ToArray()
        };
        var safePage = Math.Max(1, page);

        return new ActivityPageProjection(
            ordered.LongLength,
            period.LongLength,
            period.Where(row => row.Type == "Income").Sum(row => row.AmountBase),
            period.Where(row => row.Type == "Expense").Sum(row => row.AmountBase),
            daily,
            ordered.Skip((safePage - 1) * pageSize).Take(pageSize).ToArray())
        {
            DailyNet = period.GroupBy(row => row.TransactionDate).ToDictionary(group => group.Key, group => group.Sum(row => row.Type == "Income" ? row.AmountBase : -row.AmountBase)),
            DailyCount = period.GroupBy(row => row.TransactionDate).ToDictionary(group => group.Key, group => group.Count())
        };
    }

    public static bool MatchesSearch(TransactionResponse row, string? query)
    {
        if (string.IsNullOrWhiteSpace(query)) return true;
        var value = query.Trim();
        if (row.CategoryName.Contains(value, StringComparison.OrdinalIgnoreCase)
            || (row.Description?.Contains(value, StringComparison.OrdinalIgnoreCase) ?? false)) return true;

        // Mirrors Android Activity search: amount digits are searchable after removing currency marks,
        // grouping commas, and spaces. Search remains on the server over the complete matching history.
        var numeric = value.Replace("₱", string.Empty, StringComparison.Ordinal)
            .Replace(",", string.Empty, StringComparison.Ordinal)
            .Where(character => !char.IsWhiteSpace(character)).ToArray();
        if (numeric.Length == 0 || numeric.Any(character => !char.IsDigit(character) && character != '.')) return false;
        var digits = new string(numeric);
        var raw = row.Amount.ToString("0.################", CultureInfo.InvariantCulture);
        var formatted = row.Amount.ToString("N2", CultureInfo.InvariantCulture).Replace(",", string.Empty, StringComparison.Ordinal);
        var baseAmount = row.AmountBase.ToString("0.################", CultureInfo.InvariantCulture);
        var baseFormatted = row.AmountBase.ToString("F2", CultureInfo.InvariantCulture);
        return raw.Contains(digits, StringComparison.Ordinal) || formatted.Contains(digits, StringComparison.Ordinal)
            || baseAmount.Contains(digits, StringComparison.Ordinal) || baseFormatted.Contains(digits, StringComparison.Ordinal);
    }
}

public sealed record ActivityPageProjection(long TotalCount, long SummaryCount, decimal IncomeTotal,
    decimal ExpenseTotal, IReadOnlyDictionary<DateOnly, decimal> DailyVolume,
    IReadOnlyList<TransactionResponse> Items)
{
    public IReadOnlyDictionary<DateOnly, decimal> DailyNet { get; init; } = new Dictionary<DateOnly, decimal>();
    public IReadOnlyDictionary<DateOnly, int> DailyCount { get; init; } = new Dictionary<DateOnly, int>();
}
