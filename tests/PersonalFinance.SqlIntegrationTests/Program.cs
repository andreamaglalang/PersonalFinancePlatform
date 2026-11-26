using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.AspNetCore.Identity;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;
using PersonalFinance.SqlIntegrationTests;

// Opt-in real SQL Server checks. Requires OWLI_SQL_TEST_SERVER: a connection to master on an approved
// disposable/local server. The harness creates, migrates and drops its own PersonalFinanceSqlTests_* catalog.
const string Variable = "OWLI_SQL_TEST_SERVER";
var passed = 0; var failed = 0;
void Assert(bool value, string message) { if (!value) throw new Exception(message); }
async Task Check(string name, Func<Task> test)
{
    try { await test(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.WriteLine("FAIL " + name + ": " + ex.GetType().Name + ": " + ex.Message); }
}

// Offline guard checks always run.
await Check("Reject missing server", () => { Assert(DisposableSqlTarget.Reject(null) is not null, "accepted"); return Task.CompletedTask; });
await Check("Reject a finance catalog target", () => { Assert(DisposableSqlTarget.Reject("Server=localhost;Database=PersonalFinanceDb;User ID=x;Password=y") is not null, "accepted"); return Task.CompletedTask; });
await Check("Reject Hangfire catalog target", () => { Assert(DisposableSqlTarget.Reject("Server=localhost;Database=OWLI_Hangfire;User ID=x;Password=y") is not null, "accepted"); return Task.CompletedTask; });
await Check("Reject AttachDbFilename", () => { Assert(DisposableSqlTarget.Reject("Server=localhost;Database=master;AttachDbFilename=x.mdf;Integrated Security=true") is not null, "accepted"); return Task.CompletedTask; });
await Check("Accept master target", () => { Assert(DisposableSqlTarget.Reject("Server=localhost;Database=master;User ID=x;Password=y") is null, "rejected"); return Task.CompletedTask; });

var server = Environment.GetEnvironmentVariable(Variable);
if (string.IsNullOrWhiteSpace(server))
{
    Console.WriteLine($"SKIP live SQL checks: {Variable} is not configured.");
    Console.WriteLine($"Passed: {passed}; Failed: {failed}");
    return failed == 0 ? 0 : 1;
}

await using (var target = await DisposableSqlTarget.CreateAsync(server))
{
    Console.WriteLine("Disposable catalog created (" + DisposableSqlTarget.Prefix + "<run>).");
    var currentUser = new TestUser();
    var services = new ServiceCollection();
    var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
    {
        ["ConnectionStrings:DefaultConnection"] = target.ConnectionString,
        ["Hangfire:Enabled"] = "false"
    }).Build();
    services.AddLogging();
    services.AddInfrastructure(configuration);
    services.AddDataProtection();
    services.AddSingleton<ICurrentUser>(currentUser);
    services.RemoveAll<IFrankfurterClient>();
    services.AddSingleton<IFrankfurterClient>(new FixedFx());
    await using var provider = services.BuildServiceProvider();

    async Task<T> InScope<T>(Func<IServiceProvider, Task<T>> body)
    { await using var scope = provider.CreateAsyncScope(); return await body(scope.ServiceProvider); }

    await Check("All migrations apply to an empty catalog", async () =>
    {
        await InScope(async sp => { await sp.GetRequiredService<ApplicationDbContext>().Database.MigrateAsync(); return 0; });
        var pending = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Database.GetPendingMigrationsAsync());
        var applied = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Database.GetAppliedMigrationsAsync());
        Assert(!pending.Any() && applied.Count() >= 5, "migrations incomplete");
    });

    int userA = 0, userB = 0;
    await Check("Synthetic members are created with Identity", async () =>
    {
        userA = await CreateUser(provider, "sql.a@example.test");
        userB = await CreateUser(provider, "sql.b@example.test");
        Assert(userA > 0 && userB > 0 && userA != userB, "users");
    });

    int expenseCategory = 0;
    await Check("Category create persists for its owner only", async () =>
    {
        currentUser.Id = userA;
        var created = await InScope(sp => sp.GetRequiredService<ICategoryService>().CreateAsync(
            new CategoryWriteRequest { Name = "Groceries", Type = "Expense" }, false, default));
        Assert(created.Succeeded, created.Error ?? "");
        expenseCategory = created.Value!.CategoryId;
        currentUser.Id = userB;
        var other = await InScope(sp => sp.GetRequiredService<ICategoryService>().GetAsync(expenseCategory, default));
        Assert(other.StatusCode == 404, "user B saw user A's category");
    });

    await Check("Concurrent identical transaction creates store exactly one row", async () =>
    {
        currentUser.Id = userA;
        var request = Expense(expenseCategory, Guid.NewGuid(), 125.50m);
        var results = await Task.WhenAll(Enumerable.Range(0, 4).Select(_ =>
            InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(request, default))));
        Assert(results.All(r => r.Succeeded), string.Join(";", results.Select(r => r.Error)));
        Assert(results.Select(r => r.Value!.TransactionId).Distinct().Count() == 1, "different IDs returned");
        var rows = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.CountAsync(t => t.ClientId == request.ClientId));
        Assert(rows == 1, $"{rows} rows stored");
    });

    await Check("Monetary precision and historical FX snapshot persist", async () =>
    {
        currentUser.Id = userA;
        var tooPrecise = await InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(
            new CreateTransactionRequest { ClientId = Guid.NewGuid(), Type = "Expense", CategoryId = expenseCategory, Amount = 10.005m, CurrencyCode = "USD", TransactionDate = new DateOnly(2026, 11, 2) }, default));
        Assert(tooPrecise.StatusCode == 400, "three-decimal amount accepted");
        var created = await InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(
            new CreateTransactionRequest { ClientId = Guid.NewGuid(), Type = "Expense", CategoryId = expenseCategory, Amount = 10.01m, CurrencyCode = "USD", TransactionDate = new DateOnly(2026, 11, 2) }, default));
        Assert(created.Succeeded, created.Error ?? "");
        var stored = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.AsNoTracking().SingleAsync(t => t.TransactionId == created.Value!.TransactionId));
        Assert(stored.Amount == 10.01m && stored.FxRate == FixedFx.Rate, $"amount {stored.Amount}, rate {stored.FxRate}");
        Assert(stored.AmountBase == Math.Round(stored.Amount * FixedFx.Rate, 2, MidpointRounding.AwayFromZero), $"base {stored.AmountBase}");
    });

    await Check("Concurrent duplicate contribution ClientId records one ledger entry", async () =>
    {
        currentUser.Id = userA;
        var goal = await InScope(sp => sp.GetRequiredService<ISavingsGoalService>().CreateAsync(
            new CreateSavingsGoalRequest { Name = "Emergency fund", TargetAmount = 1000m }, default));
        Assert(goal.Succeeded, goal.Error ?? "");
        var request = new CreateGoalContributionRequest { ClientId = Guid.NewGuid(), Amount = 50m, ContributedOn = new DateOnly(2026, 11, 3) };
        var results = await Task.WhenAll(Enumerable.Range(0, 4).Select(_ =>
            InScope(sp => sp.GetRequiredService<ISavingsGoalService>().CreateContributionAsync(goal.Value!.GoalId, request, default))));
        Assert(results.All(r => r.Succeeded), string.Join(";", results.Select(r => r.Error)));
        var rows = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().GoalContributions.CountAsync(c => c.GoalId == goal.Value!.GoalId));
        Assert(rows == 1, $"{rows} contributions stored");
    });

    await Check("Concurrent bill payments for one occurrence record one receipt", async () =>
    {
        currentUser.Id = userA;
        var bill = await InScope(sp => sp.GetRequiredService<IRecurringBillService>().CreateAsync(
            new CreateRecurringBillRequest { Name = "Internet", CategoryId = expenseCategory, Amount = 1500m, CurrencyCode = "PHP", Frequency = "Monthly", FirstDueDate = new DateOnly(2026, 11, 20), ReminderDaysBefore = 3 }, default));
        Assert(bill.Succeeded, bill.Error ?? "");
        var results = await Task.WhenAll(Enumerable.Range(0, 3).Select(_ =>
            InScope(sp => sp.GetRequiredService<IBillPaymentService>().PayAsync(bill.Value!.BillId,
                new PayBillRequest { ClientId = Guid.NewGuid(), DueDate = new DateOnly(2026, 11, 20) }, default))));
        Assert(results.Count(r => r.Succeeded) >= 1, "no payment succeeded");
        var receipts = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.CountAsync(t => t.BillId == bill.Value!.BillId));
        Assert(receipts == 1, $"{receipts} payment transactions stored");
    });

    await Check("C1 on SQL Server: two concurrent edits from the same version -> exactly one wins, the other 409, no lost update", async () =>
    {
        currentUser.Id = userA;
        var created = await InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(Expense(expenseCategory, Guid.NewGuid(), 100m), default));
        Assert(created.Succeeded, created.Error ?? "");
        var id = created.Value!.TransactionId; var seen = created.Value.Version;
        UpdateTransactionRequest Edit(decimal amount) => new() { Type = "Expense", CategoryId = expenseCategory, Amount = amount, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 11, 3), Version = seen };
        for (var round = 0; round < 5; round++)
        {
            var results = await Task.WhenAll(new[] { 111m + round, 222m + round }.Select(a =>
                InScope(sp => sp.GetRequiredService<ITransactionService>().UpdateAsync(id, Edit(a), default))));
            Assert(results.Count(r => r.Succeeded) == 1 && results.Count(r => r.StatusCode == 409) == 1,
                "statuses " + string.Join(",", results.Select(r => r.StatusCode)));
            var winner = results.Single(r => r.Succeeded).Value!;
            var stored = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.AsNoTracking().SingleAsync(t => t.TransactionId == id));
            Assert(stored.Amount == winner.Amount, $"stored {stored.Amount} != winner {winner.Amount}");
            seen = winner.Version;
        }
    });

    await Check("Concurrent edit vs delete: one outcome only, never a half-applied row", async () =>
    {
        currentUser.Id = userA;
        var created = await InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(Expense(expenseCategory, Guid.NewGuid(), 50m), default));
        var id = created.Value!.TransactionId;
        var edit = new UpdateTransactionRequest { Type = "Expense", CategoryId = expenseCategory, Amount = 60m, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 11, 3), Version = created.Value.Version };
        var update = InScope(sp => sp.GetRequiredService<ITransactionService>().UpdateAsync(id, edit, default));
        var delete = InScope(sp => sp.GetRequiredService<ITransactionService>().DeleteAsync(id, default));
        await Task.WhenAll(update, delete);
        var rows = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.AsNoTracking().Where(t => t.TransactionId == id).Select(t => t.Amount).ToListAsync());
        Assert(delete.Result.Succeeded || update.Result.Succeeded, "neither operation succeeded");
        if (delete.Result.Succeeded) Assert(rows.Count == 0 && (update.Result.Succeeded || update.Result.StatusCode is 404 or 409), "deleted but row remains or update misreported");
        else Assert(rows.Single() == 60m && delete.Result.StatusCode is 409 or 404, "update won but state inconsistent");
    });

    await Check("Rollback: a failed write inside the financial lock leaves no partial change", async () =>
    {
        currentUser.Id = userA;
        var before = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.CountAsync());
        // Category of the wrong type is rejected inside the locked write; nothing may be stored.
        var income = await InScope(sp => sp.GetRequiredService<ICategoryService>().CreateAsync(new CategoryWriteRequest { Name = "Salary", Type = "Income" }, false, default));
        var bad = await InScope(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(Expense(income.Value!.CategoryId, Guid.NewGuid(), 10m), default));
        Assert(!bad.Succeeded && bad.StatusCode is 400 or 409, "mismatched category accepted: " + bad.StatusCode);
        var after = await InScope(sp => sp.GetRequiredService<ApplicationDbContext>().Transactions.CountAsync());
        Assert(after == before, $"rows {before} -> {after}");
    });

    await Check("Ownership isolation: user B cannot read user A transactions", async () =>
    {
        currentUser.Id = userB;
        var page = await InScope(sp => sp.GetRequiredService<ITransactionService>().ListAsync(new TransactionQuery(), default));
        Assert(page.Succeeded && page.Value!.Items.Count == 0, "user B saw transactions");
    });
}
Console.WriteLine("Disposable catalog dropped.");
Console.WriteLine($"Passed: {passed}; Failed: {failed}");
return failed == 0 ? 0 : 1;

static CreateTransactionRequest Expense(int category, Guid clientId, decimal amount) => new()
{ ClientId = clientId, Type = "Expense", CategoryId = category, Amount = amount, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 11, 3) };

static async Task<int> CreateUser(IServiceProvider provider, string email)
{
    await using var scope = provider.CreateAsyncScope();
    var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
    var user = new ApplicationUser { Email = email, UserName = email, FullName = "SQL Test", LockoutEnabled = true };
    var result = await users.CreateAsync(user, "Synthetic!Pass123");
    if (!result.Succeeded) throw new Exception(string.Join(",", result.Errors.Select(e => e.Code)));
    return user.Id;
}

internal sealed class TestUser : ICurrentUser
{
    public int Id;
    public int? UserId => Id == 0 ? null : Id;
    public bool Owns(int resourceOwnerId) => Id != 0 && Id == resourceOwnerId;
    public bool IsAdministrator => false;
}

internal sealed class FixedFx : IFrankfurterClient
{
    public const decimal Rate = 58.12345678m;
    public Task<bool> SupportsAsync(string code, CancellationToken ct) => Task.FromResult(code is "USD" or "PHP" or "EUR");
    public Task<FxQuote> GetRateAsync(string source, string target, DateOnly date, CancellationToken ct) =>
        Task.FromResult(new FxQuote(source, target, date, Rate));
}
