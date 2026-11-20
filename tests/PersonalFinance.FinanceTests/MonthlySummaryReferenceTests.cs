using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Jobs;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.FinanceTests;

/// <summary>
/// Regression checks for CK_Notifications_Reference ("ReferenceType and ReferenceId are both NULL or both set") on
/// job-generated notifications. The in-memory provider does not enforce SQL check constraints, so these checks attach a
/// simulation that raises the same SQL error (547) SQL Server would. Real SQL Server transactions are NOT executed here;
/// rollback on SQL Server comes from SqlFinancialWriteScope, which commits only after the whole operation returns.
/// </summary>
internal static class MonthlySummaryReferenceTests
{
    private static void Assert(bool condition, string detail = "Assertion failed") { if (!condition) throw new Exception(detail); }

    private static bool SatisfiesReferenceRule(Notification n) => (n.ReferenceType is null) == (n.ReferenceId is null);

    /// <summary>Raises SQL error 547 on SaveChanges for any added notification that would violate CK_Notifications_Reference.</summary>
    private static void EnforceReferenceRule(ApplicationDbContext db) => db.SavingChanges += (_, _) =>
    {
        if (db.ChangeTracker.Entries<Notification>().Any(e => e.State == EntityState.Added && !SatisfiesReferenceRule(e.Entity)))
            throw new DbUpdateException("Simulated CK_Notifications_Reference violation.", SyntheticSqlError.Create(547));
    };

    /// <summary>Wraps the test write scope, recording each call's lock resources and whether code is running inside it.</summary>
    private sealed class RecordingScope(IFinancialWriteScope inner) : IFinancialWriteScope
    {
        public List<string[]> Calls { get; } = [];
        public bool InScope { get; private set; }
        public Func<string[], Exception?>? FailBefore { get; init; }
        public async Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
        {
            var r = resources.ToArray();
            Calls.Add(r);
            if (FailBefore?.Invoke(r) is { } failure) throw failure;
            return await inner.ExecuteAsync(r, async token =>
            {
                InScope = true;
                try { return await operation(token); } finally { InScope = false; }
            }, ct);
        }
    }

    /// <summary>Runs one job instance on a fresh scope with the reference rule enforced and the recording write scope.</summary>
    private static async Task<T> Job<T>(FinanceFixture f, RecordingScope writes, Func<FinanceJobs, Task<T>> run, Action<ApplicationDbContext>? hook = null)
    {
        using var scope = f.Services.CreateScope();
        var sp = scope.ServiceProvider;
        var db = sp.GetRequiredService<ApplicationDbContext>();
        EnforceReferenceRule(db);
        hook?.Invoke(db);
        return await run(new FinanceJobs(db, sp.GetRequiredService<IBudgetUsageService>(), writes, sp.GetRequiredService<TimeProvider>()));
    }

    private static RecordingScope Writes(FinanceFixture f, Func<string[], Exception?>? failBefore = null) =>
        new(f.Services.GetRequiredService<IFinancialWriteScope>()) { FailBefore = failBefore };

    private static async Task Tx(FinanceFixture f, int user, int category, string type, decimal amount, string date)
    {
        var r = await f.SendAsync("POST", "/api/transactions", new CreateTransactionRequest { ClientId = Guid.NewGuid(), CategoryId = category, Type = type,
            Amount = amount, CurrencyCode = "PHP", TransactionDate = DateOnly.Parse(date), Description = "t" }, f.Issue(user));
        Assert(r.Status == 201, r.Body);
    }

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Reference rule simulation rejects ReferenceType without ReferenceId (pre-fix shape)", async f =>
        {
            using var scope = f.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            EnforceReferenceRule(db);
            db.Notifications.Add(new Notification { UserId = 1, Type = FinanceJobs.MonthlySummaryType, ReferenceType = "MonthlySummary",
                Message = "m", DeduplicationKey = "summary:2026-10" });
            var rejected = false;
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException ex) { rejected = ex.InnerException is Microsoft.Data.SqlClient.SqlException { Number: 547 }; }
            Assert(rejected, "violation was not rejected with SQL error 547");
        });

        await check("Monthly summary notification references its summary and satisfies CK_Notifications_Reference", async f =>
        {
            await Tx(f, 1, 1, "Income", 100m, "2026-10-05");
            await Tx(f, 1, 2, "Expense", 40m, "2026-10-06");
            var writes = Writes(f);
            var savesOutsideScope = 0;
            Assert(await Job(f, writes, j => j.GenerateMonthlySummariesAsync(default),
                db => db.SavedChanges += (_, _) => { if (!writes.InScope) savesOutsideScope++; }) == 1);
            Assert(writes.Calls.Count == 1 && writes.Calls[0].SequenceEqual([FinanceValidation.UserLock(1)]), "one write scope under the user's lock");
            Assert(savesOutsideScope == 0, "both inserts must be saved inside the single write scope (one SQL transaction)");
            await f.DbAsync(async db =>
            {
                var s = await db.MonthlySummaries.SingleAsync();
                var n = await db.Notifications.SingleAsync();
                Assert(n.Type == FinanceJobs.MonthlySummaryType && n.ReferenceType == "MonthlySummary" && n.ReferenceId == s.SummaryId,
                    $"notification must point at summary {s.SummaryId}, got {n.ReferenceId}");
                Assert(n.UserId == s.UserId && n.DeduplicationKey == "summary:2026-10");
                Assert(s.TotalIncome == 100m && s.TotalExpense == 40m && s.NetCashFlow == 60m);
                Assert(await db.Notifications.AllAsync(x => (x.ReferenceType == null) == (x.ReferenceId == null)));
            });
        });

        await check("Monthly summary re-run creates no additional summaries or notifications", async f =>
        {
            await Tx(f, 1, 2, "Expense", 25m, "2026-10-10");
            Assert(await Job(f, Writes(f), j => j.GenerateMonthlySummariesAsync(default)) == 1);
            var rerun = Writes(f);
            Assert(await Job(f, rerun, j => j.GenerateMonthlySummariesAsync(default)) == 0);
            Assert(rerun.Calls.Count == 0, "an existing summary is skipped before any write scope is opened");
            await f.DbAsync(async db => Assert(await db.MonthlySummaries.CountAsync() == 1 && await db.Notifications.CountAsync() == 1));
        });

        await check("Monthly summary notification failure surfaces from inside the write scope (not swallowed)", async f =>
        {
            await Tx(f, 1, 2, "Expense", 25m, "2026-10-10");
            var writes = Writes(f);
            var failedInScope = false;
            var surfaced = false;
            try
            {
                await Job(f, writes, j => j.GenerateMonthlySummariesAsync(default), db => db.SavingChanges += (_, _) =>
                {
                    if (!db.ChangeTracker.Entries<Notification>().Any(e => e.State == EntityState.Added)) return;
                    failedInScope = writes.InScope;
                    throw new DbUpdateException("Simulated notification insert failure.", SyntheticSqlError.Create(547));
                });
            }
            catch (DbUpdateException ex) { surfaced = ex.InnerException is Microsoft.Data.SqlClient.SqlException { Number: 547 }; }
            Assert(surfaced, "a non-duplicate failure must propagate, not be reported as success or 'already done'");
            Assert(failedInScope, "the failing notification insert happens inside the scope, so SQL Server rolls back the summary with it");
        });

        await check("Monthly summary duplicate key from a concurrent run creates nothing and continues with other users", async f =>
        {
            await Tx(f, 1, 2, "Expense", 25m, "2026-10-10");
            await Tx(f, 2, 3, "Expense", 30m, "2026-10-11");
            var writes = Writes(f, r => r.Contains(FinanceValidation.UserLock(1))
                ? new DbUpdateException("Simulated concurrent duplicate.", SyntheticSqlError.Create(2627)) : null);
            Assert(await Job(f, writes, j => j.GenerateMonthlySummariesAsync(default)) == 1, "only user 2 is created");
            await f.DbAsync(async db =>
            {
                var s = await db.MonthlySummaries.SingleAsync();
                var n = await db.Notifications.SingleAsync();
                Assert(s.UserId == 2 && n.UserId == 2 && n.ReferenceId == s.SummaryId);
            });
        });

        await check("Bill reminder and budget alert notifications satisfy CK_Notifications_Reference", async f =>
        {
            var bill = await f.SendAsync("POST", "/api/recurring-bills", new { name = "Internet", categoryId = 2, amount = 999m, currencyCode = "PHP",
                reminderDaysBefore = 3, frequency = "Monthly", firstDueDate = "2026-11-05" }, f.Issue(1));
            Assert(bill.Status == 201, bill.Body);
            Assert((await f.SendAsync("POST", "/api/budgets", new CreateBudgetRequest { CategoryId = 2, PeriodMonth = new(2026, 11, 1),
                LimitAmount = 10m, AlertThresholdPct = 80 }, f.Issue(1))).Status == 201);
            await Tx(f, 1, 2, "Expense", 9m, "2026-11-02");
            Assert(await Job(f, Writes(f), j => j.SendBillRemindersAsync(default)) == 1);
            Assert(await Job(f, Writes(f), j => j.SendBudgetAlertsAsync(default)) == 1);
            await f.DbAsync(async db =>
            {
                var rows = await db.Notifications.ToListAsync();
                Assert(rows.Count == 2 && rows.All(SatisfiesReferenceRule) && rows.All(n => n.ReferenceId is not null));
            });
        });
    }
}
