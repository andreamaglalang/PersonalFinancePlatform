using System.ComponentModel.DataAnnotations;

namespace PersonalFinance.Infrastructure;

/// <summary>
/// Single-line labels (names) may not contain control characters such as line breaks, tabs or NUL. Whitespace at the
/// ends is ignored because every name is trimmed before it is stored. Descriptions and notes are not restricted.
/// </summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class SingleLineTextAttribute : ValidationAttribute
{
    public SingleLineTextAttribute() : base("Use a single line without control characters.") { }
    public override bool IsValid(object? value) => value is not string text || !text.Trim().Any(char.IsControl);
}
