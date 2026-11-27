using System.Text.Json;

namespace PersonalFinance.AdminMvc.Services;

/// <summary>
/// Turns a rejected API response into a specific, safe message. API text is never echoed as-is: only messages in
/// this reviewed allowlist (and the API's password-requirement sentence, rebuilt from known parts) are shown, each in
/// the site's own wording. Anything else returns null, so callers keep their generic status message.
/// </summary>
public static class ApiMessages
{
    private const int MaxBody = 16 * 1024;

    private static readonly Dictionary<string, string> Known = new(StringComparer.Ordinal)
    {
        // Amounts (FinanceValidation.AmountMessages)
        ["Enter an amount greater than zero."] = "Enter an amount greater than zero.",
        ["Amounts can have no more than two decimal places."] = "Amounts can have no more than two decimal places.",
        ["That amount is too large."] = "That amount is too large.",
        ["The converted amount exceeds the supported monetary range."] = "The converted amount is too large.",
        // Currency
        ["The currency is not supported by the exchange-rate provider."] = "That currency isn't supported for conversion.",
        ["The requested currency or historical rate is unavailable from the provider."] = "No exchange rate is available for that currency and date.",
        // Categories, budgets, goals, bills
        ["An active, accessible expense category is required."] = "Choose an active expense category.",
        ["A category with the same name and type already exists."] = "A category with this name and type already exists. Choose a different name.",
        ["A budget already exists for this category and month."] = "A budget already exists for this category and month.",
        ["Contribution dates cannot be later than today (Philippine time)."] = "Contribution dates can't be later than today.",
        ["A goal with protected financial history cannot be deleted."] = "This goal has contributions, so it can't be deleted.",
        ["Cancelled goals are financially immutable."] = "Cancelled goals can't be changed.",
        ["This bill has payment history and cannot be deleted. Deactivate it instead."] = "This bill has payment history and can't be deleted. Deactivate it instead.",
        ["Bill payments require a controlled reversal and cannot be deleted."] = "Bill payments can't be deleted.",
        ["The operation conflicts with existing related records."] = "This record is linked to other records, so this change isn't allowed.",
        // Concurrency (stale versions)
        ["This transaction was changed elsewhere. Reload it and try again."] = "This transaction was updated elsewhere. Reload before editing.",
        ["Transaction changed. Reload and retry."] = "This transaction was updated elsewhere. Reload before editing.",
        ["Transaction changed during the request. Reload and retry."] = "This transaction was updated elsewhere. Reload before editing.",
        ["Budget changed during the request. Reload and retry."] = "This budget was updated elsewhere. Reload and try again.",
        ["The bill changed during the request. Reload and retry."] = "This bill was updated elsewhere. Reload and try again.",
        ["Contribution history changed. Reload and retry."] = "This goal's contributions were updated elsewhere. Reload and try again.",
        ["Profile changed. Reload before saving."] = "Your profile was updated elsewhere. Reload before saving.",
        // Idempotency: the same request identity was reused for different details.
        ["ClientId has already been accepted with a different contribution payload."] = "This contribution was already recorded with different details. Reload and check before trying again.",
        ["This ClientId was already used for different transaction data."] = "This transaction was already recorded with different details. Reload and check before trying again.",
        // Account and registration (field messages from the API contracts)
        ["Your current password is incorrect."] = "Your current password is incorrect.",
        ["Choose a new password that is different from your current password."] = "Choose a new password that is different from your current password.",
        ["Passwords do not match."] = "Passwords do not match.",
        ["Enter a valid email address."] = "Enter a valid email address.",
        ["Enter your full name."] = "Enter your full name.",
        ["Enter your full name (up to 100 characters)."] = "Enter your full name (up to 100 characters).",
    };

    private static readonly HashSet<string> PasswordParts = new(StringComparer.Ordinal)
    {
        "at least 12 characters", "no more than 128 characters", "an uppercase letter", "a lowercase letter",
        "a number", "a special character, such as ! or #", "at least 4 different characters",
    };

    /// <summary>The site's wording for a known API message, or null.</summary>
    public static string? Translate(string? apiMessage)
    {
        if (apiMessage is null) return null;
        if (Known.TryGetValue(apiMessage, out var known)) return known;
        return IsPasswordRequirements(apiMessage) ? apiMessage : null;
    }

    /// <summary>"Password must contain A, B and C." where every part is a known requirement.</summary>
    public static bool IsPasswordRequirements(string message)
    {
        const string prefix = "Password must contain ";
        if (!message.StartsWith(prefix, StringComparison.Ordinal) || !message.EndsWith('.')) return false;
        var body = message[prefix.Length..^1];
        var remaining = new List<string>();
        // Parts are joined with ", " and a final " and "; one part itself contains ", " so match greedily by known parts.
        while (body.Length > 0)
        {
            var part = PasswordParts.Where(p => body.StartsWith(p, StringComparison.Ordinal)).MaxBy(p => p.Length);
            if (part is null) return false;
            remaining.Add(part);
            body = body[part.Length..];
            if (body.StartsWith(", ", StringComparison.Ordinal)) body = body[2..];
            else if (body.StartsWith(" and ", StringComparison.Ordinal)) body = body[5..];
            else if (body.Length > 0) return false;
        }
        return remaining.Count > 0 && remaining.Distinct().Count() == remaining.Count;
    }

    /// <summary>Known messages from a ProblemDetails/ValidationProblemDetails body, keyed by field ("" = form-level).</summary>
    public static async Task<IReadOnlyDictionary<string, string[]>> ReadFieldsAsync(HttpResponseMessage response, CancellationToken ct)
    {
        var fields = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (response.Content.Headers.ContentType?.MediaType is not ("application/problem+json" or "application/json")) return fields;
            if (response.Content.Headers.ContentLength is > MaxBody) return fields;
            var body = await response.Content.ReadAsByteArrayAsync(ct);
            if (body.Length > MaxBody) return fields;
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return fields;
            if (root.TryGetProperty("errors", out var errors) && errors.ValueKind == JsonValueKind.Object)
                foreach (var field in errors.EnumerateObject())
                {
                    if (field.Value.ValueKind != JsonValueKind.Array) continue;
                    var messages = field.Value.EnumerateArray()
                        .Select(m => m.ValueKind == JsonValueKind.String ? Translate(m.GetString()) : null)
                        .OfType<string>().Distinct().ToArray();
                    if (messages.Length > 0) fields[field.Name] = messages;
                }
            if (root.TryGetProperty("title", out var title) && title.ValueKind == JsonValueKind.String
                && Translate(title.GetString()) is { } formMessage)
                fields[""] = [formMessage];
        }
        catch (Exception ex) when (ex is JsonException or HttpRequestException or IOException or InvalidOperationException) { }
        return fields;
    }

    /// <summary>All known messages as one sentence group for a form-level notice, or null.</summary>
    public static async Task<string?> ReadAsync(HttpResponseMessage response, CancellationToken ct)
    {
        var fields = await ReadFieldsAsync(response, ct);
        var all = fields.Values.SelectMany(m => m).Distinct().ToArray();
        return all.Length == 0 ? null : string.Join(" ", all);
    }
}
