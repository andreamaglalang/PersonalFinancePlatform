using Microsoft.AspNetCore.Mvc.ModelBinding;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.ViewModels;

/// <summary>
/// Member notification inbox state. Read-only: there is no mark-as-read endpoint, so the only control is the
/// unread-only filter plus paging.
/// </summary>
public sealed class MemberNotificationIndexViewModel
{
    public bool UnreadOnly { get; set; }
    public int Page { get; set; } = 1;

    [BindNever] public NotificationPage? Results { get; set; }
    [BindNever] public string? Notice { get; set; }
    [BindNever] public string? Error { get; set; }

    /// <summary>Accessible summary of the page, e.g. "3 of 7 notifications". Never a bare number.</summary>
    public string? Summary => Results is null
        ? null
        : $"{Results.Items.Count} of {Results.TotalCount} notification{(Results.TotalCount == 1 ? "" : "s")}";
}
