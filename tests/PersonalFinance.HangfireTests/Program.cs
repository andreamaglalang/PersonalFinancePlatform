using System.Reflection;
using Hangfire;
using Hangfire.Common;
using Hangfire.SqlServer;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Jobs;
using PersonalFinance.Infrastructure.Persistence;
using PersonalFinance.Worker;

// Tests the selected FinanceJobs family and the independent PersonalFinance.Worker.
// Offline checks always run. Live checks need OWLI_SQL_TEST_SERVER (a connection to master on an approved
// disposable/local server); they create, use and drop their own OWLI_HangfireTests_* and PersonalFinanceSqlTests_*
// catalogs and never touch operational OWLI_Hangfire or PersonalFinanceDb.
var passed = 0; var failed = 0;
void Assert(bool condition, string detail) { if (!condition) throw new Exception(detail); }
async Task Check(string name, Func<Task> test)
{
    try { await test(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.WriteLine("FAIL " + name + ": " + ex.GetType().Name + ": " + ex.Message); }
}
Task Sync(string name, Action test) => Check(name, () => { test(); return Task.CompletedTask; });

// ---------------- Offline: selected family, schedules, retry policy, storage guard, worker composition ----------------
await Sync("Exactly the approved recurring IDs, Manila cron schedules and FinanceJobs methods are registered", () =>
{
    var manager = new RecordingManager();
    HangfireSetup.RegisterRecurringJobs(manager);
    HangfireSetup.RegisterRecurringJobs(manager);
    var expected = new Dictionary<string, (string Cron, string Method)>
    {
        [HangfireSetup.BillRemindersJobId] = ("0 7 * * *", nameof(FinanceJobs.SendBillRemindersAsync)),
        [HangfireSetup.BudgetAlertsJobId] = ("0 * * * *", nameof(FinanceJobs.SendBudgetAlertsAsync)),
        [HangfireSetup.MonthlySummariesJobId] = ("30 0 1 * *", nameof(FinanceJobs.GenerateMonthlySummariesAsync)),
    };
    Assert(manager.Calls.Select(c => c.Id).Distinct().OrderBy(x => x).SequenceEqual(expected.Keys.OrderBy(x => x)), "unexpected recurring IDs");
    foreach (var call in manager.Calls)
    {
        Assert(call.Cron == expected[call.Id].Cron, $"{call.Id} cron {call.Cron}");
        Assert(call.Options.TimeZone.Id == "Asia/Manila", $"{call.Id} timezone {call.Options.TimeZone.Id}");
        Assert(call.Job.Type == typeof(FinanceJobs) && call.Job.Method.Name == expected[call.Id].Method, $"{call.Id} job {call.Job.Method.Name}");
    }
});

await Sync("Persisted job identity is unchanged (type, namespace, assembly)", () =>
{
    Assert(typeof(FinanceJobs).FullName == "PersonalFinance.Infrastructure.Jobs.FinanceJobs", "type name changed");
    Assert(typeof(FinanceJobs).Assembly.GetName().Name == "PersonalFinance.Infrastructure", "assembly changed");
});

await Sync("Every job retries 3 times and never runs concurrently with itself", () =>
{
    foreach (var name in new[] { nameof(FinanceJobs.SendBillRemindersAsync), nameof(FinanceJobs.SendBudgetAlertsAsync), nameof(FinanceJobs.GenerateMonthlySummariesAsync) })
    {
        var method = typeof(FinanceJobs).GetMethod(name)!;
        Assert(method.GetCustomAttribute<AutomaticRetryAttribute>()?.Attempts == 3, name + " retry attempts");
        Assert(method.GetCustomAttribute<DisableConcurrentExecutionAttribute>() is not null, name + " concurrency guard");
    }
});

await Sync("The competing job family is removed", () =>
{
    var names = typeof(FinanceJobs).Assembly.GetTypes().Select(t => t.Name).ToHashSet();
    foreach (var removed in new[] { "UpcomingBillReminderJob", "MonthlyFinancialSummaryJob", "BudgetThresholdAlertJob", "HangfireJobRegistration" })
        Assert(!names.Contains(removed), removed + " still exists");
});

await Sync("Storage guard: operational and disposable pairings only", () =>
{
    const string server = "Server=sql.example.test,1433;User ID=x;Password=y;";
    HangfireSetup.EnsureDedicatedStorage(server + "Database=OWLI_Hangfire", server + "Database=PersonalFinanceDb");
    HangfireSetup.EnsureDedicatedStorage(server + "Database=OWLI_HangfireTests_abc", server + "Database=OWLI_Runtime_20261126");
    HangfireSetup.EnsureDedicatedStorage(server + "Database=OWLI_HangfireTests_abc", server + "Database=PersonalFinanceSqlTests_abc");
    foreach (var (jobs, finance) in new[]
    {
        ("OWLI_HangfireTests_abc", "PersonalFinanceDb"),   // tests never pair with operational finance data
        ("PersonalFinanceDb", "PersonalFinanceDb"),
        ("master", "PersonalFinanceDb"),
        ("OWLI_HangfireTests_", "OWLI_Runtime_x"),
        ("SomethingElse", "PersonalFinanceDb"),
    })
        Assert(Rejects(server + "Database=" + jobs, server + "Database=" + finance), $"accepted {jobs} + {finance}");
    Assert(Rejects("Server=other;User ID=x;Password=y;Database=OWLI_Hangfire", server + "Database=PersonalFinanceDb"), "accepted another instance");
});

await Sync("Worker composition validates without HTTP or current-user services (M7)", () =>
{
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddFinanceWorker(Config("Server=sql.example.test;Database=OWLI_Runtime_x;User ID=x;Password=y",
        "Server=sql.example.test;Database=OWLI_HangfireTests_x;User ID=x;Password=y"));
    using var provider = services.BuildServiceProvider(new ServiceProviderOptions { ValidateOnBuild = true, ValidateScopes = true });
    Assert(!services.Any(d => d.ServiceType == typeof(ICurrentUser)), "worker registered ICurrentUser");
    Assert(!services.Any(d => d.ServiceType.Name is "ITransactionService" or "IAuthenticationService"), "worker registered member/API services");
    using var scope = provider.CreateScope();
    Assert(scope.ServiceProvider.GetRequiredService<FinanceJobs>() is not null, "FinanceJobs unresolved");
});

await Sync("Worker refuses operational finance with disposable Hangfire storage", () =>
{
    var threw = false;
    try { new ServiceCollection().AddFinanceWorker(Config("Server=s;Database=PersonalFinanceDb;User ID=x;Password=y", "Server=s;Database=OWLI_HangfireTests_x;User ID=x;Password=y")); }
    catch (InvalidOperationException) { threw = true; }
    Assert(threw, "accepted");
});

await Sync("Development test profile rejects operational storage and SQL certificate bypasses without connecting", () =>
{
    const string server = "Server=sql.example.test;User ID=fixture;Password=fixture;";
    DevelopmentStorageValidation.ValidateConfiguration(Config(server + "Database=OWLI_Runtime_local", server + "Database=OWLI_HangfireTests_local"));
    foreach (var (finance, jobs) in new[]
    {
        (server + "Database=PersonalFinanceDb", server + "Database=OWLI_Hangfire"),
        (server + "Database=PersonalFinanceDb", server + "Database=OWLI_HangfireTests_local"),
        (server + "Database=OWLI_Runtime_local;TrustServerCertificate=True", server + "Database=OWLI_HangfireTests_local"),
        (server + "Database=OWLI_Runtime_local", server + "Database=OWLI_HangfireTests_local;TrustServerCertificate=True"),
        (server + "Database=OWLI_Runtime_local;Encrypt=False", server + "Database=OWLI_HangfireTests_local"),
        (server + "Database=OWLI_Runtime_local", server + "Database=OWLI_HangfireTests_local;Encrypt=False"),
        (server + "Database=OWLI_Runtime_local;AttachDbFilename=fixture.mdf", server + "Database=OWLI_HangfireTests_local"),
        (server + "Database=OWLI_Runtime_local", server + "Database=OWLI_HangfireTests_local;AttachDbFilename=fixture.mdf"),
    })
    {
        var refused = false;
        try { DevelopmentStorageValidation.ValidateConfiguration(Config(finance, jobs)); }
        catch (InvalidOperationException) { refused = true; }
        Assert(refused, "development profile accepted operational storage or an unvalidated SQL connection");
    }
});

// ---------------- Live: independent worker lifecycle against disposable catalogs ----------------
var server = Environment.GetEnvironmentVariable("OWLI_SQL_TEST_SERVER");
if (string.IsNullOrWhiteSpace(server))
{
    Console.WriteLine("SKIP live worker checks: OWLI_SQL_TEST_SERVER is not configured.");
    return Done();
}
var master = new SqlConnectionStringBuilder(server);
if (!string.IsNullOrEmpty(master.InitialCatalog) && !master.InitialCatalog.Equals("master", StringComparison.OrdinalIgnoreCase) || !string.IsNullOrEmpty(master.AttachDBFilename))
    throw new InvalidOperationException("OWLI_SQL_TEST_SERVER must target master without AttachDbFilename.");
var run = Guid.NewGuid().ToString("N");
var financeDb = "PersonalFinanceSqlTests_" + run;
var jobsDb = HangfireSetup.TestCatalogPrefix + run;
string Cs(string db) => new SqlConnectionStringBuilder(server) { InitialCatalog = db, Pooling = true }.ConnectionString;
var financeCs = Cs(financeDb); var jobsCs = Cs(jobsDb);

try
{
    await Master($"CREATE DATABASE [{financeDb}]; CREATE DATABASE [{jobsDb}];");
    await using (var connection = new SqlConnection(jobsCs))
    {
        await connection.OpenAsync();
        SqlServerObjectsInstaller.Install(connection); // official package installer; disposable catalog only
    }
    Console.WriteLine("Disposable finance and Hangfire catalogs created and initialized.");

    var seed = await SeedAsync(financeCs);
    var storage = new SqlServerStorage(jobsCs, new SqlServerStorageOptions { PrepareSchemaIfNecessary = false });
    var recurring = new RecurringJobManager(storage);

    IHost? worker = await StartWorker();
    await Check("Worker registers on its own Hangfire server and exactly 3 recurring jobs with Manila schedules", async () =>
    {
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Server") == 1, "server count");
        var ids = await Strings(jobsCs, "SELECT Value FROM HangFire.[Set] WHERE [Key]='recurring-jobs'");
        Assert(ids.OrderBy(x => x).SequenceEqual(new[] { "owli-bill-reminders", "owli-budget-alerts", "owli-monthly-summaries" }), string.Join(",", ids));
        foreach (var id in ids)
        {
            var tz = await Strings(jobsCs, $"SELECT Value FROM HangFire.Hash WHERE [Key]='recurring-job:{id}' AND Field='TimeZoneId'");
            var job = await Strings(jobsCs, $"SELECT Value FROM HangFire.Hash WHERE [Key]='recurring-job:{id}' AND Field='Job'");
            Assert(tz.SingleOrDefault() == "Asia/Manila", id + " timezone");
            Assert(job.Single().Contains("PersonalFinance.Infrastructure.Jobs.FinanceJobs, PersonalFinance.Infrastructure"), id + " job type");
        }
    });

    await Check("Retry: a failed attempt is rescheduled by AutomaticRetry and succeeds once storage recovers, without duplicates", async () =>
    {
        await Master($"ALTER DATABASE [{financeDb}] SET OFFLINE WITH ROLLBACK IMMEDIATE");
        SqlConnection.ClearAllPools();
        recurring.Trigger(HangfireSetup.MonthlySummariesJobId);
        var jobId = await Until(async () => (await Strings(jobsCs,
            "SELECT CAST(j.Id AS nvarchar(20)) FROM HangFire.Job j JOIN HangFire.JobParameter p ON p.JobId=j.Id AND p.Name='RetryCount' WHERE TRY_CAST(REPLACE(p.Value,'\"','') AS int) >= 1")).FirstOrDefault(), "retry scheduled");
        await Master($"ALTER DATABASE [{financeDb}] SET ONLINE");
        new BackgroundJobClient(storage).Requeue(jobId!);
        await Until(async () => await Scalar(jobsCs, $"SELECT COUNT(*) FROM HangFire.Job WHERE Id={jobId} AND StateName='Succeeded'") == 1 ? "ok" : null, "retried job succeeded");
        Assert(await Scalar(financeCs, "SELECT COUNT(*) FROM MonthlySummaries") == 1, "summary count after retry");
    });

    await Check("Triggered jobs are processed by the worker and produce the expected per-user results", async () =>
    {
        recurring.Trigger(HangfireSetup.BillRemindersJobId);
        recurring.Trigger(HangfireSetup.BudgetAlertsJobId);
        await AllSettled();
        Assert(await Notifications("BillReminder") == 1, "bill reminders");
        Assert(await Notifications("BudgetAlert") == 1, "budget alerts");
        Assert(await Notifications("MonthlySummary") == 1, "summary notifications");
        Assert(await Scalar(financeCs, $"SELECT COUNT(*) FROM Notifications WHERE UserId<>{seed.UserId}") == 0, "results for another user");
    });

    await Check("Concurrent and repeated triggers create no duplicate financial effects", async () =>
    {
        for (var i = 0; i < 2; i++)
        {
            recurring.Trigger(HangfireSetup.BillRemindersJobId);
            recurring.Trigger(HangfireSetup.BudgetAlertsJobId);
            recurring.Trigger(HangfireSetup.BudgetAlertsJobId);
            recurring.Trigger(HangfireSetup.MonthlySummariesJobId);
        }
        await AllSettled();
        Assert(await Notifications("BillReminder") == 1 && await Notifications("BudgetAlert") == 1 && await Notifications("MonthlySummary") == 1, "duplicates created");
        Assert(await Scalar(financeCs, "SELECT COUNT(*) FROM MonthlySummaries") == 1, "duplicate summary");
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName='Failed'") == 0, "failed jobs");
    });

    await Check("Stopping the worker deregisters its server (graceful cancellation)", async () =>
    {
        await worker.StopAsync(TimeSpan.FromSeconds(30)); worker.Dispose(); worker = null;
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Server") == 0, "server still registered");
    });

    await Check("With no worker running nothing processes jobs; a restarted worker processes the backlog", async () =>
    {
        var before = await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName='Succeeded'");
        recurring.Trigger(HangfireSetup.BudgetAlertsJobId);
        await Task.Delay(TimeSpan.FromSeconds(6));
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName='Enqueued'") == 1, "job not left enqueued");
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName='Succeeded'") == before, "processed without a worker");
        worker = await StartWorker();
        await AllSettled();
        Assert(await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName='Succeeded'") == before + 1, "backlog not processed");
        Assert((await Strings(jobsCs, "SELECT Value FROM HangFire.[Set] WHERE [Key]='recurring-jobs'")).Count == 3, "restart changed recurring set");
        Assert(await Notifications("BudgetAlert") == 1, "restart duplicated alerts");
        await worker.StopAsync(TimeSpan.FromSeconds(30)); worker.Dispose(); worker = null;
    });

    async Task<IHost> StartWorker()
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { DisableDefaults = true });
        builder.Configuration.AddConfiguration(Config(financeCs, jobsCs, pollSeconds: 1));
        builder.Logging.SetMinimumLevel(LogLevel.Warning).AddSimpleConsole();
        builder.Services.AddFinanceWorker(builder.Configuration);
        builder.ConfigureContainer(new DefaultServiceProviderFactory(new ServiceProviderOptions { ValidateOnBuild = true, ValidateScopes = true }));
        var host = builder.Build();
        await host.StartAsync();
        await Until(async () => await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Server") == 1 ? "up" : null, "worker server registered");
        return host;
    }
    Task<int> Notifications(string type) => Scalar(financeCs, $"SELECT COUNT(*) FROM Notifications WHERE Type='{type}'");
    Task AllSettled() => Until(async () => await Scalar(jobsCs, "SELECT COUNT(*) FROM HangFire.Job WHERE StateName IN ('Enqueued','Processing','Scheduled','Awaiting')") == 0 ? "ok" : null, "jobs settled");
}
finally
{
    SqlConnection.ClearAllPools();
    await Master($"IF DB_ID(N'{financeDb}') IS NOT NULL BEGIN ALTER DATABASE [{financeDb}] SET ONLINE; ALTER DATABASE [{financeDb}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{financeDb}]; END; " +
                 $"IF DB_ID(N'{jobsDb}') IS NOT NULL BEGIN ALTER DATABASE [{jobsDb}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{jobsDb}]; END");
    Console.WriteLine("Disposable catalogs dropped.");
}
return Done();

int Done() { Console.WriteLine($"Hangfire/worker checks: {passed} passed, {failed} failed."); return failed == 0 ? 0 : 1; }

static bool Rejects(string jobs, string finance)
{
    try { HangfireSetup.EnsureDedicatedStorage(jobs, finance); return false; }
    catch (InvalidOperationException) { return true; }
}

static IConfiguration Config(string finance, string hangfire, int pollSeconds = 15) => new ConfigurationBuilder()
    .AddInMemoryCollection(new Dictionary<string, string?>
    {
        ["Hangfire:Enabled"] = "true", ["Hangfire:QueuePollIntervalSeconds"] = pollSeconds.ToString(),
        ["ConnectionStrings:DefaultConnection"] = finance, ["ConnectionStrings:Hangfire"] = hangfire,
    }).Build();

async Task Master(string sql)
{
    await using var connection = new SqlConnection(new SqlConnectionStringBuilder(server) { InitialCatalog = "master" }.ConnectionString);
    await connection.OpenAsync();
    await using var command = new SqlCommand(sql, connection) { CommandTimeout = 120 };
    await command.ExecuteNonQueryAsync();
}

static async Task<int> Scalar(string cs, string sql)
{
    await using var connection = new SqlConnection(cs);
    await connection.OpenAsync();
    await using var command = new SqlCommand(sql, connection);
    return Convert.ToInt32(await command.ExecuteScalarAsync());
}

static async Task<List<string>> Strings(string cs, string sql)
{
    await using var connection = new SqlConnection(cs);
    await connection.OpenAsync();
    await using var command = new SqlCommand(sql, connection);
    await using var reader = await command.ExecuteReaderAsync();
    var values = new List<string>();
    while (await reader.ReadAsync()) values.Add(reader.GetString(0));
    return values;
}

static async Task<string?> Until(Func<Task<string?>> probe, string what, int seconds = 90)
{
    var deadline = DateTime.UtcNow.AddSeconds(seconds);
    while (DateTime.UtcNow < deadline)
    {
        try { if (await probe() is { } value) return value; } catch (SqlException) { }
        await Task.Delay(500);
    }
    throw new TimeoutException("Timed out waiting for: " + what);
}

// Seeds one synthetic member with a bill due within its reminder window, a budget past its alert threshold and a
// transaction last month (summary input), all relative to today in Asia/Manila (the jobs use the real clock).
static async Task<(int UserId, int Dummy)> SeedAsync(string financeCs)
{
    var user = new SeedUser();
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddInfrastructure(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        { ["ConnectionStrings:DefaultConnection"] = financeCs }).Build());
    services.AddDataProtection();
    services.AddSingleton<ICurrentUser>(user);
    services.RemoveAll<IFrankfurterClient>();
    services.AddSingleton<IFrankfurterClient>(new SameCurrencyFx());
    await using var provider = services.BuildServiceProvider();
    async Task<T> In<T>(Func<IServiceProvider, Task<T>> body) { await using var scope = provider.CreateAsyncScope(); return await body(scope.ServiceProvider); }
    await In(async sp => { await sp.GetRequiredService<ApplicationDbContext>().Database.MigrateAsync(); return 0; });
    user.Id = await In(async sp =>
    {
        var account = new ApplicationUser { Email = "worker@example.test", UserName = "worker@example.test", FullName = "Worker Test", LockoutEnabled = true };
        var result = await sp.GetRequiredService<UserManager<ApplicationUser>>().CreateAsync(account, "Synthetic!Pass123");
        if (!result.Succeeded) throw new Exception("user");
        return account.Id;
    });
    var today = HangfireSetup.ManilaToday(TimeProvider.System);
    var month = new DateOnly(today.Year, today.Month, 1);
    T Ok<T>(FinanceResult<T> r) => r.Succeeded ? r.Value! : throw new Exception(r.Error);
    var category = Ok(await In(sp => sp.GetRequiredService<ICategoryService>().CreateAsync(new CategoryWriteRequest { Name = "Utilities", Type = "Expense" }, false, default))).CategoryId;
    Ok(await In(sp => sp.GetRequiredService<IBudgetService>().CreateAsync(new CreateBudgetRequest { CategoryId = category, PeriodMonth = month, LimitAmount = 100m, AlertThresholdPct = 80 }, default)));
    Ok(await In(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(new CreateTransactionRequest { ClientId = Guid.NewGuid(), Type = "Expense", CategoryId = category, Amount = 90m, CurrencyCode = "PHP", TransactionDate = today }, default)));
    Ok(await In(sp => sp.GetRequiredService<ITransactionService>().CreateAsync(new CreateTransactionRequest { ClientId = Guid.NewGuid(), Type = "Expense", CategoryId = category, Amount = 40m, CurrencyCode = "PHP", TransactionDate = month.AddMonths(-1).AddDays(9) }, default)));
    Ok(await In(sp => sp.GetRequiredService<IRecurringBillService>().CreateAsync(new CreateRecurringBillRequest { Name = "Internet", CategoryId = category, Amount = 1500m, CurrencyCode = "PHP", Frequency = "Monthly", FirstDueDate = today.AddDays(2), ReminderDaysBefore = 3 }, default)));
    return (user.Id, 0);
}

internal sealed record RecordedCall(string Id, Job Job, string Cron, RecurringJobOptions Options);
internal sealed class RecordingManager : IRecurringJobManager
{
    public List<RecordedCall> Calls { get; } = [];
    public void AddOrUpdate(string recurringJobId, Job job, string cronExpression, RecurringJobOptions options) => Calls.Add(new(recurringJobId, job, cronExpression, options));
    public void Trigger(string recurringJobId) { }
    public void RemoveIfExists(string recurringJobId) { }
}

internal sealed class SeedUser : ICurrentUser
{
    public int Id;
    public int? UserId => Id == 0 ? null : Id;
    public bool Owns(int resourceOwnerId) => Id != 0 && Id == resourceOwnerId;
    public bool IsAdministrator => false;
}

internal sealed class SameCurrencyFx : IFrankfurterClient
{
    public Task<bool> SupportsAsync(string code, CancellationToken ct) => Task.FromResult(code is "PHP" or "USD");
    public Task<FxQuote> GetRateAsync(string source, string target, DateOnly date, CancellationToken ct) => Task.FromResult(new FxQuote(source, target, date, 1m));
}
