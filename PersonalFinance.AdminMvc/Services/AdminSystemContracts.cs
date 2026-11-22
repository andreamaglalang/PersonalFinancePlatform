namespace PersonalFinance.AdminMvc.Services;

public sealed record PlatformEvent(string Action, DateTime OccurredAtUtc);
public sealed record PlatformActivity(long RegistrationsLastSevenDays, long TransactionCount,
    bool SignInRecordingAvailable, IReadOnlyList<PlatformEvent> Events);
public sealed record AuditLogEntry(long Id, int ActorId, string Action, string TargetType, int TargetId, DateTime OccurredAtUtc);
public sealed record AuditLogPage(bool RecordingAvailable, IReadOnlyList<AuditLogEntry> Items,
    int Page, int PageSize, long TotalCount);
public sealed record SystemHealth(bool DatabaseHealthy, DateTimeOffset CheckedAtUtc);
