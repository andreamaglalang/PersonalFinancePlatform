using System.Data;
using System.Data.Common;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.SavingsSqlTests;

// Dormant fixture. No target implementation, connection strings, provisioning, migrations,
// automatic teardown, ambient transaction, InMemory provider or simulated rowversions.
internal sealed class SavingsSqlFixture
{
    private readonly ISavingsSqlVerifiedTarget target;
    public SavingsSqlClock Clock { get; } = new();

    public SavingsSqlFixture(ISavingsSqlVerifiedTarget target)
    {
        ArgumentNullException.ThrowIfNull(target);
        if (target.RunId == Guid.Empty || target.DatabaseGuid == Guid.Empty
            || target.DatabaseName != "PersonalFinanceSavingsTests_" + target.RunId.ToString("N"))
            throw new InvalidOperationException("A bound disposable SQL test target is required.");
        this.target = target;
    }

    public async Task<SavingsSqlSession> OpenAsync(int userId, CancellationToken ct,
        SavingsSqlFailureInterceptor? observer = null, bool administrator = false)
    {
        if (System.Transactions.Transaction.Current is not null)
            throw new InvalidOperationException("Ambient test transactions are prohibited.");
        var connection = await target.OpenVerifiedConnectionAsync(ct);
        try
        {
            var policy = new SqlConnectionStringBuilder(connection.ConnectionString);
            if (connection.State != ConnectionState.Open || connection.Database != target.DatabaseName
                || !policy.IntegratedSecurity || policy.Pooling || policy.Enlist
                || policy.MultipleActiveResultSets || policy.ConnectRetryCount != 0
                || !policy.Encrypt.Equals(SqlConnectionEncryptOption.Mandatory) || !policy.TrustServerCertificate
                || policy.ConnectTimeout is < 1 or > 10 || !string.IsNullOrEmpty(policy.AttachDBFilename)
                || !string.IsNullOrEmpty(policy.FailoverPartner)
                || !policy.DataSource.StartsWith(@"np:\\", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Verified SQL connection policy was not satisfied.");
            await target.RevalidateAsync(connection, null, ct);
            var options = new DbContextOptionsBuilder<ApplicationDbContext>()
                .UseSqlServer(connection, sql => sql.CommandTimeout(10))
                .AddInterceptors(new IdentityInterceptor(target));
            if (observer is not null) options.AddInterceptors(observer);
            var db = new ApplicationDbContext(options.Options);
            return new SavingsSqlSession(connection, db,
                new SavingsGoalService(db, new SavingsSqlCurrentUser(userId, administrator),
                    new SqlFinancialWriteScope(db), Clock));
        }
        catch { await connection.DisposeAsync(); throw; }
    }

    public async Task<int> NewUserAsync(CancellationToken ct)
    {
        await using var session = await OpenAsync(0, ct);
        var email = $"savings-sql-{target.RunId:N}-{Guid.NewGuid():N}@example.test";
        var user = new ApplicationUser
        {
            FullName = "Synthetic Savings SQL User", UserName = email, NormalizedUserName = email.ToUpperInvariant(),
            Email = email, NormalizedEmail = email.ToUpperInvariant(), BaseCurrency = "PHP",
            CreatedAtUtc = Clock.GetUtcNow().UtcDateTime, SecurityStamp = Guid.NewGuid().ToString("N"),
            ConcurrencyStamp = Guid.NewGuid().ToString("N"), LockoutEnabled = true
        };
        session.Db.Users.Add(user);
        await session.Db.SaveChangesAsync(ct);
        return user.Id;
    }

    // Each future runner registration gets a bounded cancellation token. No runner invokes this today.
    public static Task CheckAsync(string name, Func<CancellationToken, Task> action,
        Func<string, Func<Task>, Task> check, CancellationToken ct) => check(name, async () =>
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(60));
        await action(timeout.Token);
    });

    private sealed class IdentityInterceptor(ISavingsSqlVerifiedTarget target) : DbCommandInterceptor
    {
        private Task Verify(DbCommand command, CancellationToken ct) => command.Connection is SqlConnection connection
            ? target.RevalidateAsync(connection, command.Transaction as SqlTransaction, ct)
            : throw new InvalidOperationException("Only the bound SQL connection is permitted.");

        public override InterceptionResult<DbDataReader> ReaderExecuting(DbCommand command,
            CommandEventData eventData, InterceptionResult<DbDataReader> result) =>
            throw new InvalidOperationException("Synchronous SQL commands are not supported by this fixture.");

        public override InterceptionResult<int> NonQueryExecuting(DbCommand command,
            CommandEventData eventData, InterceptionResult<int> result) =>
            throw new InvalidOperationException("Synchronous SQL commands are not supported by this fixture.");

        public override InterceptionResult<object> ScalarExecuting(DbCommand command,
            CommandEventData eventData, InterceptionResult<object> result) =>
            throw new InvalidOperationException("Synchronous SQL commands are not supported by this fixture.");

        public override async ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<DbDataReader> result, CancellationToken cancellationToken = default)
        { await Verify(command, cancellationToken); return result; }

        public override async ValueTask<InterceptionResult<int>> NonQueryExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
        { await Verify(command, cancellationToken); return result; }

        public override async ValueTask<InterceptionResult<object>> ScalarExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<object> result, CancellationToken cancellationToken = default)
        { await Verify(command, cancellationToken); return result; }
    }
}

internal sealed class SavingsSqlSession(SqlConnection connection, ApplicationDbContext db,
    SavingsGoalService service) : IAsyncDisposable
{
    public ApplicationDbContext Db { get; } = db;
    public SavingsGoalService Service { get; } = service;
    public async ValueTask DisposeAsync()
    {
        try { await Db.DisposeAsync(); }
        finally { await connection.DisposeAsync(); }
    }
}
