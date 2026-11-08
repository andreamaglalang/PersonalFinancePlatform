using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.FinanceTests;

/// <summary>Offline MVC/service checks. Simulated versions/races do not certify SQL transactions or locking.</summary>
internal static class SavingsGoalTests
{
    private sealed class SavingsCurrentUser : PersonalFinance.Infrastructure.Authentication.ICurrentUser
    {
        public int? UserId => 1;
        public bool IsAdministrator => false;
        public bool Owns(int resourceOwnerId) => resourceOwnerId == 1;
    }

    private const string Root = "/api/savings-goals";
    private const string Today = "2026-11-03";
    private static void Assert(bool value, string detail = "Savings assertion failed")
    { if (!value) throw new Exception(detail); }
    private static JsonElement Json(ApiReply reply)
    { using var doc = JsonDocument.Parse(reply.Body); return doc.RootElement.Clone(); }
    private static JsonElement Expect(ApiReply reply, int status = 200)
    { Assert(reply.Status == status, $"Expected {status}, got {reply.Status}: {reply.Body}"); return Json(reply); }
    private static void Throws<T>(Action action) where T : Exception
    { try { action(); } catch (T) { return; } throw new Exception("Expected " + typeof(T).Name); }
    private static Dictionary<string, string[]> Match(params string[] tags) => new() { ["If-Match"] = tags };
    private static string Tag(ApiReply reply)
    { Assert(reply.ETag is { Length: 14 }, reply.Body); return reply.ETag!; }
    private static string Path(int goal, int? contribution = null) => contribution.HasValue
        ? $"{Root}/{goal}/contributions/{contribution}" : $"{Root}/{goal}";
    private static object GoalBody(decimal target = 100m, string name = "Laptop", string? targetDate = null) => new { name, targetAmount = target, targetDate };
    private static object ContributionBody(Guid? client = null, decimal amount = 25m, string date = Today, string? note = "Saved") =>
        new { clientId = client ?? Guid.NewGuid(), amount, contributedOn = date, note };
    private static object EditBody(decimal amount = 25m, string date = Today, string? note = "Corrected") => new { amount, contributedOn = date, note };
    private static Task<ApiReply> Send(FinanceFixture f, string method, string path, object? body = null, int user = 1, string? tag = null) =>
        f.SendAsync(method, path, body, f.Issue(user), headers: tag is null ? null : Match(tag));
    private static async Task<int> Goal(FinanceFixture f, decimal target = 100m, int user = 1, string name = "Laptop") =>
        Expect(await Send(f, "POST", Root, GoalBody(target, name), user), 201).GetProperty("goalId").GetInt32();
    private static Task<ApiReply> Read(FinanceFixture f, int goal, int user = 1) => Send(f, "GET", Path(goal), user: user);
    private static async Task<ApiReply> Contribute(FinanceFixture f, int goal, decimal amount = 25m, Guid? client = null,
        string date = Today, string? note = "Saved", int user = 1)
    { var reply = await Send(f, "POST", Path(goal) + "/contributions", ContributionBody(client, amount, date, note), user); Expect(reply, 201); return reply; }
    private static int ContributionId(ApiReply reply) => Json(reply).GetProperty("receipt").GetProperty("contributionId").GetInt32();
    private static async Task<ApiReply> Edit(FinanceFixture f, int goal, int contribution, decimal amount = 25m,
        string date = Today, string? note = "Corrected") => await Send(f, "PUT", Path(goal, contribution),
            EditBody(amount, date, note), tag: Tag(await Send(f, "GET", Path(goal, contribution))));
    private static async Task<ApiReply> Remove(FinanceFixture f, int goal, int contribution) =>
        await Send(f, "DELETE", Path(goal, contribution), tag: Tag(await Send(f, "GET", Path(goal, contribution))));
    private static async Task<ApiReply> Cancel(FinanceFixture f, int goal) =>
        await Send(f, "POST", Path(goal) + "/cancel", tag: Tag(await Read(f, goal)));
    private static async Task Progress(FinanceFixture f, int goal, decimal saved, string status)
    { var j = Expect(await Read(f, goal)); Assert(j.GetProperty("savedAmount").GetDecimal() == saved && j.GetProperty("status").GetString() == status, j.ToString()); }
    private static async Task<string> Snapshot(FinanceFixture f)
    {
        var result = "";
        await f.DbAsync(async db => { result = JsonSerializer.Serialize(new
        {
            Goals = await db.SavingsGoals.AsNoTracking().OrderBy(g => g.GoalId).Select(g => new { g.GoalId, g.UserId, g.Name, g.TargetAmount, g.TargetDate, g.Status, g.RowVersion }).ToListAsync(),
            Contributions = await db.GoalContributions.AsNoTracking().OrderBy(c => c.ContributionId).Select(c => new { c.ContributionId, c.GoalId, c.Amount, c.ContributedOn, c.Note, c.RowVersion }).ToListAsync(),
            Requests = await db.GoalContributionRequests.AsNoTracking().OrderBy(r => r.RequestId).ToListAsync()
        }); });
        return result;
    }

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        if (!await BootstrapAsync(check))
        {
            Console.WriteLine("Savings scenario checks BLOCKED by failed simulator bootstrap; existing bootstrap failures determine runner failure.");
            return;
        }
        await PoliciesAsync(check);
        await GoalsAsync(check);
        await ContributionsAsync(check);
        await IdempotencyAsync(check);
        await AuthorizationAsync(check);
        await HttpAsync(check);
        await RegressionAsync(check);
        Console.WriteLine("Savings checks use simulated rowversions and serialized test writes. SQL rollback, constraints, native rowversion generation and locks NOT verified.");
    }

    private static async Task<bool> BootstrapAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        var healthy = true;
        async Task Boot(string name, Func<FinanceFixture, Task> action) => await check("Savings bootstrap (simulation): " + name, async f =>
        { try { await action(f); } catch { healthy = false; throw; } });
        await Boot("fixture uses InMemory with fake write scope and FX", f =>
        {
            return f.DbAsync(db => { Assert(db.Database.IsInMemory()); Assert(f.Services.GetRequiredService<IFinancialWriteScope>() is MemoryWriteScope);
                Assert(ReferenceEquals(f.Services.GetRequiredService<IFrankfurterClient>(), f.Fx)); return Task.CompletedTask; });
        });
        await Boot("Added goals and contributions receive distinct eight-byte versions", async f =>
        {
            await f.DbAsync(async db =>
            {
                var g = new SavingsGoal { UserId = 1, Name = "Bootstrap", TargetAmount = 100 };
                var c = new GoalContribution { Goal = g, Amount = 1, ContributedOn = new(2026, 11, 3) };
                db.AddRange(g, c); await db.SaveChangesAsync();
                Assert(g.RowVersion.Length == 8 && c.RowVersion.Length == 8 && !g.RowVersion.SequenceEqual(c.RowVersion));
            });
        });
        await Boot("simulated versions survive a new scope and ReloadAsync", async f =>
        {
            int id = 0; byte[] original = [];
            await f.DbAsync(async db => { var g = new SavingsGoal { UserId = 1, Name = "Reload", TargetAmount = 10 }; db.Add(g); await db.SaveChangesAsync(); id = g.GoalId; original = g.RowVersion.ToArray(); });
            await f.DbAsync(async db => { var g = await db.SavingsGoals.SingleAsync(x => x.GoalId == id); await db.Entry(g).ReloadAsync(); Assert(g.RowVersion.SequenceEqual(original)); });
        });
        await Boot("Modified entity changes version while original concurrency bytes are retained", async f =>
        {
            var id = await Goal(f);
            await f.DbAsync(async db =>
            {
                var g = await db.SavingsGoals.SingleAsync(x => x.GoalId == id); var original = g.RowVersion.ToArray(); g.Name = "Modified";
                await db.SaveChangesAsync(acceptAllChangesOnSuccess: false);
                Assert(!g.RowVersion.SequenceEqual(original)); Assert(db.Entry(g).Property(x => x.RowVersion).OriginalValue.SequenceEqual(original));
                db.ChangeTracker.AcceptAllChanges();
            });
        });
        await Boot("forced same-value parent UPDATE advances simulated version", async f =>
        {
            var id = await Goal(f);
            await f.DbAsync(async db => { var g = await db.SavingsGoals.SingleAsync(x => x.GoalId == id); var old = g.RowVersion.ToArray();
                db.Entry(g).Property(x => x.Status).IsModified = true; await db.SaveChangesAsync(); Assert(!g.RowVersion.SequenceEqual(old)); });
        });
        await Boot("unchanged SaveChanges and reads preserve tokens", async f =>
        {
            var id = await Goal(f); var before = await Snapshot(f);
            await Read(f, id); await f.DbAsync(db => db.SaveChangesAsync()); Assert(await Snapshot(f) == before);
        });
        await Boot("child insertion alone never advances the parent automatically", async f =>
        {
            var id = await Goal(f); var before = Tag(await Read(f, id));
            await f.DbAsync(async db => { db.GoalContributions.Add(new() { GoalId = id, Amount = 1, ContributedOn = new(2026, 11, 3) }); await db.SaveChangesAsync(); });
            Assert(Tag(await Read(f, id)) == before);
        });
        await Boot("EF InMemory detects stale original tokens (not SQL concurrency proof)", async f =>
        {
            var id = await Goal(f);
            using var a = f.Services.CreateScope(); using var b = f.Services.CreateScope();
            var dbA = a.ServiceProvider.GetRequiredService<ApplicationDbContext>(); var dbB = b.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            var first = await dbA.SavingsGoals.SingleAsync(g => g.GoalId == id); var stale = await dbB.SavingsGoals.SingleAsync(g => g.GoalId == id);
            first.Name = "Winner"; await dbA.SaveChangesAsync(); stale.Name = "Loser";
            var rejected = false; try { await dbB.SaveChangesAsync(); } catch (DbUpdateConcurrencyException) { rejected = true; }
            Assert(rejected);
        });
        await Boot("fixture clock is fixed and JWT uses the same clock", async f =>
        { Assert(f.Clock.Now == new DateTimeOffset(2026, 11, 3, 12, 0, 0, TimeSpan.Zero)); Expect(await Send(f, "GET", Root)); });
        await Boot("headers round-trip through the actual MVC pipeline", async f =>
        {
            var r = await Send(f, "POST", Root, GoalBody()); Expect(r, 201); Assert(r.Location is not null && r.ContentType?.Contains("application/json") == true);
            Assert(r.CacheControl == "no-store" && r.Pragma == "no-cache");
            var update = await Send(f, "PUT", Path(Json(r).GetProperty("goalId").GetInt32()), GoalBody(), tag: Tag(r)); Expect(update);
        });
        return healthy;
    }

    private static async Task PoliciesAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings policy: empty total and Active zero progress", _ => { var p = SavingsGoalProgressCalculator.Calculate(Array.Empty<decimal>(), 100, SavingsGoalStatus.Active);
            Assert(p.TotalSavedAmount == 0 && p.RemainingAmount == 100 && p.SurplusAmount == 0 && p.ProgressPercentage == 0 && p.Status == SavingsGoalStatus.Active); return Task.CompletedTask; });
        await check("Savings policy: partial exact and overfunded boundaries", _ => { foreach (var saved in new[] { 99.99m, 100m, 125m }) {
            var p = SavingsGoalProgressCalculator.Calculate(saved, 100, SavingsGoalStatus.Active); Assert(p.Status == (saved < 100 ? SavingsGoalStatus.Active : SavingsGoalStatus.Completed));
            Assert(p.RemainingAmount == 100 - saved && p.SurplusAmount == Math.Max(0, saved - 100)); } return Task.CompletedTask; });
        await check("Savings policy: Completed reopens and Cancelled remains terminal", _ => { Assert(SavingsGoalProgressCalculator.Calculate(1, 100, SavingsGoalStatus.Completed).Status == SavingsGoalStatus.Active);
            foreach (var saved in new[] { 0m, 200m }) Assert(SavingsGoalProgressCalculator.Calculate(saved, 100, SavingsGoalStatus.Cancelled).Status == SavingsGoalStatus.Cancelled); return Task.CompletedTask; });
        await check("Savings policy: percentage rounds only display and permits over 100", _ => { Assert(SavingsGoalProgressCalculator.Calculate(1, 3, SavingsGoalStatus.Active).ProgressPercentage == 33.33m);
            Assert(SavingsGoalProgressCalculator.Calculate(2, 3, SavingsGoalStatus.Active).ProgressPercentage == 66.67m); Assert(SavingsGoalProgressCalculator.Calculate(150, 100, SavingsGoalStatus.Active).ProgressPercentage == 150); return Task.CompletedTask; });
        await check("Savings policy: ceilings agree without a Domain dependency", _ => { Assert(SavingsGoalProgressCalculator.MaximumMoney == FinanceValidation.MaximumMoney);
            Assert(SavingsGoalProgressCalculator.CalculateTotalSaved([FinanceValidation.MaximumMoney]) == FinanceValidation.MaximumMoney); return Task.CompletedTask; });
        await check("Savings policy: rejects overflow before aggregate addition", _ => { Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.CalculateTotalSaved([FinanceValidation.MaximumMoney, .01m]));
            Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.CalculateTotalSaved([decimal.MaxValue])); return Task.CompletedTask; });
        await check("Savings policy: invalid contributions targets totals and enums", _ => { foreach (var amount in new[] { 0m, -1m, 1.001m }) Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.CalculateTotalSaved([amount]));
            Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.Calculate(0m, 0m, SavingsGoalStatus.Active)); Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.Calculate(-1m, 1m, SavingsGoalStatus.Active));
            Throws<ArgumentOutOfRangeException>(() => SavingsGoalProgressCalculator.Calculate(0m, 1m, (SavingsGoalStatus)99)); return Task.CompletedTask; });
        await check("Savings policy: smallest target maximum total and null sequence", _ => { var p = SavingsGoalProgressCalculator.Calculate(FinanceValidation.MaximumMoney, .01m, SavingsGoalStatus.Active);
            Assert(p.ProgressPercentage > 100 && p.Status == SavingsGoalStatus.Completed); Throws<ArgumentNullException>(() => SavingsGoalProgressCalculator.CalculateTotalSaved(null!)); return Task.CompletedTask; });
    }

    private static async Task GoalsAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings goals: create retrieve safe base-currency details", async f => { var id = await Goal(f); var j = Expect(await Read(f, id));
            Assert(j.GetProperty("baseCurrency").GetString() == "PHP" && j.GetProperty("name").GetString() == "Laptop" && !j.TryGetProperty("userId", out _)); await Progress(f, id, 0, "Active"); });
        await check("Savings goals: list status pagination and stable ordering", async f => { var a = await Goal(f); var b = await Goal(f); await Contribute(f, a, 100);
            var page = Expect(await Send(f, "GET", Root + "?pageSize=1")); Assert(page.GetProperty("totalCount").GetInt64() == 2 && page.GetProperty("items")[0].GetProperty("goalId").GetInt32() == b);
            var completed = Expect(await Send(f, "GET", Root + "?status=Completed")); Assert(completed.GetProperty("totalCount").GetInt64() == 1); });
        await check("Savings goals: update name target and historical target date", async f => { var id = await Goal(f); var r = await Send(f, "PUT", Path(id), GoalBody(200, "  Travel  ", "2020-01-01"), tag: Tag(await Read(f, id)));
            var j = Expect(r); Assert(j.GetProperty("name").GetString() == "Travel" && j.GetProperty("targetDate").GetString() == "2020-01-01"); });
        await check("Savings goals: contributions complete exactly at target", async f => { var id = await Goal(f); await Contribute(f, id, 99.99m); await Progress(f, id, 99.99m, "Active"); await Contribute(f, id, .01m); await Progress(f, id, 100, "Completed"); });
        await check("Savings goals: Completed goals accept overfunding", async f => { var id = await Goal(f); await Contribute(f, id, 100); await Contribute(f, id, 25);
            var j = Expect(await Read(f, id)); Assert(j.GetProperty("remainingAmount").GetDecimal() == -25 && j.GetProperty("surplusAmount").GetDecimal() == 25 && j.GetProperty("progressPercentage").GetDecimal() == 125); });
        await check("Savings goals: target increase reopens and decrease completes", async f => { var id = await Goal(f); await Contribute(f, id, 100);
            Expect(await Send(f, "PUT", Path(id), GoalBody(200), tag: Tag(await Read(f, id)))); await Progress(f, id, 100, "Active");
            Expect(await Send(f, "PUT", Path(id), GoalBody(50), tag: Tag(await Read(f, id)))); await Progress(f, id, 100, "Completed"); });
        await check("Savings goals: cancellation terminal repeat is read-only", async f => { var id = await Goal(f); Expect(await Cancel(f, id)); var before = await Snapshot(f);
            var r = await Cancel(f, id); Assert(Expect(r).GetProperty("status").GetString() == "Cancelled" && await Snapshot(f) == before);
            Expect(await Send(f, "PUT", Path(id), GoalBody(), tag: Tag(r)), 409); Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody()), 409); });
        await check("Savings goals: Completed goal cancellation", async f => { var id = await Goal(f); await Contribute(f, id, 100); Expect(await Cancel(f, id)); await Progress(f, id, 100, "Cancelled"); });
        await check("Savings goals: empty deletion returns 204 and removes only goal", async f => { var id = await Goal(f); var r = await Send(f, "DELETE", Path(id), tag: Tag(await Read(f, id)));
            Assert(r.Status == 204 && r.Body.Length == 0 && r.ETag is null); Expect(await Read(f, id), 404); });
        await check("Savings goals: surviving contributions block deletion", async f => { var id = await Goal(f); await Contribute(f, id); var before = await Snapshot(f);
            Expect(await Send(f, "DELETE", Path(id), tag: Tag(await Read(f, id))), 409); Assert(await Snapshot(f) == before); });
        await check("Savings goals: deleted contribution history still blocks goal deletion", async f => { var id = await Goal(f); var c = await Contribute(f, id); Expect(await Remove(f, id, ContributionId(c)));
            Expect(await Send(f, "DELETE", Path(id), tag: Tag(await Read(f, id))), 409); await f.DbAsync(async db => Assert(await db.GoalContributionRequests.CountAsync() == 1 && !await db.GoalContributions.AnyAsync())); });
        await check("Savings goals: typed and canonical notification history block deletion", async f => { var id = await Goal(f); await f.DbAsync(async db => { db.Notifications.Add(new() { UserId = 1, Type = "Goal", ReferenceType = "sAvInGsGoAl", ReferenceId = id, Message = "History", DeduplicationKey = "unrelated" }); await db.SaveChangesAsync(); });
            Expect(await Send(f, "DELETE", Path(id), tag: Tag(await Read(f, id))), 409); });
        await check("Savings goals: logical goal-key notification protects empty goal", async f => { var id = await Goal(f); await f.DbAsync(async db => { db.Notifications.Add(new() { UserId = 1, Type = "Goal", Message = "History", DeduplicationKey = $"goal:{id}:completed" }); await db.SaveChangesAsync(); });
            Expect(await Send(f, "DELETE", Path(id), tag: Tag(await Read(f, id))), 409); });
        await check("Savings goals: invalid names required fields and target bounds", async f => { foreach (var body in new object[] { new { }, GoalBody(name: "  "), GoalBody(name: new string('x', 101)), GoalBody(0), GoalBody(-1), GoalBody(1.001m), GoalBody(10000000000000000m) }) Expect(await Send(f, "POST", Root, body), 400); });
    }

    private static async Task ContributionsAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings contributions: create receipt and retrieve current details", async f => { var id = await Goal(f); var r = await Contribute(f, id, note: "  Deposit  ");
            var j = Expect(r, 201); Assert(!j.GetProperty("replayed").GetBoolean() && j.GetProperty("receipt").GetProperty("canonicalVersion").GetInt16() == 1);
            var current = Expect(await Send(f, "GET", Path(id, ContributionId(r)))); Assert(current.GetProperty("note").GetString() == "Deposit" && current.GetProperty("baseCurrency").GetString() == "PHP"); });
        await check("Savings contributions: list date filters pagination and ordering", async f => { var id = await Goal(f); await Contribute(f, id, date: "2026-11-01"); var latest = await Contribute(f, id, date: "2026-11-02");
            var j = Expect(await Send(f, "GET", Path(id) + "/contributions?pageSize=1")); Assert(j.GetProperty("totalCount").GetInt64() == 2 && j.GetProperty("items")[0].GetProperty("contributionId").GetInt32() == ContributionId(latest));
            j = Expect(await Send(f, "GET", Path(id) + "/contributions?fromDate=2026-11-02&toDate=2026-11-02")); Assert(j.GetProperty("totalCount").GetInt64() == 1); });
        await check("Savings contributions: correction updates amount date note and progress", async f => { var id = await Goal(f); var r = await Contribute(f, id); var j = Expect(await Edit(f, id, ContributionId(r), 125, "2026-11-01", "  Corrected  "));
            Assert(j.GetProperty("contribution").GetProperty("note").GetString() == "Corrected" && j.GetProperty("contribution").GetProperty("contributedOn").GetString() == "2026-11-01"); await Progress(f, id, 125, "Completed"); });
        await check("Savings contributions: lower correction reopens Completed goal", async f => { var id = await Goal(f); var r = await Contribute(f, id, 100); Expect(await Edit(f, id, ContributionId(r), 50)); await Progress(f, id, 50, "Active"); });
        await check("Savings contributions: delete retains receipt and reopens", async f => { var id = await Goal(f); var r = await Contribute(f, id, 100); var j = Expect(await Remove(f, id, ContributionId(r)));
            Assert(j.GetProperty("deleted").GetBoolean()); Expect(await Send(f, "GET", Path(id, ContributionId(r))), 404); await Progress(f, id, 0, "Active");
            await f.DbAsync(async db => { var receipt = await db.GoalContributionRequests.SingleAsync(); Assert(receipt.DeletedAtUtc == f.Clock.Now.UtcDateTime && receipt.OriginalAmount == 100); }); });
        await check("Savings contributions: today and historical dates accepted", async f => { var id = await Goal(f); await Contribute(f, id, date: Today); await Contribute(f, id, date: "2000-01-01"); await Progress(f, id, 50, "Active"); });
        await check("Savings contributions: future creation rejected without mutation", async f => { var id = await Goal(f); var before = await Snapshot(f);
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(date: "2026-11-04")), 400); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: future correction rejected without mutation", async f => { var id = await Goal(f); var r = await Contribute(f, id); var before = await Snapshot(f);
            Expect(await Edit(f, id, ContributionId(r), date: "2026-11-04"), 400); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: UTC calendar date rather than clock offset local date", async f => { f.Clock.Now = new DateTimeOffset(2026, 11, 4, 0, 30, 0, TimeSpan.FromHours(8)); var id = await Goal(f);
            await Contribute(f, id, date: Today); Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(date: "2026-11-04")), 400); });
        await check("Savings contributions: UTC midnight advances accepted day deterministically", async f => { f.Clock.Now = new DateTimeOffset(2026, 11, 3, 23, 59, 59, TimeSpan.Zero); var id = await Goal(f);
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(date: "2026-11-04")), 400); f.Clock.Now = f.Clock.Now.AddSeconds(1); await Contribute(f, id, date: "2026-11-04"); });
        await check("Savings contributions: invalid money required fields date and note", async f => { var id = await Goal(f); var before = await Snapshot(f);
            foreach (var body in new object[] { new { }, ContributionBody(amount: 0), ContributionBody(amount: -1), ContributionBody(amount: 1.001m), ContributionBody(amount: 10000000000000000m), ContributionBody(date: "2026-02-30"), ContributionBody(note: new string('x', 201)) })
                Expect(await Send(f, "POST", Path(id) + "/contributions", body), 400); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: maximum aggregate accepted and next cent rejected", async f => { var id = await Goal(f); await Contribute(f, id, FinanceValidation.MaximumMoney); var before = await Snapshot(f);
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(amount: .01m)), 409); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: correction cannot exceed aggregate ceiling", async f => { var id = await Goal(f); await Contribute(f, id, FinanceValidation.MaximumMoney - 1); var r = await Contribute(f, id, 1); var before = await Snapshot(f);
            Expect(await Edit(f, id, ContributionId(r), 2), 409); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: all mutations advance parent even when lifecycle unchanged (simulation)", async f => { var id = await Goal(f); var v0 = Tag(await Read(f, id)); var r = await Contribute(f, id); var v1 = Tag(await Read(f, id));
            var edited = await Edit(f, id, ContributionId(r), note: "Note only"); Expect(edited); var v2 = Tag(await Read(f, id)); Assert(Tag(edited) != Tag(r));
            Expect(await Remove(f, id, ContributionId(r))); var v3 = Tag(await Read(f, id)); Assert(new[] { v0, v1, v2, v3 }.Distinct().Count() == 4); });
        await check("Savings contributions: Cancelled rejects correction and deletion", async f => { var id = await Goal(f); var r = await Contribute(f, id); Expect(await Cancel(f, id)); var before = await Snapshot(f);
            Expect(await Edit(f, id, ContributionId(r)), 409); Expect(await Remove(f, id, ContributionId(r)), 409); Assert(await Snapshot(f) == before); });
        await check("Savings contributions: unenrolled legacy-shaped test row cannot be edited or deleted", async f => { var id = await Goal(f); int c = 0;
            await f.DbAsync(async db => { var row = new GoalContribution { GoalId = id, Amount = 10, ContributedOn = new(2026, 11, 3) }; db.Add(row); await db.SaveChangesAsync(); c = row.ContributionId; });
            var before = await Snapshot(f); Expect(await Edit(f, id, c), 409); Expect(await Remove(f, id, c), 409); Assert(await Snapshot(f) == before); });
    }

    private static async Task SeedRaceWinner(FinanceFixture f, int goalId, Guid client, decimal amount = 25m)
    {
        // Test fixture data only. This does not emulate rollback or enroll real legacy data.
        await f.DbAsync(async db =>
        {
            var goal = await db.SavingsGoals.SingleAsync(g => g.GoalId == goalId);
            var c = new GoalContribution { GoalId = goalId, Amount = amount, ContributedOn = new(2026, 11, 3), Note = "Saved" };
            db.Add(c); db.Entry(goal).Property(g => g.Status).IsModified = true; await db.SaveChangesAsync();
            db.GoalContributionRequests.Add(new() { UserId = 1, ClientId = client, GoalId = goalId, ContributionId = c.ContributionId,
                OriginalAmount = amount, OriginalContributedOn = c.ContributedOn, OriginalNote = c.Note, RecordedAtUtc = f.Clock.Now.UtcDateTime });
            await db.SaveChangesAsync();
        });
    }

    private static async Task IdempotencyAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings idempotency: first acceptance and identical replay are single-counted", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var r = await Contribute(f, id, client: key); var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts;
            var replay = await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)); var j = Expect(replay); Assert(j.GetProperty("replayed").GetBoolean() && ContributionId(replay) == ContributionId(r));
            Assert(await Snapshot(f) == before && f.RowVersions.SaveAttempts == saves && Tag(replay) == Tag(r)); await Progress(f, id, 25, "Active"); });
        foreach (var change in new[] { "amount", "date", "note", "goal" })
            await check("Savings idempotency: conflicting " + change + " returns 409", async f => { var id = await Goal(f); var other = await Goal(f); var key = Guid.NewGuid(); await Contribute(f, id, client: key); var before = await Snapshot(f);
                var body = ContributionBody(key, amount: change == "amount" ? 26 : 25, date: change == "date" ? "2026-11-02" : Today, note: change == "note" ? "Other" : "Saved");
                Expect(await Send(f, "POST", Path(change == "goal" ? other : id) + "/contributions", body), 409); Assert(await Snapshot(f) == before); });
        await check("Savings idempotency: replay after correction returns current values without restoring", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var r = await Contribute(f, id, client: key); Expect(await Edit(f, id, ContributionId(r), 40, "2026-11-01", "New")); var before = await Snapshot(f);
            var j = Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key))); Assert(j.GetProperty("receipt").GetProperty("originalAmount").GetDecimal() == 25 && j.GetProperty("currentContribution").GetProperty("amount").GetDecimal() == 40); Assert(await Snapshot(f) == before); });
        await check("Savings idempotency: deleted receipt replay never recreates allocation or ETag", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var r = await Contribute(f, id, client: key); Expect(await Remove(f, id, ContributionId(r))); var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts;
            var replay = await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)); var j = Expect(replay); Assert(j.GetProperty("deleted").GetBoolean() && j.GetProperty("currentContribution").ValueKind == JsonValueKind.Null && replay.ETag is null);
            Assert(await Snapshot(f) == before && f.RowVersions.SaveAttempts == saves); });
        await check("Savings idempotency: deleted receipt still rejects changed payload", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var r = await Contribute(f, id, client: key); Expect(await Remove(f, id, ContributionId(r)));
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, amount: 26)), 409); });
        await check("Savings idempotency: replay remains read-only after cancellation", async f => { var id = await Goal(f); var key = Guid.NewGuid(); await Contribute(f, id, client: key); Expect(await Cancel(f, id)); var before = await Snapshot(f);
            var j = Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key))); Assert(j.GetProperty("goal").GetProperty("status").GetString() == "Cancelled" && await Snapshot(f) == before); });
        await check("Savings idempotency: user-scoped ClientId permits independent acceptance", async f => { var a = await Goal(f); var b = await Goal(f, user: 2); var key = Guid.NewGuid(); await Contribute(f, a, client: key); await Contribute(f, b, client: key, user: 2);
            await f.DbAsync(async db => Assert(await db.GoalContributionRequests.CountAsync() == 2)); });
        await check("Savings idempotency: frozen note boundary set and interior whitespace", _ => { foreach (var ch in new[] { '\t', '\n', '\u00A0', '\u1680', '\u2007', '\u202F', '\u3000' }) Assert(GoalContributionIdempotency.CanonicalizeNote(ch + "A  B" + ch) == "A  B");
            Assert(GoalContributionIdempotency.CanonicalizeNote("\u200BA\u200B") == "\u200BA\u200B"); return Task.CompletedTask; });
        await check("Savings idempotency: null empty whitespace notes share canonical identity", async f => { var id = await Goal(f); var key = Guid.NewGuid(); await Contribute(f, id, client: key, note: null);
            foreach (var note in new[] { "", "  ", "\t\u00A0\u3000" }) Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, note: note))); });
        await check("Savings idempotency: boundary trim replays but case and interior changes conflict", async f => { var id = await Goal(f); var key = Guid.NewGuid(); await Contribute(f, id, client: key, note: " A  B ");
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, note: "\tA  B\u3000"))); foreach (var note in new[] { "a  B", "A B" }) Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, note: note)), 409); });
        await check("Savings idempotency: equal decimal payload formats compare numerically", async f => { var id = await Goal(f); var key = Guid.NewGuid(); await Contribute(f, id, 25m, key); Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, 25.00m))); });
        await check("Savings idempotency: missing and empty ClientId rejected", async f => { var id = await Goal(f); foreach (var body in new object[] { new { amount = 1, contributedOn = Today }, ContributionBody(Guid.Empty) }) Expect(await Send(f, "POST", Path(id) + "/contributions", body), 400); });
        await check("Savings idempotency: unsupported comparison version rejected", _ => { var receipt = new GoalContributionRequest { CanonicalVersion = 2 }; Throws<FinanceException>(() => GoalContributionIdempotency.Matches(receipt, 1, 1, new(2026, 11, 3), null)); return Task.CompletedTask; });
        await check("Savings race SIMULATION: identical concurrent requests serialize to one receipt", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var results = await Task.WhenAll(Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)), Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)));
            Assert(results.Count(r => r.Status == 201) == 1 && results.Count(r => r.Status == 200) == 1); await f.DbAsync(async db => Assert(await db.GoalContributions.CountAsync() == 1 && await db.GoalContributionRequests.CountAsync() == 1)); });
        await check("Savings race SIMULATION: conflicting concurrent requests serialize to one conflict", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var results = await Task.WhenAll(Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, 10)), Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, 20)));
            Assert(results.Count(r => r.Status == 201) == 1 && results.Count(r => r.Status == 409) == 1); });
        foreach (var number in new[] { 2601, 2627 })
        {
            await check($"Savings race SIMULATION: synthetic {number} recovers matching winner", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
                scope.BeforeWrite = async () => { await SeedRaceWinner(f, id, key); throw new DbUpdateException("PRIVATE_SQL_DETAIL", SyntheticSqlError.Create(number)); };
                var r = await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)); Assert(Expect(r).GetProperty("replayed").GetBoolean()); await Progress(f, id, 25, "Active"); });
            await check($"Savings race SIMULATION: synthetic {number} compares winner payload", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
                scope.BeforeWrite = async () => { await SeedRaceWinner(f, id, key); throw new DbUpdateException("PRIVATE_SQL_DETAIL", SyntheticSqlError.Create(number)); };
                var r = await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key, 26)); Expect(r, 409); Assert(!r.Body.Contains("PRIVATE_SQL_DETAIL")); });
            await check($"Savings race SIMULATION: synthetic {number} without receipt is not success", async f => { var id = await Goal(f); var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
                scope.BeforeWrite = () => throw new DbUpdateException("PRIVATE_SQL_DETAIL", SyntheticSqlError.Create(number)); var r = await Send(f, "POST", Path(id) + "/contributions", ContributionBody()); Expect(r, 409); Assert(!r.Body.Contains("PRIVATE_SQL_DETAIL")); });
        }
        await check("Savings idempotency: inconsistent retained receipt fails without resurrection", async f => { var id = await Goal(f); var key = Guid.NewGuid(); var r = await Contribute(f, id, client: key);
            await f.DbAsync(async db => { db.GoalContributions.Remove(await db.GoalContributions.SingleAsync()); await db.SaveChangesAsync(); }); var before = await Snapshot(f);
            Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(key)), 409); Assert(await Snapshot(f) == before); });
    }

    private static async Task AuthorizationAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings authorization: all eleven endpoint operations reject anonymous requests", async f =>
        {
            foreach (var item in new (string Method, string Suffix, object? Body)[]
            { ("POST", "", GoalBody()), ("GET", "", null), ("GET", "/1", null), ("PUT", "/1", GoalBody()), ("POST", "/1/cancel", null), ("DELETE", "/1", null),
              ("POST", "/1/contributions", ContributionBody()), ("GET", "/1/contributions", null), ("GET", "/1/contributions/1", null), ("PUT", "/1/contributions/1", EditBody()), ("DELETE", "/1/contributions/1", null) })
                Assert((await f.SendAsync(item.Method, Root + item.Suffix, item.Body)).Status == 401);
        });
        await check("Savings authorization: malformed JWT rejected", async f => Assert((await f.SendAsync("GET", Root, bearer: "not-a-jwt")).Status == 401));
        await check("Savings authorization: expired JWT rejected using deterministic clock", async f => Assert((await f.SendAsync("GET", Root, bearer: f.Issue(1, issuedAt: f.Clock.Now.AddHours(-1)))).Status == 401));
        await check("Savings authorization: wrong issuer rejected", async f => Assert((await f.SendAsync("GET", Root, bearer: f.Issue(1, issuer: "foreign"))).Status == 401));
        await check("Savings authorization: wrong audience rejected", async f => Assert((await f.SendAsync("GET", Root, bearer: f.Issue(1, audience: "foreign"))).Status == 401));
        await check("Savings authorization: wrong signing key rejected", async f => Assert((await f.SendAsync("GET", Root, bearer: f.Issue(1, signingKey: Convert.ToBase64String(new byte[64])))).Status == 401));
        await check("Savings authorization: locked-out account rejected", async f => { var token = f.Issue(1); f.Users.Users[1].LockoutEnabled = true; f.Users.Users[1].LockoutEnd = DateTimeOffset.MaxValue; Assert((await f.SendAsync("GET", Root, bearer: token)).Status == 401); });
        await check("Savings authorization: stale role claims rejected", async f => { var token = f.Issue(1); f.Users.Membership[1].Add("ADMINISTRATOR"); Assert((await f.SendAsync("GET", Root, bearer: token)).Status == 401); });
        foreach (var user in new[] { 2, 3 })
        {
            await check($"Savings ownership: user {user} has no goal bypass (3 is Administrator)", async f => { var id = await Goal(f); var tag = Tag(await Read(f, id)); var before = await Snapshot(f);
                foreach (var target in new[] { id, 99999 }) { Expect(await Send(f, "GET", Path(target), user: user), 404); Expect(await Send(f, "PUT", Path(target), GoalBody(), user, tag), 404);
                    Expect(await Send(f, "POST", Path(target) + "/cancel", user: user, tag: tag), 404); Expect(await Send(f, "DELETE", Path(target), user: user, tag: tag), 404); }
                Assert(await Snapshot(f) == before); });
            await check($"Savings ownership: user {user} cannot access another user's contributions", async f => { var id = await Goal(f); var r = await Contribute(f, id); var c = ContributionId(r); var before = await Snapshot(f);
                Expect(await Send(f, "GET", Path(id) + "/contributions", user: user), 404); Expect(await Send(f, "POST", Path(id) + "/contributions", ContributionBody(), user), 404);
                Expect(await Send(f, "GET", Path(id, c), user: user), 404); Expect(await Send(f, "PUT", Path(id, c), EditBody(), user, Tag(r)), 404); Expect(await Send(f, "DELETE", Path(id, c), user: user, tag: Tag(r)), 404);
                Assert(await Snapshot(f) == before); });
        }
        await check("Savings ownership: nested owned-goal and contribution mismatch returns 404", async f => { var a = await Goal(f); var b = await Goal(f); var r = await Contribute(f, a); var c = ContributionId(r);
            Expect(await Send(f, "GET", Path(b, c)), 404); Expect(await Send(f, "PUT", Path(b, c), EditBody(), tag: Tag(r)), 404); Expect(await Send(f, "DELETE", Path(b, c), tag: Tag(r)), 404); });
        await check("Savings ownership: lists include only authenticated owner's goals", async f => { await Goal(f); var own = await Goal(f, user: 2); var j = Expect(await Send(f, "GET", Root, user: 2)); Assert(j.GetProperty("totalCount").GetInt64() == 1 && j.GetProperty("items")[0].GetProperty("goalId").GetInt32() == own);
            Assert(Expect(await Send(f, "GET", Root, user: 3)).GetProperty("totalCount").GetInt64() == 0); });
    }

    private static async Task HttpAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings HTTP: goal Location resolves to its GET action", async f => { var r = await Send(f, "POST", Root, GoalBody()); var id = Expect(r, 201).GetProperty("goalId").GetInt32(); Assert(r.Location is not null);
            var uri = new Uri(r.Location!, UriKind.RelativeOrAbsolute); var path = uri.IsAbsoluteUri ? uri.PathAndQuery : uri.OriginalString; Assert(path == Path(id)); Assert(Expect(await Send(f, "GET", path)).GetProperty("goalId").GetInt32() == id); });
        await check("Savings HTTP: contribution Location resolves to nested GET action", async f => { var id = await Goal(f); var r = await Contribute(f, id); Assert(r.Location is not null);
            var uri = new Uri(r.Location!, UriKind.RelativeOrAbsolute); var path = uri.IsAbsoluteUri ? uri.PathAndQuery : uri.OriginalString; Assert(path == Path(id, ContributionId(r))); Expect(await Send(f, "GET", path)); });
        await check("Savings HTTP: goal ETag quotes the current canonical response version", async f => { var id = await Goal(f); var r = await Read(f, id); var j = Expect(r);
            Assert(Tag(r) == "\"" + j.GetProperty("version").GetString() + "\""); Assert(Convert.FromBase64String(Tag(r)[1..^1]).Length == 8); });
        await check("Savings HTTP: contribution ETag uses child not parent version", async f => { var id = await Goal(f); var r = await Contribute(f, id); var j = Json(r);
            Assert(Tag(r) == "\"" + j.GetProperty("currentContribution").GetProperty("version").GetString() + "\""); Assert(Tag(r) != Tag(await Read(f, id))); Assert(Tag(await Send(f, "GET", Path(id, ContributionId(r)))) == Tag(r)); });
        await check("Savings HTTP: all five mutations require If-Match", async f => { var id = await Goal(f); var r = await Contribute(f, id); var child = Path(id, ContributionId(r));
            foreach (var item in new (string Method, string Path, object? Body)[] { ("PUT", Path(id), GoalBody()), ("POST", Path(id) + "/cancel", null), ("DELETE", Path(id), null), ("PUT", child, EditBody()), ("DELETE", child, null) })
                Expect(await Send(f, item.Method, item.Path, item.Body), 428); });
        foreach (var item in new (string Name, string[] Values)[]
        {
            ("empty", [""]), ("unquoted", ["AQIDBAUGBwg="]), ("weak", ["W/\"AQIDBAUGBwg=\""]), ("wildcard", ["*"]),
            ("malformed base64", ["\"!!!!!!!!!!!=\""]), ("wrong byte count", ["\"AQID\""]), ("empty quoted", ["\"\""]),
            ("multiple header fields", ["\"AQIDBAUGBwg=\"", "\"AQIDBAUGBwk=\""]),
            ("comma list", ["\"AQIDBAUGBwg=\", \"AQIDBAUGBwk=\""]), ("noncanonical padding bits", ["\"AQIDBAUGBwh=\""])
        })
            await check("Savings HTTP: If-Match rejects " + item.Name, async f => { var id = await Goal(f); var before = await Snapshot(f);
                var r = await f.SendAsync("PUT", Path(id), GoalBody(), f.Issue(1), headers: Match(item.Values)); Expect(r, 400); Assert(r.ETag is null && await Snapshot(f) == before); });
        await check("Savings HTTP: strong token accepts only surrounding HTTP optional whitespace", async f => { var id = await Goal(f); Expect(await Send(f, "PUT", Path(id), GoalBody(), tag: " \t" + Tag(await Read(f, id)) + "\t ")); });
        await check("Savings HTTP: stale tokens fail all five mutation operations", async f => { var id = await Goal(f); var r = await Contribute(f, id); var oldGoal = Tag(await Read(f, id)); var oldChild = Tag(r); Expect(await Edit(f, id, ContributionId(r), 30)); var before = await Snapshot(f);
            Expect(await Send(f, "PUT", Path(id), GoalBody(), tag: oldGoal), 412); Expect(await Send(f, "POST", Path(id) + "/cancel", tag: oldGoal), 412); Expect(await Send(f, "DELETE", Path(id), tag: oldGoal), 412);
            Expect(await Send(f, "PUT", Path(id, ContributionId(r)), EditBody(), tag: oldChild), 412); Expect(await Send(f, "DELETE", Path(id, ContributionId(r)), tag: oldChild), 412); Assert(await Snapshot(f) == before); });
        await check("Savings HTTP: correction returns fresh child and parent versions", async f => { var id = await Goal(f); var r = await Contribute(f, id); var oldGoal = Tag(await Read(f, id)); var update = await Edit(f, id, ContributionId(r)); var j = Expect(update);
            Assert(Tag(update) != Tag(r) && Tag(update) == "\"" + j.GetProperty("contribution").GetProperty("version").GetString() + "\""); Assert(Tag(await Read(f, id)) != oldGoal); });
        await check("Savings HTTP: deletion receipt has no current-contribution ETag", async f => { var id = await Goal(f); var r = await Contribute(f, id); var deletion = await Remove(f, id, ContributionId(r)); var j = Expect(deletion);
            Assert(deletion.ETag is null && j.GetProperty("goal").GetProperty("version").GetString() is { Length: 12 }); });
        await check("Savings HTTP: business errors are safe ProblemDetails", async f => { var id = await Goal(f); Expect(await Cancel(f, id)); var r = await Send(f, "POST", Path(id) + "/contributions", ContributionBody()); var j = Expect(r, 409);
            Assert(r.ContentType?.StartsWith("application/problem+json", StringComparison.Ordinal) == true && j.GetProperty("status").GetInt32() == 409 && j.GetProperty("title").GetString() is not null);
            Assert(!r.Body.Contains("SqlException") && !r.Body.Contains("StackTrace")); });
        await check("Savings HTTP: simulated busy result maps to 503", async f => { var id = await Goal(f); var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
            scope.BeforeWrite = () => throw new FinanceException(503, "Financial records are busy. Retry the request."); var r = await Send(f, "POST", Path(id) + "/contributions", ContributionBody()); Expect(r, 503); Assert(r.ContentType?.Contains("problem+json") == true); });
        await check("Savings HTTP: simulated EF concurrency exception maps safely to 412", async f => { var id = await Goal(f); var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>(); var before = await Snapshot(f);
            scope.BeforeWrite = () => throw new DbUpdateConcurrencyException("PRIVATE_CONCURRENCY_DETAIL"); var r = await Send(f, "PUT", Path(id), GoalBody(), tag: Tag(await Read(f, id))); Expect(r, 412); Assert(!r.Body.Contains("PRIVATE_CONCURRENCY_DETAIL") && await Snapshot(f) == before); });
        await check("Savings HTTP: goal creation rejects owner status and derived overposting", async f => { foreach (var field in new[] { "userId", "status", "savedAmount", "rowVersion" }) { var body = new Dictionary<string, object?> { ["name"] = "Goal", ["targetAmount"] = 100, [field] = "spoof" };
            Expect(await Send(f, "POST", Root, body), 400); } });
        await check("Savings HTTP: contribution creation rejects ledger currency and owner fields", async f => { var id = await Goal(f); foreach (var field in new[] { "userId", "goalId", "requestId", "originalAmount", "deletedAtUtc", "currencyCode" }) {
            var body = new Dictionary<string, object?> { ["clientId"] = Guid.NewGuid(), ["amount"] = 1, ["contributedOn"] = Today, [field] = "spoof" }; Expect(await Send(f, "POST", Path(id) + "/contributions", body), 400); } });
        await check("Savings HTTP: updates reject create-only identity and status fields", async f => { var id = await Goal(f); var r = await Contribute(f, id);
            Expect(await Send(f, "PUT", Path(id), new { name = "Goal", targetAmount = 100, status = "Completed" }, tag: Tag(await Read(f, id))), 400);
            Expect(await Send(f, "PUT", Path(id, ContributionId(r)), ContributionBody(), tag: Tag(r)), 400); });
        await check("Savings HTTP: malformed literal JSON rejected", async f => { Expect(await f.SendAsync("POST", Root, bearer: f.Issue(1), rawJson: "{bad-json"), 400); });
        await check("Savings HTTP: unsupported duplicate blank and invalid goal query values", async f => { foreach (var query in new[] { "userId=2", "page=1&page=2", "status=", "status=Other", "status=active", "status=0", "page=bad", "page=0", "pageSize=101", "page=2147483647&pageSize=100" }) Expect(await Send(f, "GET", Root + "?" + query), 400); });
        await check("Savings HTTP: invalid contribution query dates and pagination rejected", async f => { var id = await Goal(f); foreach (var query in new[] { "userId=2", "pageSize=0", "fromDate=2026-11-03&toDate=2026-11-01", "fromDate=not-a-date", "toDate=", "page=1&page=2" }) Expect(await Send(f, "GET", Path(id) + "/contributions?" + query), 400); });
        await check("Savings HTTP: resource routes reject query-owner injection", async f => { var id = await Goal(f); Expect(await Send(f, "GET", Path(id) + "?userId=1"), 400); Expect(await Send(f, "POST", Root + "?userId=1", GoalBody()), 400); });
        await check("Savings HTTP: cancel and both deletion routes reject request bodies", async f => { var id = await Goal(f); var r = await Contribute(f, id); var tag = Tag(await Read(f, id));
            Expect(await Send(f, "POST", Path(id) + "/cancel", new { }, tag: tag), 400); Expect(await Send(f, "DELETE", Path(id), new { }, tag: tag), 400);
            Expect(await Send(f, "DELETE", Path(id, ContributionId(r)), new { }, tag: Tag(r)), 400); });
        await check("Savings HTTP: savings root and nested routes enforce HTTPS with no-store", async f => { foreach (var path in new[] { Root, Root + "/1", Root + "/1/contributions", Root + "/1/contributions/1" }) {
            var r = await f.SendAsync("GET", path, bearer: f.Issue(1), scheme: "http"); Expect(r, 400); Assert(r.CacheControl == "no-store" && r.Pragma == "no-cache"); } });
        await check("Savings HTTP: successful and failed HTTPS requests forbid caching", async f => { var id = await Goal(f); foreach (var path in new[] { Path(id), Root + "/99999", Root + "?status=Other" }) {
            var r = await Send(f, "GET", path); Assert(r.CacheControl == "no-store" && r.Pragma == "no-cache"); } });
        await check("Savings HTTP: service preconditions remain enforced without controller parsing", async f => { var id = await Goal(f); using var scope = f.Services.CreateScope(); var service = new SavingsGoalService(scope.ServiceProvider.GetRequiredService<ApplicationDbContext>(), new SavingsCurrentUser(), scope.ServiceProvider.GetRequiredService<IFinancialWriteScope>(), f.Clock);
            var request = new PersonalFinance.Infrastructure.Finance.Contracts.UpdateSavingsGoalRequest { Name = "Goal", TargetAmount = 100 };
            Assert((await service.UpdateAsync(id, request, null, default)).StatusCode == 428); Assert((await service.UpdateAsync(id, request, "bad", default)).StatusCode == 400);
            Assert((await service.UpdateAsync(id, request, "AQIDBAUGBwg=", default)).StatusCode == 412); });
    }

    private static async Task RegressionAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        await check("Savings regression: allocations corrections and deletion never create expenses", async f => { var id = await Goal(f); var c = await Contribute(f, id); Expect(await Edit(f, id, ContributionId(c), 40)); Expect(await Remove(f, id, ContributionId(c)));
            await f.DbAsync(async db => Assert(!await db.Transactions.AnyAsync())); });
        await check("Savings regression: existing budget spending excludes all savings mutations", async f => { var budget = Expect(await Send(f, "POST", "/api/budgets", new { categoryId = 2, periodMonth = "2026-11-01", limitAmount = 100, alertThresholdPct = 80 }), 201).GetProperty("budgetId").GetInt32();
            Expect(await Send(f, "POST", "/api/transactions", new { clientId = Guid.NewGuid(), type = "Expense", categoryId = 2, amount = 10, currencyCode = "PHP", transactionDate = Today }), 201);
            var id = await Goal(f); var c = await Contribute(f, id, 100); Expect(await Edit(f, id, ContributionId(c), 50)); Expect(await Remove(f, id, ContributionId(c)));
            Assert(Expect(await Send(f, "GET", $"/api/budgets/{budget}")).GetProperty("spentAmount").GetDecimal() == 10); });
        await check("Savings regression: base-currency allocations never consult FX", async f => { f.Fx.Unavailable = true; var id = await Goal(f); var c = await Contribute(f, id); Expect(await Edit(f, id, ContributionId(c))); Expect(await Remove(f, id, ContributionId(c))); Assert(f.Fx.Calls == 0); });
        await check("Savings regression: savings lifecycle does not emit notifications", async f => { var id = await Goal(f); await Contribute(f, id, 100); Expect(await Cancel(f, id)); await f.DbAsync(async db => Assert(!await db.Notifications.AnyAsync())); });
        await check("Savings regression: category identity and financial relationships preserved", async f => { var id = await Goal(f); await Contribute(f, id); Expect(await Send(f, "POST", "/api/categories", new { name = "Savings unaffected", type = "Expense" }), 201);
            await f.DbAsync(async db => { Assert(await db.Categories.CountAsync() == 6); Assert((await db.GoalContributions.SingleAsync()).GoalId == id); Assert(!await db.RecurringBills.AnyAsync()); }); });
        await check("Savings regression: prior finance routes retain HTTPS and no-cache behavior", async f => { foreach (var path in new[] { "/api/categories", "/api/transactions", "/api/budgets" }) {
            var insecure = await f.SendAsync("GET", path, bearer: f.Issue(1), scheme: "http"); Assert(insecure.Status == 400 && insecure.CacheControl == "no-store"); var secure = await Send(f, "GET", path); Expect(secure); Assert(secure.Pragma == "no-cache"); } });
    }
}
