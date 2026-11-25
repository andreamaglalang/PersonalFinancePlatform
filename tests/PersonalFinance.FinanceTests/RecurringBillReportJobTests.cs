using System.Text.Json;
using Hangfire;
using Hangfire.Common;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Jobs;

namespace PersonalFinance.FinanceTests;

/// <summary>Recurring-bill endpoints, monthly report endpoint and finance jobs (in-memory EF, real JWT/MVC pipeline).</summary>
internal static class RecurringBillReportJobTests
{
    private sealed record RecurringJobCall(string Id, Job Job, string Cron, RecurringJobOptions Options);
    private sealed class RecordingRecurringJobManager : IRecurringJobManager
    {
        public List<RecurringJobCall> Calls { get; } = [];
        public void AddOrUpdate(string recurringJobId, Job job, string cronExpression, RecurringJobOptions options) =>
            Calls.Add(new(recurringJobId, job, cronExpression, options));
        public void Trigger(string recurringJobId) { }
        public void RemoveIfExists(string recurringJobId) { }
    }
    private static void RejectStorage(string storage, string finance)
    {
        try { HangfireSetup.EnsureDedicatedStorage(storage, finance); }
        catch (InvalidOperationException) { return; }
        throw new Exception("Unsafe Hangfire storage was accepted.");
    }
    private static void Assert(bool condition, string detail = "Assertion failed") { if (!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
    private static object Bill(int category = 2, string frequency = "Monthly", decimal amount = 999m, string currency = "PHP",
        string first = "2026-11-10", int reminder = 3, string name = "Internet") =>
        new { name, categoryId = category, amount, currencyCode = currency, reminderDaysBefore = reminder, frequency, firstDueDate = first };
    private static async Task<ApiReply> Create(FinanceFixture f, object body, int user = 1) => await f.SendAsync("POST", "/api/recurring-bills", body, f.Issue(user));
    private static async Task<int> Created(FinanceFixture f, object? body = null)
    { var r = await Create(f, body ?? Bill()); Assert(r.Status == 201, r.Body); return Json(r).GetProperty("billId").GetInt32(); }
    private static async Task<T> Job<T>(FinanceFixture f, Func<FinanceJobs, Task<T>> run)
    { using var scope = f.Services.CreateScope(); return await run(scope.ServiceProvider.GetRequiredService<FinanceJobs>()); }
    private static async Task<ApiReply> Expense(FinanceFixture f, decimal amount, string date, int category = 2, string type = "Expense", string currency = "PHP") =>
        await f.SendAsync("POST", "/api/transactions", new CreateTransactionRequest { ClientId = Guid.NewGuid(), CategoryId = category, Type = type,
            Amount = amount, CurrencyCode = currency, TransactionDate = DateOnly.Parse(date), Description = "t" }, f.Issue(1));

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        // ---------- Recurring bills ----------
        await check("Bill create sets schedule from first due date", async f =>
        {
            var r = await Create(f, Bill());
            Assert(r.Status == 201 && r.Location is not null, r.Body);
            var j = Json(r);
            Assert(j.GetProperty("nextDueDate").GetString() == "2026-11-10" && j.GetProperty("firstDueDate").GetString() == "2026-11-10");
            Assert(j.GetProperty("frequency").GetString() == "Monthly" && j.GetProperty("categoryName").GetString() == "Food");
            Assert(!r.Body.Contains("userId", StringComparison.OrdinalIgnoreCase));
            await f.DbAsync(async db => Assert((await db.RecurringBills.SingleAsync()).IntendedDayOfMonth == 10));
        });
        await check("Weekly bills keep no intended day", async f =>
        {
            await Created(f, Bill(frequency: "Weekly"));
            await f.DbAsync(async db => Assert((await db.RecurringBills.SingleAsync()).IntendedDayOfMonth is null));
        });
        foreach (var (label, body) in new (string, object)[]
        {
            ("bad frequency", Bill(frequency: "Daily")), ("income category", Bill(category: 1)), ("other user's category", Bill(category: 3)),
            ("inactive category", Bill(category: 5)), ("zero amount", Bill(amount: 0m)), ("three decimals", Bill(amount: 1.005m)),
            ("lowercase currency", Bill(currency: "php")), ("reminder over 30", Bill(reminder: 31)), ("blank name", Bill(name: "  ")),
        })
            await check("Bill validation rejects " + label, async f => Assert((await Create(f, body)).Status == 400));
        await check("Bill create rejects unknown fields (overposting)", async f =>
            Assert((await f.SendAsync("POST", "/api/recurring-bills", new { name = "x", categoryId = 2, amount = 1, currencyCode = "PHP",
                reminderDaysBefore = 0, frequency = "Monthly", firstDueDate = "2026-11-10", userId = 2 }, f.Issue(1))).Status == 400));
        await check("Default categories are allowed for bills", async f => await Created(f, Bill(category: 4)));
        await check("Bills require authentication", async f => Assert((await f.SendAsync("GET", "/api/recurring-bills")).Status == 401));

        await check("Bills are invisible to other users", async f =>
        {
            var id = await Created(f);
            foreach (var user in new[] { 2, 3 })
            {
                Assert((await f.SendAsync("GET", $"/api/recurring-bills/{id}", bearer: f.Issue(user))).Status == 404);
                Assert((await f.SendAsync("PUT", $"/api/recurring-bills/{id}", new { name = "x", categoryId = 4, amount = 1, currencyCode = "PHP", reminderDaysBefore = 0 }, f.Issue(user))).Status == 404);
                Assert((await f.SendAsync("DELETE", $"/api/recurring-bills/{id}", bearer: f.Issue(user))).Status == 404);
                Assert((await f.SendAsync("POST", $"/api/recurring-bills/{id}/payments", new PayBillRequest { ClientId = Guid.NewGuid(), DueDate = new(2026, 11, 10) }, f.Issue(user))).Status == 404);
                Assert(Json(await f.SendAsync("GET", "/api/recurring-bills", bearer: f.Issue(user))).GetProperty("totalCount").GetInt64() == 0);
            }
        });

        await check("Bill list orders by next due date, hides inactive and filters due-soon", async f =>
        {
            await Created(f, Bill(first: "2026-11-20", name: "Late"));
            var early = await Created(f, Bill(first: "2026-11-05", name: "Early"));
            var off = await Created(f, Bill(first: "2026-11-01", name: "Off"));
            Assert((await f.SendAsync("PUT", $"/api/recurring-bills/{off}", new { name = "Off", categoryId = 2, amount = 1, currencyCode = "PHP", reminderDaysBefore = 0, isActive = false }, f.Issue(1))).Status == 200);
            var list = Json(await f.SendAsync("GET", "/api/recurring-bills", bearer: f.Issue(1))).GetProperty("items");
            Assert(list.GetArrayLength() == 2 && list[0].GetProperty("billId").GetInt32() == early);
            Assert(Json(await f.SendAsync("GET", "/api/recurring-bills?includeInactive=true", bearer: f.Issue(1))).GetProperty("totalCount").GetInt64() == 3);
            Assert(Json(await f.SendAsync("GET", "/api/recurring-bills?dueOnOrBefore=2026-11-10", bearer: f.Issue(1))).GetProperty("totalCount").GetInt64() == 1);
        });

        await check("Bill update changes editable fields but never the schedule", async f =>
        {
            var id = await Created(f);
            var r = await f.SendAsync("PUT", $"/api/recurring-bills/{id}", new { name = "Fibre", categoryId = 4, amount = 1299.50m, currencyCode = "USD", reminderDaysBefore = 5 }, f.Issue(1));
            Assert(r.Status == 200, r.Body);
            var j = Json(r);
            Assert(j.GetProperty("name").GetString() == "Fibre" && j.GetProperty("amount").GetDecimal() == 1299.50m && j.GetProperty("currencyCode").GetString() == "USD");
            Assert(j.GetProperty("nextDueDate").GetString() == "2026-11-10");
            Assert((await f.SendAsync("PUT", $"/api/recurring-bills/{id}", new { name = "x", categoryId = 2, amount = 1, currencyCode = "PHP", reminderDaysBefore = 0, frequency = "Weekly" }, f.Issue(1))).Status == 400);
        });

        await check("Paying a bill through the endpoint is idempotent and advances the due date", async f =>
        {
            var id = await Created(f, Bill(first: "2026-11-03"));
            var pay = new PayBillRequest { ClientId = Guid.NewGuid(), DueDate = new(2026, 11, 3) };
            var first = await f.SendAsync("POST", $"/api/recurring-bills/{id}/payments", pay, f.Issue(1));
            Assert(first.Status == 201, first.Body);
            Assert(!first.Body.Contains("userId", StringComparison.OrdinalIgnoreCase), "receipt must not expose userId");
            var replay = await f.SendAsync("POST", $"/api/recurring-bills/{id}/payments", pay, f.Issue(1));
            Assert(replay.Status == 200 && Json(replay).GetProperty("replayed").GetBoolean(), replay.Body);
            Assert(Json(first).GetProperty("receipt").GetProperty("transactionId").GetInt32() == Json(replay).GetProperty("receipt").GetProperty("transactionId").GetInt32());
            await f.DbAsync(async db =>
            {
                Assert(await db.Transactions.CountAsync(t => t.BillId == id) == 1, "exactly one expense for the payment");
                Assert((await db.RecurringBills.SingleAsync()).NextDueDate == new DateOnly(2026, 12, 3));
            });
            var del = await f.SendAsync("DELETE", $"/api/recurring-bills/{id}", bearer: f.Issue(1));
            Assert(del.Status == 409, "paid bills are deactivated, not deleted");
        });

        await check("Unpaid bill can be deleted", async f =>
        {
            var id = await Created(f);
            Assert((await f.SendAsync("DELETE", $"/api/recurring-bills/{id}", bearer: f.Issue(1))).Status == 204);
            Assert((await f.SendAsync("GET", $"/api/recurring-bills/{id}", bearer: f.Issue(1))).Status == 404);
        });

        // ---------- Monthly report ----------
        await check("Monthly report totals base amounts by type and category", async f =>
        {
            Assert((await Expense(f, 100m, "2026-10-05", category: 1, type: "Income")).Status == 201);
            Assert((await Expense(f, 10m, "2026-10-06")).Status == 201);
            Assert((await Expense(f, 2m, "2026-10-07", category: 4)).Status == 201);
            Assert((await Expense(f, 1m, "2026-10-08", currency: "USD")).Status == 201); // converted with the fake rate
            Assert((await Expense(f, 50m, "2026-11-01")).Status == 201); // other month: excluded
            var r = await f.SendAsync("GET", "/api/reports/monthly?month=2026-10-01", bearer: f.Issue(1));
            Assert(r.Status == 200, r.Body);
            var j = Json(r);
            decimal usd = 0m;
            await f.DbAsync(async db => usd = (await db.Transactions.SingleAsync(t => t.CurrencyCode == "USD")).AmountBase);
            Assert(j.GetProperty("totalIncome").GetDecimal() == 100m);
            Assert(j.GetProperty("totalExpenses").GetDecimal() == 12m + usd, "expenses use AmountBase");
            Assert(j.GetProperty("netCashFlow").GetDecimal() == 100m - 12m - usd);
            Assert(j.GetProperty("transactionCount").GetInt32() == 4 && j.GetProperty("baseCurrency").GetString() == "PHP");
            var cats = j.GetProperty("expenseByCategory");
            Assert(cats.GetArrayLength() == 2 && cats[0].GetProperty("categoryName").GetString() == "Food");
            Assert(j.GetProperty("incomeByCategory")[0].GetProperty("total").GetDecimal() == 100m);
        });
        await check("Monthly report is per user and validates the month", async f =>
        {
            Assert((await Expense(f, 10m, "2026-10-06")).Status == 201);
            var other = Json(await f.SendAsync("GET", "/api/reports/monthly?month=2026-10-01", bearer: f.Issue(2)));
            Assert(other.GetProperty("totalExpenses").GetDecimal() == 0m && other.GetProperty("transactionCount").GetInt32() == 0);
            Assert((await f.SendAsync("GET", "/api/reports/monthly?month=2026-10-02", bearer: f.Issue(1))).Status == 400);
            Assert((await f.SendAsync("GET", "/api/reports/monthly", bearer: f.Issue(1))).Status == 400);
            Assert((await f.SendAsync("GET", "/api/reports/monthly?month=2026-10-01")).Status == 401);
        });
        await check("Monthly report includes the month's budgets with server status", async f =>
        {
            Assert((await f.SendAsync("POST", "/api/budgets", new CreateBudgetRequest { CategoryId = 2, PeriodMonth = new(2026, 10, 1), LimitAmount = 5m, AlertThresholdPct = 80 }, f.Issue(1))).Status == 201);
            Assert((await Expense(f, 10m, "2026-10-06")).Status == 201);
            var b = Json(await f.SendAsync("GET", "/api/reports/monthly?month=2026-10-01", bearer: f.Issue(1))).GetProperty("budgets");
            Assert(b.GetArrayLength() == 1 && b[0].GetProperty("status").GetString() == "Exceeded");
        });

        // ---------- Jobs (no Hangfire storage involved: the job class is invoked directly) ----------
        await check("Bill reminder job notifies once per bill and due date", async f =>
        {
            await Created(f, Bill(first: "2026-11-05", reminder: 3));   // due in 2 days: inside the window
            await Created(f, Bill(first: "2026-11-20", reminder: 3, name: "Later")); // outside the window
            Assert(await Job(f, j => j.SendBillRemindersAsync(default)) == 1);
            Assert(await Job(f, j => j.SendBillRemindersAsync(default)) == 0, "re-run must not duplicate");
            await f.DbAsync(async db =>
            {
                var n = await db.Notifications.SingleAsync();
                Assert(n.UserId == 1 && n.Type == FinanceJobs.BillReminderType && n.DeduplicationKey == "bill:1:due:2026-11-05", n.DeduplicationKey);
            });
        });
        await check("Inactive bills get no reminders", async f =>
        {
            var id = await Created(f, Bill(first: "2026-11-04", reminder: 3));
            Assert((await f.SendAsync("PUT", $"/api/recurring-bills/{id}", new { name = "x", categoryId = 2, amount = 1, currencyCode = "PHP", reminderDaysBefore = 3, isActive = false }, f.Issue(1))).Status == 200);
            Assert(await Job(f, j => j.SendBillRemindersAsync(default)) == 0);
        });
        await check("Budget alert job alerts on each new threshold crossing only", async f =>
        {
            Assert((await f.SendAsync("POST", "/api/budgets", new CreateBudgetRequest { CategoryId = 2, PeriodMonth = new(2026, 11, 1), LimitAmount = 10m, AlertThresholdPct = 80 }, f.Issue(1))).Status == 201);
            Assert((await Expense(f, 5m, "2026-11-02")).Status == 201);
            Assert(await Job(f, j => j.SendBudgetAlertsAsync(default)) == 0, "50% is below the alert level");
            Assert((await Expense(f, 3.5m, "2026-11-02")).Status == 201); // 85%
            Assert(await Job(f, j => j.SendBudgetAlertsAsync(default)) == 1);
            Assert(await Job(f, j => j.SendBudgetAlertsAsync(default)) == 0, "same status: no duplicate");
            Assert((await Expense(f, 2m, "2026-11-02")).Status == 201); // 105%
            Assert(await Job(f, j => j.SendBudgetAlertsAsync(default)) == 1);
            await f.DbAsync(async db =>
            {
                var types = await db.Notifications.OrderBy(n => n.NotificationId).Select(n => n.Message).ToListAsync();
                Assert(types.Count == 2 && types[0].Contains("reached 80%") && types[1].Contains("exceeded"), string.Join(" | ", types));
                Assert(await db.Notifications.AllAsync(n => n.UserId == 1 && n.Type == FinanceJobs.BudgetAlertType));
                Assert((await db.Budgets.SingleAsync()).LastAlertStatus == BudgetAlertStatus.Exceeded);
            });
        });
        await check("Monthly summary job writes last month once per active user", async f =>
        {
            Assert((await Expense(f, 100m, "2026-10-05", category: 1, type: "Income")).Status == 201);
            Assert((await Expense(f, 40m, "2026-10-06")).Status == 201);
            Assert((await Expense(f, 7m, "2026-11-01")).Status == 201); // current month: not summarised
            Assert(await Job(f, j => j.GenerateMonthlySummariesAsync(default)) == 1);
            Assert(await Job(f, j => j.GenerateMonthlySummariesAsync(default)) == 0, "re-run must not duplicate");
            await f.DbAsync(async db =>
            {
                var s = await db.MonthlySummaries.SingleAsync();
                Assert(s.UserId == 1 && s.SummaryMonth == new DateOnly(2026, 10, 1) && s.TotalIncome == 100m && s.TotalExpense == 40m && s.NetCashFlow == 60m);
                Assert(await db.Notifications.CountAsync(n => n.Type == FinanceJobs.MonthlySummaryType) == 1);
            });
        });
        await check("Hangfire storage requires the dedicated OWLI_Hangfire catalog", f =>
        {
            const string finance = @"Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Integrated Security=True";
            const string hangfire = @"Server=(localdb)\MSSQLLocalDB;Database=OWLI_Hangfire;Integrated Security=True";
            HangfireSetup.EnsureDedicatedStorage(hangfire, finance);
            RejectStorage(@"Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Integrated Security=True", finance);
            RejectStorage(@"Server=(localdb)\MSSQLLocalDB;Database=master;Integrated Security=True", finance);
            RejectStorage(@"Server=other;Database=OWLI_Hangfire;Integrated Security=True", finance);
            return Task.CompletedTask;
        });
        await check("Hangfire recurring registrations use Manila schedules and stable IDs", f =>
        {
            var manager = new RecordingRecurringJobManager();
            HangfireSetup.RegisterRecurringJobs(manager);
            Assert(manager.Calls.Count == 3);
            var bill = manager.Calls.Single(x => x.Id == HangfireSetup.BillRemindersJobId);
            var budget = manager.Calls.Single(x => x.Id == HangfireSetup.BudgetAlertsJobId);
            var monthly = manager.Calls.Single(x => x.Id == HangfireSetup.MonthlySummariesJobId);
            Assert(bill.Cron == Cron.Daily(7) && bill.Options.TimeZone.Id == "Asia/Manila" && bill.Job.Method.Name == nameof(FinanceJobs.SendBillRemindersAsync));
            Assert(budget.Cron == Cron.Hourly() && budget.Options.TimeZone.Id == "Asia/Manila" && budget.Job.Method.Name == nameof(FinanceJobs.SendBudgetAlertsAsync));
            Assert(monthly.Cron == Cron.Monthly(1, 0, 30) && monthly.Options.TimeZone.Id == "Asia/Manila" && monthly.Job.Method.Name == nameof(FinanceJobs.GenerateMonthlySummariesAsync));
            return Task.CompletedTask;
        });
        await check("Finance job dates and bill reminders follow Philippine midnight", async f =>
        {
            f.Clock.Now = new DateTimeOffset(2026, 11, 2, 15, 59, 59, TimeSpan.Zero);
            Assert(HangfireSetup.ManilaToday(f.Clock) == new DateOnly(2026, 11, 2));
            f.Clock.Now = new DateTimeOffset(2026, 11, 2, 16, 0, 0, TimeSpan.Zero);
            Assert(HangfireSetup.ManilaToday(f.Clock) == new DateOnly(2026, 11, 3));
            await Created(f, Bill(first: "2026-11-03", reminder: 0));
            Assert(await Job(f, j => j.SendBillRemindersAsync(default)) == 1);
        });
        await check("Monthly summary uses Manila month boundary", async f =>
        {
            f.Clock.Now = new DateTimeOffset(2026, 10, 31, 16, 0, 0, TimeSpan.Zero);
            Assert(HangfireSetup.ManilaToday(f.Clock) == new DateOnly(2026, 11, 1));
            Assert((await Expense(f, 100m, "2026-10-05", category: 1, type: "Income")).Status == 201);
            Assert(await Job(f, j => j.GenerateMonthlySummariesAsync(default)) == 1);
            await f.DbAsync(async db => Assert((await db.MonthlySummaries.SingleAsync()).SummaryMonth == new DateOnly(2026, 10, 1)));
        });
        await check("Hangfire stays disabled by default", f =>
        {
            Assert(!HangfireSetup.IsEnabled(new Microsoft.Extensions.Configuration.ConfigurationBuilder().Build()));
            Assert(f.Services.GetService<Hangfire.IRecurringJobManager>() is null, "no Hangfire services without Hangfire:Enabled=true");
            return Task.CompletedTask;
        });
    }
}
