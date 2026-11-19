using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.FinanceTests;

/// <summary>Read-only notifications API and the Development-only job trigger (in-memory EF, real JWT/MVC pipeline).</summary>
internal static class NotificationDevJobTests
{
    private static void Assert(bool condition, string detail = "Assertion failed") { if (!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
    private static Notification Note(int user, string message, bool read = false, int minutes = 0) => new()
    {
        UserId = user, Type = "BudgetAlert", ReferenceType = "Budget", ReferenceId = 1, Message = message, IsRead = read,
        DeduplicationKey = $"test:{user}:{message}", CreatedAtUtc = new DateTime(2026, 11, 3, 12, 0, 0, DateTimeKind.Utc).AddMinutes(minutes),
    };
    private static Task Seed(FinanceFixture f, params Notification[] rows) =>
        f.DbAsync(async db => { db.Notifications.AddRange(rows); await db.SaveChangesAsync(); });
    private static Task<ApiReply> List(FinanceFixture f, int user, string query = "") =>
        f.SendAsync("GET", "/api/notifications" + query, bearer: f.Issue(user));
    private static Task<ApiReply> Run(FinanceFixture f, string job, int? user = 3, string scheme = "https") =>
        f.SendAsync("POST", $"/api/dev/jobs/{job}/run", bearer: user is int id ? f.Issue(id) : null, scheme: scheme);

    /// <summary>Runs a check against a host in the Development environment (the shared Check helper uses "Testing").</summary>
    private static Func<FinanceFixture, Task> InDevelopment(Func<FinanceFixture, Task> test) => async _ =>
    {
        await using var dev = new FinanceFixture("Development");
        await dev.SeedAsync();
        await test(dev);
    };

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        // ---------- Notifications (read-only) ----------
        await check("Notifications require authentication", async f =>
            Assert((await f.SendAsync("GET", "/api/notifications")).Status == 401));
        await check("Notifications reject plain HTTP and are never cached", async f =>
        {
            Assert((await f.SendAsync("GET", "/api/notifications", bearer: f.Issue(1), scheme: "http")).Status == 400);
            var r = await List(f, 1);
            Assert(r.Status == 200 && r.CacheControl == "no-store", r.Body);
        });
        await check("Notifications are scoped to their owner, newest first", async f =>
        {
            await Seed(f, Note(1, "older", minutes: 0), Note(1, "newer", minutes: 5), Note(2, "someone else"));
            var mine = Json(await List(f, 1));
            Assert(mine.GetProperty("totalCount").GetInt64() == 2);
            var items = mine.GetProperty("items");
            Assert(items[0].GetProperty("message").GetString() == "newer" && items[1].GetProperty("message").GetString() == "older");
            var other = Json(await List(f, 2));
            Assert(other.GetProperty("totalCount").GetInt64() == 1 && other.GetProperty("items")[0].GetProperty("message").GetString() == "someone else");
            var admin = Json(await List(f, 3));
            Assert(admin.GetProperty("totalCount").GetInt64() == 0, "administrators see only their own notifications");
        });
        await check("Notifications never expose owner IDs or deduplication keys", async f =>
        {
            await Seed(f, Note(1, "hello"));
            var r = await List(f, 1);
            Assert(r.Status == 200 && !r.Body.Contains("userId", StringComparison.OrdinalIgnoreCase)
                && !r.Body.Contains("deduplication", StringComparison.OrdinalIgnoreCase) && !r.Body.Contains("emailSent", StringComparison.OrdinalIgnoreCase), r.Body);
        });
        await check("Notifications filter unread and paginate", async f =>
        {
            await Seed(f, Note(1, "a", read: true, minutes: 1), Note(1, "b", minutes: 2), Note(1, "c", minutes: 3));
            var unread = Json(await List(f, 1, "?unreadOnly=true"));
            Assert(unread.GetProperty("totalCount").GetInt64() == 2);
            var page2 = Json(await List(f, 1, "?page=2&pageSize=2"));
            Assert(page2.GetProperty("totalCount").GetInt64() == 3 && page2.GetProperty("items").GetArrayLength() == 1
                && page2.GetProperty("items")[0].GetProperty("message").GetString() == "a");
            Assert((await List(f, 1, "?pageSize=101")).Status == 400 && (await List(f, 1, "?page=0")).Status == 400);
        });
        await check("Notifications API is read-only", async f =>
        {
            await Seed(f, Note(1, "x"));
            foreach (var method in new[] { "POST", "PUT", "DELETE", "PATCH" })
                Assert((await f.SendAsync(method, "/api/notifications", new { }, f.Issue(1))).Status is 404 or 405, method);
            await f.DbAsync(async db => Assert(await db.Notifications.CountAsync() == 1 && !(await db.Notifications.SingleAsync()).IsRead));
        });

        // ---------- Development-only job trigger ----------
        await check("Dev job trigger does not exist outside Development (404 even for administrators)", async f =>
        {
            Assert((await Run(f, "budget-alerts")).Status == 404);
            Assert((await Run(f, "monthly-summaries", user: null)).Status == 404, "anonymous callers learn nothing either");
            await f.DbAsync(async db => Assert(!await db.Notifications.AnyAsync() && !await db.MonthlySummaries.AnyAsync()));
        });
        await check("Dev job trigger requires authentication and the Administrator role", InDevelopment(async f =>
        {
            Assert((await Run(f, "budget-alerts", user: null)).Status == 401);
            Assert((await Run(f, "budget-alerts", user: 1)).Status == 403);
            Assert((await Run(f, "budget-alerts", user: 3, scheme: "http")).Status == 400);
            Assert((await Run(f, "bill-reminders")).Status == 404, "bill reminders are not offered");
            Assert((await Run(f, "anything")).Status == 404);
        }));
        await check("Dev trigger runs budget alerts once per transition and the owner can read them", InDevelopment(async f =>
        {
            Assert((await f.SendAsync("POST", "/api/budgets", new { categoryId = 2, periodMonth = "2026-11-01", limitAmount = 100m, alertThresholdPct = 80 }, f.Issue(1))).Status == 201);
            var tx = await f.SendAsync("POST", "/api/transactions", new CreateTransactionRequest { ClientId = Guid.NewGuid(), CategoryId = 2, Type = "Expense",
                Amount = 90m, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 11, 2), Description = "t" }, f.Issue(1));
            Assert(tx.Status == 201, tx.Body);
            var first = await Run(f, "budget-alerts");
            Assert(first.Status == 200 && Json(first).GetProperty("created").GetInt32() == 1, first.Body);
            var second = await Run(f, "budget-alerts");
            Assert(Json(second).GetProperty("created").GetInt32() == 0, "no duplicate alert for the same status");
            var mine = Json(await List(f, 1));
            Assert(mine.GetProperty("totalCount").GetInt64() == 1 && mine.GetProperty("items")[0].GetProperty("type").GetString() == "BudgetAlert");
            Assert(Json(await List(f, 2)).GetProperty("totalCount").GetInt64() == 0);
            Assert(Json(await List(f, 3)).GetProperty("totalCount").GetInt64() == 0, "the administrator who ran the job sees no member data");
        }));
        await check("Dev trigger generates last month's summary once", InDevelopment(async f =>
        {
            var tx = await f.SendAsync("POST", "/api/transactions", new CreateTransactionRequest { ClientId = Guid.NewGuid(), CategoryId = 2, Type = "Expense",
                Amount = 25m, CurrencyCode = "PHP", TransactionDate = new DateOnly(2026, 10, 10), Description = "t" }, f.Issue(1));
            Assert(tx.Status == 201, tx.Body);
            Assert(Json(await Run(f, "monthly-summaries")).GetProperty("created").GetInt32() == 1);
            Assert(Json(await Run(f, "monthly-summaries")).GetProperty("created").GetInt32() == 0, "existing summaries are left unchanged");
            await f.DbAsync(async db =>
            {
                var s = await db.MonthlySummaries.SingleAsync();
                Assert(s.UserId == 1 && s.SummaryMonth == new DateOnly(2026, 10, 1) && s.TotalExpense == 25m);
            });
            var mine = Json(await List(f, 1));
            Assert(mine.GetProperty("items")[0].GetProperty("type").GetString() == "MonthlySummary");
        }));
        await check("Dev trigger response contains only the job name and a count", InDevelopment(async f =>
        {
            var r = await Run(f, "monthly-summaries");
            Assert(r.Status == 200 && r.CacheControl == "no-store", r.Body);
            var props = Json(r).EnumerateObject().Select(p => p.Name).OrderBy(n => n).ToArray();
            Assert(props.SequenceEqual(new[] { "created", "job" }), string.Join(",", props));
        }));
    }
}
