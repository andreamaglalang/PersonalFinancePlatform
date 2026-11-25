using Microsoft.Data.SqlClient;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.SavingsSqlTests;

// Intentionally has no implementation. A future separately approved live guard must supply
// this capability AFTER approval and actual server/database GUID/file/run-marker verification.
// Neither an offline manifest nor a raw connection string implements this contract.
internal interface ISavingsSqlVerifiedTarget
{
    Guid RunId { get; }
    Guid DatabaseGuid { get; }
    string DatabaseName { get; }
    // Must use the frozen approved existing pipe, bounded timeouts, no implicit startup/retries.
    Task<SqlConnection> OpenVerifiedConnectionAsync(CancellationToken ct);
    // Must verify the same connection's full bound identity without using EF/interceptor recursion.
    // Verification SELECT commands must enlist in the supplied local transaction when non-null;
    // they must never begin/commit/rollback a transaction or change the approved target binding.
    Task RevalidateAsync(SqlConnection connection, SqlTransaction? transaction, CancellationToken ct);
}

internal sealed class SavingsSqlClock : TimeProvider
{
    public static readonly DateTimeOffset FixedUtc = new(2026, 11, 3, 0, 15, 0, TimeSpan.Zero);
    public override DateTimeOffset GetUtcNow() => FixedUtc;
    public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;
    public DateOnly Today => DateOnly.FromDateTime(GetUtcNow().UtcDateTime);
}

internal sealed class SavingsSqlCurrentUser(int userId, bool administrator = false) : ICurrentUser
{
    public int? UserId => userId;
    public bool IsAdministrator => administrator;
    public bool Owns(int resourceOwnerId) => resourceOwnerId == userId;
}

internal static class SavingsSqlAssert
{
    public static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    public static T Value<T>(FinanceResult<T> result, int status = 200) where T : class
    {
        Require(result.Succeeded && result.StatusCode == status && result.Value is not null,
            $"Expected successful finance response with status {status}.");
        return result.Value!;
    }

    public static void Status<T>(FinanceResult<T> result, int status) =>
        Require(result.StatusCode == status && (status < 400 || !result.Succeeded),
            $"Expected finance status {status}.");

    public static void NativeVersion(string value) =>
        Require(Convert.FromBase64String(value).Length == 8, "Expected native eight-byte rowversion.");
}
