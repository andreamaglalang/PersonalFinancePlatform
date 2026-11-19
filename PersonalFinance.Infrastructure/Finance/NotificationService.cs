using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance.Contracts
{
    public sealed class NotificationQuery
    {
        /// <summary>Only notifications not yet marked as read.</summary>
        public bool UnreadOnly { get; set; }
        public int Page { get; set; } = 1;
        public int PageSize { get; set; } = 20;
    }

    /// <summary>Notification as shown to its owner. Internal owner IDs, deduplication keys and delivery metadata are never exposed.</summary>
    public sealed record NotificationResponse(int NotificationId, string Type, string? ReferenceType, int? ReferenceId,
        string Message, bool IsRead, DateTime CreatedAtUtc);
}

namespace PersonalFinance.Infrastructure.Finance
{
    public interface INotificationService
    {
        Task<FinanceResult<PagedResponse<NotificationResponse>>> ListAsync(NotificationQuery query, CancellationToken ct);
    }

    /// <summary>Read-only access to the signed-in user's own notifications (bill reminders, budget alerts, monthly summaries).</summary>
    public sealed class NotificationService(ApplicationDbContext db, ICurrentUser current, IFinancialWriteScope writes)
        : FinanceServiceBase(db, current, writes), INotificationService
    {
        public Task<FinanceResult<PagedResponse<NotificationResponse>>> ListAsync(NotificationQuery query, CancellationToken ct) => Guard(async () =>
        {
            var userId = UserId;
            if (!FinanceValidation.ValidPage(query.Page, query.PageSize))
                throw new FinanceException(400, "Invalid notification filters or pagination.");
            var rows = Db.Notifications.AsNoTracking().Where(n => n.UserId == userId);
            if (query.UnreadOnly) rows = rows.Where(n => !n.IsRead);
            var total = await rows.LongCountAsync(ct);
            var items = await rows.OrderByDescending(n => n.CreatedAtUtc).ThenByDescending(n => n.NotificationId)
                .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize)
                .Select(n => new NotificationResponse(n.NotificationId, n.Type, n.ReferenceType, n.ReferenceId, n.Message, n.IsRead, n.CreatedAtUtc))
                .ToListAsync(ct);
            return FinanceResult<PagedResponse<NotificationResponse>>.Success(new(items, query.Page, query.PageSize, total));
        });
    }
}
