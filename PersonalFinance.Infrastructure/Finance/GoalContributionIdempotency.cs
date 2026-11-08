using PersonalFinance.Domain.Entities;

namespace PersonalFinance.Infrastructure.Finance;

/// <summary>Versioned comparison against accepted originals, never the editable allocation.</summary>
public static class GoalContributionIdempotency
{
    public const short CanonicalVersion = 1;

    // Version 1 freezes this whitespace set rather than depending on future Unicode trim changes.
    private static readonly char[] BoundaryWhitespace =
    [
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0', '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
        '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
    ];

    public static string? CanonicalizeNote(string? note)
    {
        var value = note?.Trim(BoundaryWhitespace);
        return string.IsNullOrEmpty(value) ? null : value;
    }

    public static bool Matches(GoalContributionRequest accepted, int goalId, decimal amount,
        DateOnly contributedOn, string? note)
    {
        if (accepted.CanonicalVersion != CanonicalVersion)
            throw new FinanceException(409, "The accepted request uses an unsupported canonical version.");
        return accepted.GoalId == goalId && accepted.OriginalAmount == amount
            && accepted.OriginalContributedOn == contributedOn
            && string.Equals(accepted.OriginalNote, CanonicalizeNote(note), StringComparison.Ordinal);
    }
}
