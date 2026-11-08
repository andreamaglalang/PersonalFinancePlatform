using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.FinanceTests;

internal static class BudgetTests
{
    private static void Assert(bool condition, string detail = "Budget assertion failed")
    { if (!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply reply) => JsonDocument.Parse(reply.Body).RootElement;
    private static object Request(int category = 2, string month = "2026-10-01", decimal limit = 100m, int threshold = 80) =>
        new { categoryId = category, periodMonth = month, limitAmount = limit, alertThresholdPct = threshold };
    private static Task<ApiReply> Budget(FinanceFixture f, object? body = null, int user = 1) =>
        f.SendAsync("POST", "/api/budgets", body ?? Request(), f.Issue(user));
    private static async Task<int> CreateBudget(FinanceFixture f, object? body = null)
    {
        var result = await Budget(f, body);
        Assert(result.Status == 201, result.Body);
        return Json(result).GetProperty("budgetId").GetInt32();
    }
    private static Task<ApiReply> Get(FinanceFixture f, int id) => f.SendAsync("GET", $"/api/budgets/{id}", bearer: f.Issue(1));
    private static Task<ApiReply> Transaction(FinanceFixture f, decimal amount = 10m, int category = 2,
        string date = "2026-10-10", string type = "Expense", Guid? client = null, string currency = "PHP") =>
        f.SendAsync("POST", "/api/transactions", new { clientId = client ?? Guid.NewGuid(), categoryId = category,
            transactionDate = date, type, amount, currencyCode = currency }, f.Issue(1));
    private static Task<ApiReply> Edit(FinanceFixture f, int id, decimal amount = 10m, int category = 2,
        string date = "2026-10-10", string type = "Expense") =>
        f.SendAsync("PUT", $"/api/transactions/{id}", new { categoryId = category, transactionDate = date,
            type, amount, currencyCode = "PHP" }, f.Issue(1));
    private static async Task ExpectUsage(FinanceFixture f, int id, decimal spent, string status)
    {
        var result = await Get(f, id);
        Assert(result.Status == 200, result.Body);
        Assert(Json(result).GetProperty("spentAmount").GetDecimal() == spent, result.Body);
        Assert(Json(result).GetProperty("status").GetString() == status, result.Body);
    }

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Actual spending aggregation translates to grouped owner-scoped SQL without connecting", _ =>
        {
            using var db = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseSqlServer().Options);
            var sql = BudgetUsageService.SpendingTotalsQuery(db, 1, [2, 4], new(2026, 10, 1), new(2026, 11, 30)).ToQueryString();
            Assert(sql.Contains("GROUP BY") && sql.Contains("SUM(") && sql.Contains("[AmountBase]"));
            Assert(sql.Contains("DATEPART(year") && sql.Contains("DATEPART(month") && sql.Contains("[UserId] ="));
            Assert(sql.Contains("[Type] = N'Expense'") && sql.Contains("[TransactionDate] >=") && sql.Contains("[TransactionDate] <="), sql);
            Assert(db.Database.GetDbConnection().State == System.Data.ConnectionState.Closed);
            return Task.CompletedTask;
        });
        await check("Budget calculator exact boundaries and small decimal thresholds", _ =>
        {
            foreach (var (spent, expected) in new[] { (0m, BudgetAlertStatus.OK), (79.99m, BudgetAlertStatus.OK),
                (80m, BudgetAlertStatus.Warning), (100m, BudgetAlertStatus.Warning), (100.01m, BudgetAlertStatus.Exceeded) })
                Assert(BudgetStatusCalculator.Calculate(spent, 100m, 80) == expected);
            Assert(BudgetStatusCalculator.Calculate(0m, .01m, 1) == BudgetAlertStatus.OK);
            Assert(BudgetStatusCalculator.Calculate(.01m, .01m, 100) == BudgetAlertStatus.Warning);
            Assert(BudgetStatusCalculator.Calculate(FinanceValidation.MaximumMoney, FinanceValidation.MaximumMoney, 100) == BudgetAlertStatus.Warning);
            return Task.CompletedTask;
        });
        await check("Budget create returns safe calculated base-currency DTO", async f =>
        {
            var id = await CreateBudget(f);
            var r = await Get(f, id);
            Assert(r.CacheControl == "no-store" && !r.Body.Contains("userId", StringComparison.OrdinalIgnoreCase));
            Assert(Json(r).GetProperty("baseCurrency").GetString() == "PHP");
            Assert(Json(r).GetProperty("remainingAmount").GetDecimal() == 100m);
            await ExpectUsage(f, id, 0, "OK");
        });
        await check("Budget creation includes preexisting expenses and excludes income contributions and other users", async f =>
        {
            await Transaction(f, 45);
            await Transaction(f, 50, 1, type: "Income");
            await f.DbAsync(async db =>
            {
                db.SavingsGoals.Add(new() { GoalId = 10, UserId = 1, Name = "Goal", TargetAmount = 100 });
                db.GoalContributions.Add(new() { GoalId = 10, Amount = 30, ContributedOn = new(2026, 10, 10) });
                db.Transactions.Add(new() { UserId = 2, CategoryId = 2, Type = TransactionType.Expense,
                    ClientId = Guid.NewGuid(), Amount = 99, AmountBase = 99, CurrencyCode = "PHP", FxRate = 1,
                    TransactionDate = new(2026, 10, 10), FxRateDate = new(2026, 10, 10) });
                await db.SaveChangesAsync();
            });
            await ExpectUsage(f, await CreateBudget(f), 45, "OK");
        });
        foreach (var cat in new[] { 1, 3, 5, 999 })
            await check("Budget rejects income foreign archived or missing category " + cat, async f =>
                Assert((await Budget(f, Request(cat))).Status == 400));
        await check("Budget accepts system expense category without administrator privilege", async f =>
            Assert((await Budget(f, Request(4))).Status == 201));
        foreach (var limit in new[] { 0m, -1m, 1.001m, 10000000000000000m })
            await check("Budget rejects invalid limit " + limit, async f => Assert((await Budget(f, Request(limit: limit))).Status == 400));
        foreach (var threshold in new[] { 0, 101 })
            await check("Budget rejects invalid threshold " + threshold, async f => Assert((await Budget(f, Request(threshold: threshold))).Status == 400));
        await check("Budget threshold endpoints 1 and 100 accepted", async f =>
        {
            Assert((await Budget(f, Request(threshold: 1))).Status == 201);
            Assert((await Budget(f, Request(month: "2026-11-01", threshold: 100))).Status == 201);
        });
        await check("Budget requires all fields valid date and first day", async f =>
        {
            foreach (var body in new object[] { new { }, Request(month: "2026-10-02"), Request(month: "2026-02-30") })
                Assert((await Budget(f, body)).Status == 400);
        });
        await check("Budget rejects ownership status and immutable-field overposting", async f =>
        {
            Assert((await Budget(f, new { userId = 2, categoryId = 2, periodMonth = "2026-10-01", limitAmount = 100, alertThresholdPct = 80 })).Status == 400);
            var id = await CreateBudget(f);
            foreach (var body in new object[] { Request(), new { limitAmount = 10, alertThresholdPct = 80, status = "OK" },
                new { limitAmount = 10, alertThresholdPct = 80, userId = 2 } })
                Assert((await f.SendAsync("PUT", $"/api/budgets/{id}", body, f.Issue(1))).Status == 400);
        });
        await check("Budget uniqueness owner category month; separate months and owners valid", async f =>
        {
            await CreateBudget(f, Request(4));
            Assert((await Budget(f, Request(4))).Status == 409);
            Assert((await Budget(f, Request(4, "2026-11-01"))).Status == 201);
            Assert((await Budget(f, Request(4), 2)).Status == 201);
        });
        await check("Budget all endpoint verbs reject unauthenticated requests", async f =>
        {
            foreach (var verb in new[] { "GET", "POST" })
                Assert((await f.SendAsync(verb, "/api/budgets", verb == "POST" ? Request() : null)).Status == 401);
            foreach (var verb in new[] { "GET", "PUT", "DELETE" })
                Assert((await f.SendAsync(verb, "/api/budgets/1", verb == "PUT" ? Request() : null)).Status == 401);
        });
        await check("Budgets require HTTPS and forbid caching", async f =>
        {
            var r = await f.SendAsync("GET", "/api/budgets", bearer: f.Issue(1), scheme: "http");
            Assert(r.Status == 400 && r.CacheControl == "no-store");
        });
        await check("Budget privacy includes administrator no bypass and owned list", async f =>
        {
            var id = await CreateBudget(f);
            foreach (var user in new[] { 2, 3 })
            {
                foreach (var verb in new[] { "GET", "PUT", "DELETE" })
                    Assert((await f.SendAsync(verb, $"/api/budgets/{id}", verb == "PUT" ? new { limitAmount = 20, alertThresholdPct = 80 } : null, f.Issue(user))).Status == 404);
                var r = await f.SendAsync("GET", "/api/budgets", bearer: f.Issue(user));
                Assert(Json(r).GetProperty("totalCount").GetInt64() == 0);
            }
        });
        await check("Budget list month category pagination stable order and invalid filters", async f =>
        {
            await CreateBudget(f); await CreateBudget(f, Request(4)); await CreateBudget(f, Request(month: "2026-11-01"));
            var r = await f.SendAsync("GET", "/api/budgets?pageSize=1", bearer: f.Issue(1));
            Assert(Json(r).GetProperty("totalCount").GetInt64() == 3 && Json(r).GetProperty("items")[0].GetProperty("periodMonth").GetString() == "2026-11-01");
            r = await f.SendAsync("GET", "/api/budgets?periodMonth=2026-10-01&categoryId=2", bearer: f.Issue(1));
            Assert(Json(r).GetProperty("totalCount").GetInt64() == 1);
            foreach (var q in new[] { "periodMonth=2026-10-02", "categoryId=0", "page=0", "pageSize=101", "page=2147483647&pageSize=100" })
                Assert((await f.SendAsync("GET", "/api/budgets?" + q, bearer: f.Issue(1))).Status == 400);
        });
        await check("Archived category retained on read and update; new assignments denied", async f =>
        {
            var id = await CreateBudget(f);
            await f.DbAsync(async db => { (await db.Categories.SingleAsync(c => c.CategoryId == 2)).IsActive = false; await db.SaveChangesAsync(); });
            var r = await f.SendAsync("PUT", $"/api/budgets/{id}", new { limitAmount = 200, alertThresholdPct = 90 }, f.Issue(1));
            Assert(r.Status == 200 && !Json(r).GetProperty("isCategoryActive").GetBoolean());
            Assert((await Budget(f, Request(month: "2026-11-01"))).Status == 400);
        });
        await check("Budget edits recalculate threshold without marking alert delivered", async f =>
        {
            var id = await CreateBudget(f);
            await Transaction(f, 90);
            await ExpectUsage(f, id, 90, "Warning");
            var r = await f.SendAsync("PUT", $"/api/budgets/{id}", new { limitAmount = 80, alertThresholdPct = 80 }, f.Issue(1));
            Assert(r.Status == 200 && Json(r).GetProperty("status").GetString() == "Exceeded");
            Assert(Json(r).GetProperty("remainingAmount").GetDecimal() == -10m);
            await f.DbAsync(async db =>
            {
                var b = await db.Budgets.SingleAsync();
                Assert(b.LastAlertStatus is null && b.LastAlertAtUtc is null && !await db.Notifications.AnyAsync());
            });
        });
        await check("Transaction create impacts expense budget only and uses AmountBase snapshot", async f =>
        {
            var id = await CreateBudget(f);
            var r = await Transaction(f, 2, currency: "USD");
            Assert(r.Status == 201 && Json(r).GetProperty("affectedBudgets").GetArrayLength() == 1);
            await ExpectUsage(f, id, 112.25m, "Exceeded");
            r = await Transaction(f, 100, 1, type: "Income");
            Assert(Json(r).GetProperty("affectedBudgets").GetArrayLength() == 0);
            await ExpectUsage(f, id, 112.25m, "Exceeded");
        });
        await check("Transaction month/category moves recalculate old and new budgets", async f =>
        {
            var oldId = await CreateBudget(f);
            var newId = await CreateBudget(f, Request(4, "2026-11-01"));
            var tx = Json(await Transaction(f, 85)).GetProperty("transactionId").GetInt32();
            var r = await Edit(f, tx, 110, 4, "2026-11-10");
            Assert(r.Status == 200 && Json(r).GetProperty("affectedBudgets").GetArrayLength() == 2);
            await ExpectUsage(f, oldId, 0, "OK"); await ExpectUsage(f, newId, 110, "Exceeded");
        });
        await check("Expense to income and income to expense recalculate proper periods", async f =>
        {
            var id = await CreateBudget(f);
            var tx = Json(await Transaction(f, 85)).GetProperty("transactionId").GetInt32();
            Assert((await Edit(f, tx, 85, 1, type: "Income")).Status == 200);
            await ExpectUsage(f, id, 0, "OK");
            Assert((await Edit(f, tx, 100)).Status == 200);
            await ExpectUsage(f, id, 100, "Warning");
        });
        await check("Transaction DELETE HTTP 200 recalculates budget and preserves history timestamp", async f =>
        {
            var id = await CreateBudget(f);
            var tx = Json(await Transaction(f, 85)).GetProperty("transactionId").GetInt32();
            var deliveredAt = new DateTime(2026, 10, 10, 0, 0, 0, DateTimeKind.Utc);
            await f.DbAsync(async db =>
            {
                var b = await db.Budgets.SingleAsync(); b.LastAlertStatus = BudgetAlertStatus.Warning; b.LastAlertAtUtc = deliveredAt;
                await db.SaveChangesAsync();
            });
            var r = await f.SendAsync("DELETE", $"/api/transactions/{tx}", bearer: f.Issue(1));
            Assert(r.Status == 200 && Json(r).GetProperty("deleted").GetBoolean());
            Assert(Json(r).GetProperty("affectedBudgets")[0].GetProperty("status").GetString() == "OK");
            await ExpectUsage(f, id, 0, "OK");
            await f.DbAsync(async db =>
            {
                var b = await db.Budgets.SingleAsync();
                Assert(b.LastAlertStatus is null && b.LastAlertAtUtc == deliveredAt && !await db.Notifications.AnyAsync());
            });
        });
        await check("Budget reads never reset delivered state but limit changes to OK do", async f =>
        {
            var id = await CreateBudget(f);
            await Transaction(f, 85);
            await f.DbAsync(async db => { (await db.Budgets.SingleAsync()).LastAlertStatus = BudgetAlertStatus.Warning; await db.SaveChangesAsync(); });
            await Get(f, id);
            await f.DbAsync(async db => Assert((await db.Budgets.SingleAsync()).LastAlertStatus == BudgetAlertStatus.Warning));
            await f.SendAsync("PUT", $"/api/budgets/{id}", new { limitAmount = 200, alertThresholdPct = 80 }, f.Issue(1));
            await f.DbAsync(async db => Assert((await db.Budgets.SingleAsync()).LastAlertStatus is null));
        });
        await check("Idempotent replay uses stored scope and never adds spending or alters alert delivery state", async f =>
        {
            var id = await CreateBudget(f); var client = Guid.NewGuid();
            await Transaction(f, 10, client: client);
            var r = await Transaction(f, 99, 4, "2026-11-10", client: client);
            Assert(r.Status == 200 && Json(r).GetProperty("amount").GetDecimal() == 10);
            Assert(Json(r).GetProperty("affectedBudgets")[0].GetProperty("budgetId").GetInt32() == id);
            await ExpectUsage(f, id, 10, "OK");
            await f.DbAsync(async db => Assert(await db.Transactions.CountAsync() == 1));
        });
        await check("Budget deletion preserves transactions and permits subsequent recreation", async f =>
        {
            var id = await CreateBudget(f); await Transaction(f, 10);
            Assert((await f.SendAsync("DELETE", $"/api/budgets/{id}", bearer: f.Issue(1))).Status == 204);
            Assert((await Get(f, id)).Status == 404);
            await f.DbAsync(async db => Assert(await db.Transactions.CountAsync() == 1));
            await ExpectUsage(f, await CreateBudget(f), 10, "OK");
        });
        foreach (var refType in new[] { "Budget", "budget", "BUDGET" })
            await check("Notification history blocks budget deletion regardless read/delivery " + refType, async f =>
            {
                var id = await CreateBudget(f);
                await f.DbAsync(async db =>
                {
                    db.Notifications.Add(new() { UserId = 1, Type = "BudgetAlert", ReferenceType = refType,
                        ReferenceId = id, Message = "History", DeduplicationKey = Guid.NewGuid().ToString(),
                        IsRead = true, EmailSentAtUtc = DateTime.UtcNow });
                    await db.SaveChangesAsync();
                });
                Assert((await f.SendAsync("DELETE", $"/api/budgets/{id}", bearer: f.Issue(1))).Status == 409);
                await f.DbAsync(async db => Assert(await db.Budgets.CountAsync() == 1 && await db.Notifications.CountAsync() == 1));
            });
        await check("Unrelated notification reference does not prevent budget deletion", async f =>
        {
            var id = await CreateBudget(f);
            await f.DbAsync(async db =>
            {
                db.Notifications.Add(new() { UserId = 1, Type = "BillReminder", ReferenceType = "RecurringBill", ReferenceId = id,
                    Message = "Bill", DeduplicationKey = "bill:unrelated" }); await db.SaveChangesAsync();
            });
            Assert((await f.SendAsync("DELETE", $"/api/budgets/{id}", bearer: f.Issue(1))).Status == 204);
        });
        await check("Spending month edges inclusive and December 9999 safe", async f =>
        {
            var id = await CreateBudget(f);
            await Transaction(f, 1, date: "2026-09-30"); await Transaction(f, 2, date: "2026-10-01");
            await Transaction(f, 3, date: "2026-10-31"); await Transaction(f, 4, date: "2026-11-01");
            await ExpectUsage(f, id, 5, "OK");
            await ExpectUsage(f, await CreateBudget(f, Request(month: "9999-12-01")), 0, "OK");
        });
        await check("Concurrent budget creation serialized with one conflict and no duplicates (test scope)", async f =>
        {
            var results = await Task.WhenAll(Budget(f), Budget(f));
            Assert(results.Count(r => r.Status == 201) == 1 && results.Count(r => r.Status == 409) == 1);
            await f.DbAsync(async db => Assert(await db.Budgets.CountAsync() == 1));
        });
        await check("Concurrent transaction idempotency returns one counted expense budget (test scope)", async f =>
        {
            var id = await CreateBudget(f); var client = Guid.NewGuid();
            var results = await Task.WhenAll(Transaction(f, 20, client: client), Transaction(f, 20, client: client));
            Assert(results.All(r => r.Status is 200 or 201));
            await ExpectUsage(f, id, 20, "OK");
        });
        await check("Concurrent budget edit is rejected without overwriting newer value", async f =>
        {
            var id = await CreateBudget(f);
            var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
            scope.BeforeWrite = () => f.DbAsync(async db =>
            { (await db.Budgets.SingleAsync()).LimitAmount = 300; await db.SaveChangesAsync(); });
            Assert((await f.SendAsync("PUT", $"/api/budgets/{id}", new { limitAmount = 200, alertThresholdPct = 80 }, f.Issue(1))).Status == 409);
            Assert(Json(await Get(f, id)).GetProperty("limitAmount").GetDecimal() == 300);
        });
        await check("Budget category archive race revalidated before insert", async f =>
        {
            var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
            scope.BeforeWrite = () => f.DbAsync(async db =>
            { (await db.Categories.SingleAsync(c => c.CategoryId == 2)).IsActive = false; await db.SaveChangesAsync(); });
            Assert((await Budget(f)).Status == 400);
            await f.DbAsync(async db => Assert(!await db.Budgets.AnyAsync()));
        });
        await check("Canonical budget-transition deduplication key also protects notification history", async f =>
        {
            var id = await CreateBudget(f);
            await f.DbAsync(async db =>
            {
                db.Notifications.Add(new() { UserId = 1, Type = "BudgetAlert", Message = "Legacy reference",
                    DeduplicationKey = NotificationKeys.BudgetTransition(id, BudgetAlertStatus.Warning, Guid.NewGuid()) });
                await db.SaveChangesAsync();
            });
            Assert((await f.SendAsync("DELETE", $"/api/budgets/{id}", bearer: f.Issue(1))).Status == 409);
            await f.DbAsync(async db => Assert(await db.Notifications.CountAsync() == 1 && await db.Budgets.CountAsync() == 1));
        });
        await check("Repeated Warning to OK to Warning never writes delivery markers or notifications", async f =>
        {
            var id = await CreateBudget(f);
            var tx = Json(await Transaction(f, 80)).GetProperty("transactionId").GetInt32();
            await ExpectUsage(f, id, 80, "Warning");
            await Edit(f, tx, 10); await ExpectUsage(f, id, 10, "OK");
            await Edit(f, tx, 80); await ExpectUsage(f, id, 80, "Warning");
            await f.DbAsync(async db =>
            {
                var b = await db.Budgets.SingleAsync();
                Assert(b.LastAlertStatus is null && b.LastAlertAtUtc is null && !await db.Notifications.AnyAsync());
            });
        });
        await check("Concurrent distinct expenses counted together under serialized test write scope", async f =>
        {
            var id = await CreateBudget(f);
            var results = await Task.WhenAll(Transaction(f, 40), Transaction(f, 45));
            Assert(results.All(r => r.Status == 201));
            await ExpectUsage(f, id, 85, "Warning");
        });
        await check("Concurrent budget deletion conflict preserves edits and financial records", async f =>
        {
            var id = await CreateBudget(f); await Transaction(f);
            var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
            scope.BeforeWrite = () => f.DbAsync(async db =>
            { (await db.Budgets.SingleAsync()).LimitAmount = 300; await db.SaveChangesAsync(); });
            Assert((await f.SendAsync("DELETE", $"/api/budgets/{id}", bearer: f.Issue(1))).Status == 409);
            await f.DbAsync(async db => Assert(await db.Budgets.CountAsync() == 1 && await db.Transactions.CountAsync() == 1));
        });
        await check("Budget update validates amount threshold and missing fields before mutation", async f =>
        {
            var id = await CreateBudget(f);
            foreach (var body in new object[] { new { }, new { limitAmount = 0, alertThresholdPct = 80 },
                new { limitAmount = 1.001m, alertThresholdPct = 80 }, new { limitAmount = 10, alertThresholdPct = 101 } })
                Assert((await f.SendAsync("PUT", $"/api/budgets/{id}", body, f.Issue(1))).Status == 400);
            Assert(Json(await Get(f, id)).GetProperty("limitAmount").GetDecimal() == 100);
        });
        await check("Bill payment rejection leaves budget spending unchanged", async f =>
        {
            var id = await CreateBudget(f);
            var tx = Json(await Transaction(f, 20)).GetProperty("transactionId").GetInt32();
            await f.DbAsync(async db =>
            {
                var row = await db.Transactions.SingleAsync(); row.BillId = 10; row.BillDueDate = new(2026, 10, 10);
                await db.SaveChangesAsync();
            });
            Assert((await f.SendAsync("DELETE", $"/api/transactions/{tx}", bearer: f.Issue(1))).Status == 409);
            await ExpectUsage(f, id, 20, "OK");
        });
        foreach (var code in new[] { 2601, 2627 })
            await check("Budget unique SQL violation safe 409 " + code, async f =>
            {
                var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
                scope.BeforeWrite = () => throw new DbUpdateException("PRIVATE_SQL_DETAIL", SyntheticSqlError.Create(code));
                var r = await Budget(f);
                Assert(r.Status == 409 && !r.Body.Contains("PRIVATE_SQL_DETAIL") && !r.Body.Contains("IX_Budgets"));
            });
    }
}