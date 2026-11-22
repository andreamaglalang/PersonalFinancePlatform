using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Infrastructure.Finance.Contracts;
namespace PersonalFinance.FinanceTests;
internal static class ReportsOnlyTests
{
    private static void Assert(bool condition,string detail="Assertion failed") { if(!condition) throw new Exception(detail); }
    private static JsonElement Json(ApiReply r) => JsonDocument.Parse(r.Body).RootElement;
    private static async Task<ApiReply> Expense(FinanceFixture f,decimal amount,string date,int category=2,string type="Expense",string currency="PHP") =>
        await f.SendAsync("POST","/api/transactions",new CreateTransactionRequest { ClientId=Guid.NewGuid(),CategoryId=category,Type=type,
        Amount=amount,CurrencyCode=currency,TransactionDate=DateOnly.Parse(date),Description="Synthetic report test" },f.Issue(1));
    public static async Task RunAsync(Func<string,Func<FinanceFixture,Task>,Task> check)
    {
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

    }
}
