using System.Text.Json;
using Microsoft.EntityFrameworkCore;

namespace PersonalFinance.FinanceTests;

internal static class TransactionLifecycleTests
{
    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        object Request(Guid client, decimal amount = 10) => new { clientId = client, type = "Expense", categoryId = 2,
            amount, currencyCode = "PHP", transactionDate = "2026-10-10", description = "Lifecycle" };
        static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
        static void Assert(bool ok, string why) { if (!ok) throw new InvalidOperationException(why); }
        await check("Phase9: missing and malformed transaction delete versions never delete", async f =>
        {
            var row = Json(await f.SendAsync("POST", "/api/transactions", Request(Guid.NewGuid()), f.Issue(1)));
            var path = "/api/transactions/" + row.GetProperty("transactionId").GetInt32();
            foreach (var query in new[] { "", "?version=abc", "?version=" + new string('a', 64) })
                Assert((await f.SendAsync("DELETE", path + query, bearer: f.Issue(1))).Status == 400, "missing/malformed precondition accepted");
            Assert((await f.SendAsync("GET", path, bearer: f.Issue(1))).Status == 200, "record lost");
        });
        await check("Phase9: stale delete preserves another client's edit and matching version deletes", async f =>
        {
            var row = Json(await f.SendAsync("POST", "/api/transactions", Request(Guid.NewGuid()), f.Issue(1)));
            var path = "/api/transactions/" + row.GetProperty("transactionId").GetInt32();
            var stale = row.GetProperty("version").GetString();
            var edited = await f.SendAsync("PUT", path, new { type = "Expense", categoryId = 2, amount = 20,
                currencyCode = "PHP", transactionDate = "2026-10-10", description = "Edited elsewhere", version = stale }, f.Issue(1));
            Assert(edited.Status == 200, edited.Body);
            Assert((await f.SendAsync("DELETE", path + "?version=" + stale, bearer: f.Issue(1))).Status == 409, "stale delete accepted");
            Assert(Json(await f.SendAsync("GET", path, bearer: f.Issue(1))).GetProperty("amount").GetDecimal() == 20, "edit lost");
            Assert((await f.SendAsync("DELETE", path + "?version=" + Json(edited).GetProperty("version").GetString(), bearer: f.Issue(1))).Status == 200, "current delete refused");
        });
        await check("Phase9: delayed creates after deletion never recreate, even with altered payload", async f =>
        {
            var client = Guid.NewGuid(); var request = Request(client);
            var row = Json(await f.SendAsync("POST", "/api/transactions", request, f.Issue(1)));
            var path = "/api/transactions/" + row.GetProperty("transactionId").GetInt32();
            Assert((await f.SendAsync("DELETE", path + "?version=" + row.GetProperty("version").GetString(), bearer: f.Issue(1))).Status == 200, "delete failed");
            foreach (var replay in new[] { request, Request(client, 99), request })
                Assert((await f.SendAsync("POST", "/api/transactions", replay, f.Issue(1))).Status == 409, "deleted identity recreated");
            await f.DbAsync(async db => Assert(!await db.Transactions.AnyAsync(), "deleted amount returned to totals"));
            var other = new { clientId = client, type = "Expense", categoryId = 3, amount = 10,
                currencyCode = "PHP", transactionDate = "2026-10-10", description = "Own namespace" };
            Assert((await f.SendAsync("POST", "/api/transactions", other, f.Issue(2))).Status == 201, "tombstone leaked across owners");
            Assert((await f.SendAsync("POST", "/api/transactions", Request(Guid.NewGuid()), f.Issue(1))).Status == 201, "new independent action refused");
        });
    }
}
