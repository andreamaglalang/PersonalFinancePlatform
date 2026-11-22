using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Controllers;

internal static class PlatformContentSchema
{
    public static async Task<bool> AvailableAsync(ApplicationDbContext db, CancellationToken ct) =>
        !db.Database.IsSqlServer() || await db.Database.SqlQueryRaw<bool>("""
            SELECT CAST(CASE WHEN OBJECT_ID(N'dbo.PlatformAnnouncements', N'U') IS NOT NULL
                AND OBJECT_ID(N'dbo.SupportRequests', N'U') IS NOT NULL
                AND OBJECT_ID(N'dbo.SupportReplies', N'U') IS NOT NULL
                AND OBJECT_ID(N'dbo.PlatformSettings', N'U') IS NOT NULL
                THEN 1 ELSE 0 END AS bit) AS [Value]
            """).SingleAsync(ct);
}
