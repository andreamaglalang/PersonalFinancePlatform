using System.Data;
using Hangfire;
using Hangfire.SqlServer;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Jobs;
using PersonalFinance.Infrastructure.Persistence;

var passed = 0;
var failed = 0;
void Check(bool condition, string name)
{
    if (condition) { passed++; Console.WriteLine($"PASS {name}"); }
    else { failed++; Console.WriteLine($"FAIL {name}"); }
}

try
{
    const string expectedDatabase = "OWLI_HangfireValidation_20261124_1735";
    var expected = Environment.GetEnvironmentVariable("OWLI_HANGFIRE_TEST_DATABASE")
        ?? throw new InvalidOperationException("The isolated test database name is required.");
    if (expected != expectedDatabase || expected.Equals("PersonalFinanceDb", StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException("The connection is not the explicitly authorized disposable LocalDB target.");
    var parsed = new SqlConnectionStringBuilder
    {
        DataSource = "(localdb)\\MSSQLLocalDB", InitialCatalog = expected,
        IntegratedSecurity = true, TrustServerCertificate = true, Pooling = false
    };
    var cs = parsed.ConnectionString;

    await using (var identity = new SqlConnection(cs))
    {
        await identity.OpenAsync();
        await using var command = identity.CreateCommand();
        command.CommandText = "SELECT DB_NAME(), @@SERVERNAME, (SELECT COUNT(*) FROM sys.databases WHERE name=N'PersonalFinanceDb'), (SELECT COUNT(*) FROM sys.databases WHERE name=@expected)";
        command.Parameters.AddWithValue("@expected", expectedDatabase);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync() || reader.GetString(0) != expectedDatabase || reader.GetInt32(2) != 1 || reader.GetInt32(3) != 1)
            throw new InvalidOperationException("Disposable SQL identity preflight failed.");
        Console.WriteLine($"PASS disposable DB identity ({reader.GetString(0)} on {reader.GetString(1)}) distinct from PersonalFinanceDb");
    }

    var options = new DbContextOptionsBuilder<ApplicationDbContext>().UseSqlServer(cs).Options;
    await using (var db = new ApplicationDbContext(options))
    {
        var userTables = await db.Database.SqlQueryRaw<int>("SELECT COUNT(*) AS [Value] FROM sys.tables WHERE is_ms_shipped=0").SingleAsync();
        if (userTables != 0) throw new InvalidOperationException("Disposable database was not empty before schema initialization.");
        await db.Database.MigrateAsync();
    }
    Check(true, "EF schema initialized only in verified disposable database");

    var storage = new SqlServerStorage(cs, new SqlServerStorageOptions { SchemaName = "HangFire", PrepareSchemaIfNecessary = true });
    JobStorage.Current = storage; // Isolated process; no Hangfire server is started.
    var recurring = new RecurringJobManager(storage);
    HangfireJobRegistration.Register(recurring);
    HangfireJobRegistration.Register(recurring); // AddOrUpdate must remain exactly three persistent schedules.
    var schedules = await ReadSchedulesAsync(cs);
    Check(schedules.Count == 3
        && schedules["owli-upcoming-bill-reminders"].Cron == "0 9 * * *"
        && schedules["owli-monthly-financial-summaries"].Cron == "0 9 1 * *"
        && schedules["owli-budget-threshold-alerts"].Cron == "*/15 * * * *"
        && schedules.Values.All(x => x.TimeZone == "Asia/Manila"), "three idempotent Manila recurring schedules");
    Check(await ScalarAsync<int>(cs, "SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON t.schema_id=s.schema_id WHERE s.name=N'HangFire'") > 0,
        "Hangfire SQL storage schema initialized in isolated DB");
    try { HangfireJobRegistration.EnsureDedicatedStorage(cs, cs); Check(false, "storage rejects finance database reuse"); }
    catch (InvalidOperationException) { Check(true, "storage rejects finance database reuse"); }
    try { HangfireJobRegistration.EnsureDedicatedStorage("Server=(localdb)\\MSSQLLocalDB;Database=PersonalFinanceDb;Integrated Security=true", cs); Check(false, "storage rejects PersonalFinanceDb"); }
    catch (InvalidOperationException) { Check(true, "storage rejects PersonalFinanceDb"); }

    var fixedClock = new FixedClock(new DateTimeOffset(2026, 11, 23, 16, 0, 1, TimeSpan.Zero)); // Nov 24, 00:00:01 Manila
    Check(HangfireJobRegistration.ManilaTimeZone.Id == "Asia/Manila"
        && DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(fixedClock.GetUtcNow(), HangfireJobRegistration.ManilaTimeZone).DateTime)
            == new DateOnly(2026, 11, 24), "Philippine midnight boundary uses Asia/Manila calendar date");
    Check(new[] { typeof(UpcomingBillReminderJob), typeof(MonthlyFinancialSummaryJob), typeof(BudgetThresholdAlertJob) }
        .All(type => type.GetMethod("RunAsync")!.GetCustomAttributes(typeof(AutomaticRetryAttribute), false)
            .Cast<AutomaticRetryAttribute>().Single().Attempts == 3), "each scheduled job has bounded three-attempt automatic retry");
    var ids = await SeedAsync(options);
    var beforeTransactions = await CountAsync<PersonalFinance.Domain.Entities.Transaction>(options);
    var beforeReceipts = await CountAsync<BillPaymentReceipt>(options);
    var beforeContributions = await CountAsync<GoalContribution>(options);

    await RunBillAsync(options, fixedClock);
    await RunBillAsync(options, fixedClock);
    Check(await CountNotificationsAsync(cs, "BillReminder") == 1, "bill reminder generated once for due occurrence");
    Check(await ScalarAsync<int>(cs, "SELECT COUNT(*) FROM dbo.Notifications WHERE Type=N'BillReminder' AND ReferenceId=@id AND DeduplicationKey=@key",
        ("@id", ids.Bill), ("@key", $"bill:{ids.Bill}:due:2026-11-25")) == 1, "bill reminder owner/reference/idempotency key persisted");

    await AddRetryBillAsync(options, ids.User);
    var failingOptions = FailureOptions(cs);
    var failedAttempt = false;
    try { await RunBillAsync(failingOptions, fixedClock); }
    catch (InvalidOperationException) { failedAttempt = true; }
    Check(failedAttempt && await CountNotificationsAsync(cs, "BillReminder") == 1, "failed reminder attempt rolls back without partial notification");
    await RunBillAsync(options, fixedClock);
    Check(await CountNotificationsAsync(cs, "BillReminder") == 2, "reminder succeeds on retry without duplicate prior occurrence");

    await RunMonthlyAsync(options, fixedClock);
    await RunMonthlyAsync(options, fixedClock);
    var summary = await ReadSummaryAsync(options, ids.User);
    Check(summary is { Month: var m, Income: 1000m, Expense: 200m, Net: 800m, Savings: 50m } && m == new DateOnly(2026, 10, 1),
        "monthly summary uses previous Manila month, base amounts, and goal contributions");
    Check(await CountNotificationsAsync(cs, "MonthlySummary") == 1, "monthly summary notification generated once on retries");
    await SetTransactionAsync(options, new DateOnly(2026, 10, 11), 250m);
    var monthlyFailure = false;
    try { await RunMonthlyAsync(FailureOptions(cs), fixedClock); } catch (InvalidOperationException) { monthlyFailure = true; }
    var rolledBackSummary = await ReadSummaryAsync(options, ids.User);
    Check(monthlyFailure && rolledBackSummary?.Expense == 200m && await CountNotificationsAsync(cs, "MonthlySummary") == 1,
        "failed monthly summary update is atomic and preserves the prior summary/notification");
    await RunMonthlyAsync(options, fixedClock);
    Check((await ReadSummaryAsync(options, ids.User))?.Expense == 250m && await CountNotificationsAsync(cs, "MonthlySummary") == 1,
        "monthly summary recomputes safely on retry without duplicate notification");

    var budgetFailure = false;
    try { await RunBudgetAsync(FailureOptions(cs), fixedClock); } catch (InvalidOperationException) { budgetFailure = true; }
    Check(budgetFailure && await CountNotificationsAsync(cs, "BudgetAlert") == 0
        && await BudgetStatusAsync(options, ids.Budget) is null, "failed budget transition rolls back status and notification together");
    await RunBudgetAsync(options, fixedClock);
    await RunBudgetAsync(options, fixedClock);
    Check(await CountNotificationsAsync(cs, "BudgetAlert") == 1 && await BudgetStatusAsync(options, ids.Budget) == BudgetAlertStatus.Warning,
        "budget warning transition notifies once at configured threshold");
    await SetBudgetSpendAsync(options, ids.TransactionToChange, 110m);
    await RunBudgetAsync(options, fixedClock);
    await RunBudgetAsync(options, fixedClock);
    Check(await CountNotificationsAsync(cs, "BudgetAlert") == 2 && await BudgetStatusAsync(options, ids.Budget) == BudgetAlertStatus.Exceeded,
        "budget exceeded transition notifies once");
    await SetBudgetSpendAsync(options, ids.TransactionToChange, 50m);
    await RunBudgetAsync(options, fixedClock);
    Check(await BudgetStatusAsync(options, ids.Budget) is null, "below-threshold status resets for future alert rearming");
    await SetBudgetSpendAsync(options, ids.TransactionToChange, 81m);
    await RunBudgetAsync(options, fixedClock);
    Check(await CountNotificationsAsync(cs, "BudgetAlert") == 3, "budget threshold can alert again after returning to OK");

    Check(await CountAsync<PersonalFinance.Domain.Entities.Transaction>(options) == beforeTransactions
        && await CountAsync<BillPaymentReceipt>(options) == beforeReceipts
        && await CountAsync<GoalContribution>(options) == beforeContributions,
        "jobs leave transactions, payment receipts, and contributions unchanged");
    Check(await ScalarAsync<int>(cs, "SELECT COUNT(DISTINCT [Key]) FROM HangFire.Hash WHERE [Key] LIKE N'recurring-job:%'") == 3,
        "schedule registration creates no duplicate recurring jobs");
}
catch (Exception ex)
{
    failed++;
    Console.Error.WriteLine($"FAIL unexpected {ex.GetType().Name}: {SafeMessage(ex.Message)}");
}

Console.WriteLine($"Hangfire validation: {passed} PASS, {failed} FAIL");
return failed == 0 ? 0 : 1;

static string SafeMessage(string value) => value.Replace(Environment.NewLine, " ").Replace("Password=", "Password=[redacted]", StringComparison.OrdinalIgnoreCase);

static async Task<Dictionary<string, (string Cron, string TimeZone)>> ReadSchedulesAsync(string cs)
{
    var result = new Dictionary<string, (string Cron, string TimeZone)>();
    await using var connection = new SqlConnection(cs); await connection.OpenAsync();
    await using var command = connection.CreateCommand();
    command.CommandText = "SELECT [Key],[Field],[Value] FROM HangFire.Hash WHERE [Key] LIKE N'recurring-job:%' AND [Field] IN (N'Cron',N'TimeZoneId')";
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        var id = reader.GetString(0)["recurring-job:".Length..]; var field = reader.GetString(1); var value = reader.GetString(2);
        var current = result.GetValueOrDefault(id);
        result[id] = field == "Cron" ? (value, current.TimeZone) : (current.Cron, value);
    }
    return result;
}
static async Task<int> ScalarAsync<T>(string cs, string sql, params (string Name, object Value)[] parameters)
{
    await using var connection = new SqlConnection(cs); await connection.OpenAsync();
    await using var command = connection.CreateCommand(); command.CommandText = sql;
    foreach (var parameter in parameters) command.Parameters.AddWithValue(parameter.Name, parameter.Value);
    return Convert.ToInt32(await command.ExecuteScalarAsync());
}
static async Task<int> CountAsync<T>(DbContextOptions<ApplicationDbContext> options) where T : class
{ await using var db = new ApplicationDbContext(options); return await db.Set<T>().CountAsync(); }
static DbContextOptions<ApplicationDbContext> FailureOptions(string cs) =>
    new DbContextOptionsBuilder<ApplicationDbContext>().UseSqlServer(cs).AddInterceptors(new FailOnceSaveInterceptor()).Options;
static async Task RunBillAsync(DbContextOptions<ApplicationDbContext> options, TimeProvider clock)
{ await using var db = new ApplicationDbContext(options); await new UpcomingBillReminderJob(db, clock).RunAsync(default); }
static async Task RunMonthlyAsync(DbContextOptions<ApplicationDbContext> options, TimeProvider clock)
{ await using var db = new ApplicationDbContext(options); await new MonthlyFinancialSummaryJob(db, clock).RunAsync(default); }
static async Task RunBudgetAsync(DbContextOptions<ApplicationDbContext> options, TimeProvider clock)
{ await using var db = new ApplicationDbContext(options); await new BudgetThresholdAlertJob(db, clock).RunAsync(default); }
static async Task<int> CountNotificationsAsync(string cs, string type) =>
    await ScalarAsync<int>(cs, "SELECT COUNT(*) FROM dbo.Notifications WHERE Type=@type", ("@type", type));
static async Task<(DateOnly Month, decimal Income, decimal Expense, decimal Net, decimal Savings)?> ReadSummaryAsync(
    DbContextOptions<ApplicationDbContext> options, int userId)
{
    await using var db = new ApplicationDbContext(options);
    var x = await db.MonthlySummaries.SingleOrDefaultAsync(s => s.UserId == userId);
    return x is null ? null : (x.SummaryMonth, x.TotalIncome, x.TotalExpense, x.NetCashFlow, x.SavingsAllocated);
}
static async Task<BudgetAlertStatus?> BudgetStatusAsync(DbContextOptions<ApplicationDbContext> options, int id)
{ await using var db = new ApplicationDbContext(options); return await db.Budgets.Where(x => x.BudgetId == id).Select(x => x.LastAlertStatus).SingleAsync(); }
static async Task SetBudgetSpendAsync(DbContextOptions<ApplicationDbContext> options, int transactionId, decimal amount)
{
    await using var db = new ApplicationDbContext(options); var t = await db.Transactions.SingleAsync(x => x.TransactionId == transactionId);
    t.AmountBase = amount; t.Amount = amount; await db.SaveChangesAsync();
}
static async Task SetTransactionAsync(DbContextOptions<ApplicationDbContext> options, DateOnly date, decimal amount)
{
    await using var db = new ApplicationDbContext(options); var t = await db.Transactions.SingleAsync(x => x.TransactionDate == date);
    t.AmountBase = amount; t.Amount = amount; await db.SaveChangesAsync();
}
static async Task<(int User, int Bill, int Budget, int TransactionToChange)> SeedAsync(DbContextOptions<ApplicationDbContext> options)
{
    await using var db = new ApplicationDbContext(options);
    var user = new ApplicationUser { UserName = "hangfire.synthetic", NormalizedUserName = "HANGFIRE.SYNTHETIC",
        Email = "hangfire.synthetic@example.invalid", NormalizedEmail = "HANGFIRE.SYNTHETIC@EXAMPLE.INVALID",
        FullName = "Synthetic Automation User", BaseCurrency = "PHP", EmailConfirmed = true };
    db.Users.Add(user); await db.SaveChangesAsync();
    db.UserRoles.Add(new Microsoft.AspNetCore.Identity.IdentityUserRole<int> { UserId = user.Id, RoleId = 1 });
    var expense = new Category { UserId = user.Id, Name = "Synthetic Expense", Type = TransactionType.Expense, IsActive = true };
    var income = new Category { UserId = user.Id, Name = "Synthetic Income", Type = TransactionType.Income, IsActive = true };
    db.Categories.AddRange(expense, income); await db.SaveChangesAsync();
    var goal = new SavingsGoal { UserId = user.Id, Name = "Synthetic Goal", TargetAmount = 500m, Status = SavingsGoalStatus.Active };
    db.SavingsGoals.Add(goal); await db.SaveChangesAsync();
    db.GoalContributions.Add(new GoalContribution { GoalId = goal.GoalId, Amount = 50m, ContributedOn = new DateOnly(2026, 10, 20) });
    db.Budgets.Add(new Budget { UserId = user.Id, CategoryId = expense.CategoryId, PeriodMonth = new DateOnly(2026, 11, 1), LimitAmount = 100m, AlertThresholdPct = 80 });
    db.RecurringBills.Add(new RecurringBill { UserId = user.Id, CategoryId = expense.CategoryId, Name = "Synthetic bill", Amount = 1m,
        CurrencyCode = "PHP", Frequency = BillFrequency.Monthly, AnchorDate = new DateOnly(2026, 1, 25), NextDueDate = new DateOnly(2026, 11, 25),
        IntendedDayOfMonth = 25, ReminderDaysBefore = 1, IsActive = true });
    db.RecurringBills.Add(new RecurringBill { UserId = user.Id, CategoryId = expense.CategoryId, Name = "Inactive bill", Amount = 3m,
        CurrencyCode = "PHP", Frequency = BillFrequency.Monthly, AnchorDate = new DateOnly(2026, 1, 27), NextDueDate = new DateOnly(2026, 11, 25),
        IntendedDayOfMonth = 27, ReminderDaysBefore = 1, IsActive = false });
    db.Transactions.AddRange(
        Tx(user.Id, income.CategoryId, TransactionType.Income, 1000m, new DateOnly(2026, 10, 10)),
        Tx(user.Id, expense.CategoryId, TransactionType.Expense, 200m, new DateOnly(2026, 10, 11)),
        Tx(user.Id, expense.CategoryId, TransactionType.Expense, 80m, new DateOnly(2026, 11, 24)));
    await db.SaveChangesAsync();
    return (user.Id, await db.RecurringBills.Where(b => b.Name == "Synthetic bill").Select(b => b.BillId).SingleAsync(),
        await db.Budgets.Select(b => b.BudgetId).SingleAsync(), await db.Transactions.Where(t => t.TransactionDate == new DateOnly(2026, 11, 24)).Select(t => t.TransactionId).SingleAsync());
}
static PersonalFinance.Domain.Entities.Transaction Tx(int userId, int categoryId, TransactionType type, decimal amount, DateOnly date) =>
    new() { UserId = userId, CategoryId = categoryId, ClientId = Guid.NewGuid(), Type = type, Amount = amount,
        CurrencyCode = "PHP", FxRate = 1m, FxRateDate = date, AmountBase = amount, TransactionDate = date, CreatedAtUtc = new DateTime(2026, 11, 24, 0, 0, 0, DateTimeKind.Utc) };
static async Task AddRetryBillAsync(DbContextOptions<ApplicationDbContext> options, int userId)
{
    await using var db = new ApplicationDbContext(options);
    var categoryId = await db.Categories.Where(x => x.UserId == userId && x.Type == TransactionType.Expense).Select(x => x.CategoryId).SingleAsync();
    db.RecurringBills.Add(new RecurringBill { UserId = userId, CategoryId = categoryId, Name = "Synthetic retry bill", Amount = 2m,
        CurrencyCode = "PHP", Frequency = BillFrequency.Monthly, AnchorDate = new DateOnly(2026, 1, 26), NextDueDate = new DateOnly(2026, 11, 26),
        IntendedDayOfMonth = 26, ReminderDaysBefore = 2, IsActive = true });
    await db.SaveChangesAsync();
}

sealed class FixedClock(DateTimeOffset now) : TimeProvider { public override DateTimeOffset GetUtcNow() => now; }
sealed class FailOnceSaveInterceptor : SaveChangesInterceptor
{
    private int _remaining = 1;
    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(DbContextEventData eventData, InterceptionResult<int> result,
        CancellationToken cancellationToken = default)
    {
        if (Interlocked.Exchange(ref _remaining, 0) == 1) throw new InvalidOperationException("Synthetic first-save failure.");
        return ValueTask.FromResult(result);
    }
}
