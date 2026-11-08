using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.FinanceTests;

internal static class BillPaymentTests
{
    private static readonly DateOnly Today = new(2026, 11, 3);

    private sealed class TestUser(int? id, bool administrator = false) : ICurrentUser
    {
        public int? UserId => id;
        public bool IsAdministrator => administrator;
        public bool Owns(int resourceOwnerId) => id == resourceOwnerId;
    }

    private sealed class ObservedBudgets(IBudgetUsageService inner, bool fail = false) : IBudgetUsageService
    {
        public int Reads { get; private set; }
        public int Recalculations { get; private set; }
        public Task<IReadOnlyList<BudgetResponse>> ReadAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct)
        { Reads++; return inner.ReadAsync(userId, periods, ct); }
        public Task<IReadOnlyList<BudgetResponse>> RecalculateAsync(int userId, IReadOnlyCollection<BudgetPeriod> periods, CancellationToken ct)
        {
            Recalculations++;
            if (fail) throw new InvalidOperationException("Injected bill budget failure");
            return inner.RecalculateAsync(userId, periods, ct);
        }
    }

    private sealed class ObservedConversion(IConversionService inner, Action? after = null) : IConversionService
    {
        public int Calls { get; private set; }
        public async Task<ConversionSnapshot> ConvertAsync(decimal amount, string source, string target, DateOnly date, CancellationToken ct)
        {
            Calls++;
            var result = await inner.ConvertAsync(amount, source, target, date, ct);
            after?.Invoke();
            return result;
        }
    }

    private sealed class ObservedWrites(IFinancialWriteScope inner, bool concurrencyFailure = false) : IFinancialWriteScope
    {
        public int Calls { get; private set; }
        public string[] Resources { get; private set; } = [];
        public Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken, Task<T>> operation, CancellationToken ct)
        {
            Calls++;
            Resources = resources.ToArray();
            if (concurrencyFailure) throw new DbUpdateConcurrencyException("Injected bill concurrency failure");
            return inner.ExecuteAsync(resources, operation, ct);
        }
    }

    private static void Assert(bool value, string detail = "Bill payment assertion failed")
    { if (!value) throw new InvalidOperationException(detail); }

    private static BillPaymentResponse Expect(FinanceResult<BillPaymentResponse> result, int status = 201)
    {
        Assert(result.StatusCode == status && result.Succeeded && result.Value is not null,
            $"Expected success {status}; got {result.StatusCode}: {result.Error}");
        return result.Value!;
    }

    private static void Reject(FinanceResult<BillPaymentResponse> result, int status, string? error = null)
    {
        Assert(result.StatusCode == status && !result.Succeeded && result.Value is null,
            $"Expected failure {status}; got {result.StatusCode}: {result.Error}");
        if (error is not null) Assert(result.Error == error, result.Error ?? "Missing error");
    }

    private static PayBillRequest Request(Guid? client = null, DateOnly? due = null) =>
        new() { ClientId = client ?? Guid.NewGuid(), DueDate = due ?? Today };

    private static BillPaymentService Service(FinanceFixture f, IServiceProvider provider, int? user = 1,
        IConversionService? conversion = null, IBudgetUsageService? budgets = null, IFinancialWriteScope? writes = null) =>
        new(provider.GetRequiredService<ApplicationDbContext>(), new TestUser(user, user == 3),
            writes ?? provider.GetRequiredService<IFinancialWriteScope>(),
            conversion ?? provider.GetRequiredService<IConversionService>(), f.Clock,
            budgets ?? provider.GetRequiredService<IBudgetUsageService>());

    private static async Task<FinanceResult<BillPaymentResponse>> Pay(FinanceFixture f, int billId,
        PayBillRequest? request = null, int? user = 1, CancellationToken ct = default)
    {
        using var scope = f.Services.CreateScope();
        return await Service(f, scope.ServiceProvider, user).PayAsync(billId, request ?? Request(), ct);
    }

    private static async Task<int> SeedBill(FinanceFixture f, Action<RecurringBill>? change = null)
    {
        var id = 0;
        await f.DbAsync(async db =>
        {
            var bill = new RecurringBill { UserId = 1, CategoryId = 2, Name = "Test bill", Amount = 10m,
                CurrencyCode = "PHP", Frequency = BillFrequency.Monthly, AnchorDate = Today,
                NextDueDate = Today, IntendedDayOfMonth = 3, ReminderDaysBefore = 0 };
            change?.Invoke(bill);
            db.RecurringBills.Add(bill);
            await db.SaveChangesAsync();
            id = bill.BillId;
        });
        return id;
    }

    private static Task ChangeBill(FinanceFixture f, int id, Action<RecurringBill> change) => f.DbAsync(async db =>
    {
        change(await db.RecurringBills.SingleAsync(b => b.BillId == id));
        await db.SaveChangesAsync();
    });

    private static async Task<string> Snapshot(FinanceFixture f)
    {
        var result = "";
        await f.DbAsync(async db => result = JsonSerializer.Serialize(new
        {
            Bills = await db.RecurringBills.AsNoTracking().OrderBy(b => b.BillId).ToListAsync(),
            Transactions = await db.Transactions.AsNoTracking().OrderBy(t => t.TransactionId).ToListAsync(),
            Receipts = await db.BillPaymentReceipts.AsNoTracking().OrderBy(r => r.ReceiptId).ToListAsync(),
            Budgets = await db.Budgets.AsNoTracking().OrderBy(b => b.BudgetId).ToListAsync(),
            Rates = await db.ExchangeRates.AsNoTracking().OrderBy(r => r.BaseCurrency).ThenBy(r => r.QuoteCurrency).ThenBy(r => r.RateDate).ToListAsync()
        }));
        return result;
    }

    private static async Task RejectUnchanged(FinanceFixture f, int billId, PayBillRequest request, int status)
    {
        var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts; var calls = f.Fx.Calls;
        Reject(await Pay(f, billId, request), status);
        Assert(await Snapshot(f) == before, "Rejected payment changed stored state");
        Assert(f.RowVersions.SaveAttempts == saves && f.Fx.Calls == calls, "Pre-write rejection saved or requested FX");
    }

    public static async Task RunAsync(Func<string, Func<FinanceFixture, Task>, Task> check)
    {
        var count = 0;
        async Task Case(string name, Func<FinanceFixture, Task> action)
        { count++; await check("Bill payment offline: " + name, action); }

        await Case("simulator bootstrap uses InMemory, shared fake dependencies and eight-byte bill versions", async f =>
        {
            var id = await SeedBill(f);
            await f.DbAsync(async db =>
            {
                Assert(db.Database.IsInMemory());
                Assert(f.Services.GetRequiredService<IFinancialWriteScope>() is MemoryWriteScope);
                Assert(ReferenceEquals(f.Services.GetRequiredService<IFrankfurterClient>(), f.Fx));
                var bill = await db.RecurringBills.SingleAsync(b => b.BillId == id);
                var old = bill.RowVersion.ToArray(); Assert(old.Length == 8);
                bill.Name = "Changed";
                await db.SaveChangesAsync(acceptAllChangesOnSuccess: false);
                Assert(bill.RowVersion.Length == 8 && !old.SequenceEqual(bill.RowVersion));
                Assert(db.Entry(bill).Property(b => b.RowVersion).OriginalValue.SequenceEqual(old));
                db.ChangeTracker.AcceptAllChanges();
            });
        });

        await Case("full acceptance preserves all receipt and transaction snapshots", async f =>
        {
            var id = await SeedBill(f); var request = Request();
            var result = Expect(await Pay(f, id, request)); var r = result.Receipt;
            Assert(!result.Replayed && result.AffectedBudgets is not null && result.AffectedBudgets.Count == 0);
            Assert(r.ReceiptId > 0 && r.TransactionId > 0 && r.UserId == 1 && r.BillId == id
                && r.ClientId == request.ClientId && r.DueDate == Today && r.CategoryId == 2
                && r.Amount == 10m && r.CurrencyCode == "PHP" && r.BaseCurrency == "PHP"
                && r.FxRate == 1m && r.FxRateDate == Today && r.AmountBase == 10m
                && r.PaidOn == Today && r.RecordedAtUtc == f.Clock.Now.UtcDateTime && r.CanonicalVersion == 1);
            await f.DbAsync(async db =>
            {
                var t = await db.Transactions.SingleAsync(); var stored = await db.BillPaymentReceipts.SingleAsync();
                Assert(stored.ReceiptId == r.ReceiptId && stored.TransactionId == t.TransactionId
                    && t.UserId == r.UserId && t.ClientId == r.ClientId && t.BillId == r.BillId
                    && t.BillDueDate == r.DueDate && t.CategoryId == r.CategoryId && t.Type == TransactionType.Expense
                    && t.Amount == r.Amount && t.CurrencyCode == r.CurrencyCode && t.FxRate == r.FxRate
                    && t.FxRateDate == r.FxRateDate && t.AmountBase == r.AmountBase
                    && t.TransactionDate == r.PaidOn && t.CreatedAtUtc == r.RecordedAtUtc
                    && t.Description == BillPaymentService.PaymentDescription);
                Assert((await db.RecurringBills.SingleAsync()).NextDueDate == new DateOnly(2026, 12, 3));
            });
        });

        await Case("budget spending uses acceptance month and replay never recalculates or double counts", async f =>
        {
            var due = new DateOnly(2026, 10, 3);
            var id = await SeedBill(f, b => { b.AnchorDate = due; b.NextDueDate = due; });
            Assert((await f.SendAsync("POST", "/api/budgets", new { categoryId = 2, periodMonth = "2026-11-01", limitAmount = 100m, alertThresholdPct = 80 }, f.Issue(1))).Status == 201);
            var request = Request(due: due);
            using (var scope = f.Services.CreateScope())
            {
                var budgets = new ObservedBudgets(scope.ServiceProvider.GetRequiredService<IBudgetUsageService>());
                var accepted = Expect(await Service(f, scope.ServiceProvider, budgets: budgets).PayAsync(id, request, default));
                Assert(budgets.Recalculations == 1 && accepted.AffectedBudgets!.Single().SpentAmount == 10m
                    && accepted.AffectedBudgets!.Single().PeriodMonth == new DateOnly(2026, 11, 1));
            }
            var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts;
            using (var scope = f.Services.CreateScope())
            {
                var budgets = new ObservedBudgets(scope.ServiceProvider.GetRequiredService<IBudgetUsageService>());
                var conversion = new ObservedConversion(scope.ServiceProvider.GetRequiredService<IConversionService>());
                var replay = Expect(await Service(f, scope.ServiceProvider, conversion: conversion, budgets: budgets).PayAsync(id, request, default), 200);
                Assert(replay.Replayed && replay.AffectedBudgets is null && budgets.Reads == 0 && budgets.Recalculations == 0 && conversion.Calls == 0);
            }
            Assert(await Snapshot(f) == before && f.RowVersions.SaveAttempts == saves);
        });

        await Case("replay survives deactivation and changed unpaid settings with the original receipt", async f =>
        {
            var id = await SeedBill(f); var request = Request(); var accepted = Expect(await Pay(f, id, request));
            await ChangeBill(f, id, b => { b.IsActive = false; b.Amount = 99m; b.CategoryId = 5; });
            var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts; f.Fx.Unavailable = true;
            var replay = Expect(await Pay(f, id, request), 200);
            Assert(replay.Receipt == accepted.Receipt && replay.Replayed && replay.AffectedBudgets is null);
            Assert(await Snapshot(f) == before && f.RowVersions.SaveAttempts == saves && f.Fx.Calls == 0);
            await RejectUnchanged(f, id, Request(due: new(2026, 12, 3)), 409);
        });

        foreach (var conflict in new[] { "bill", "date", "different-client-same-occurrence" })
            await Case("canonical conflict " + conflict, async f =>
            {
                var id = await SeedBill(f); var request = Request(); Expect(await Pay(f, id, request));
                var target = conflict == "bill" ? await SeedBill(f) : id;
                var retry = conflict == "different-client-same-occurrence" ? Request() : Request(request.ClientId,
                    conflict == "date" ? Today.AddDays(-1) : Today);
                await RejectUnchanged(f, target, retry, 409);
            });

        await Case("client namespace is per authenticated user", async f =>
        {
            var key = Guid.NewGuid(); var own = await SeedBill(f); var other = await SeedBill(f, b => { b.UserId = 2; b.CategoryId = 3; });
            Expect(await Pay(f, own, Request(key))); Expect(await Pay(f, other, Request(key), 2));
            await f.DbAsync(async db => Assert(await db.BillPaymentReceipts.CountAsync() == 2 && await db.Transactions.CountAsync() == 2));
        });

        foreach (var occupied in new[] { "client", "occurrence" })
            await Case("existing transaction collision " + occupied, async f =>
            {
                var id = await SeedBill(f); var request = Request();
                await f.DbAsync(async db =>
                {
                    db.Transactions.Add(new() { UserId = 1, ClientId = occupied == "client" ? request.ClientId!.Value : Guid.NewGuid(),
                        BillId = occupied == "occurrence" ? id : null, BillDueDate = occupied == "occurrence" ? Today : null,
                        CategoryId = 2, Type = TransactionType.Expense, Amount = 10m, CurrencyCode = "PHP", FxRate = 1m,
                        FxRateDate = Today, AmountBase = 10m, TransactionDate = Today, CreatedAtUtc = f.Clock.Now.UtcDateTime });
                    await db.SaveChangesAsync();
                });
                await RejectUnchanged(f, id, request, 409);
            });

        foreach (var offset in new[] { -1, 1, 31 })
            await Case("oldest occurrence rejects requested day offset " + offset, async f =>
                await RejectUnchanged(f, await SeedBill(f), Request(due: Today.AddDays(offset)), 409));
        await Case("future oldest occurrence rejected", async f =>
        {
            var due = Today.AddDays(1); var id = await SeedBill(f, b => { b.AnchorDate = due; b.NextDueDate = due; b.IntendedDayOfMonth = 4; });
            await RejectUnchanged(f, id, Request(due: due), 409);
        });

        foreach (var item in new[] {
            (BillFrequency.Weekly, new DateOnly(2026, 11, 3), (byte?)null, new DateOnly(2026, 11, 10)),
            (BillFrequency.Monthly, new DateOnly(2026, 1, 31), (byte?)31, new DateOnly(2026, 2, 28)),
            (BillFrequency.Quarterly, new DateOnly(2026, 1, 31), (byte?)31, new DateOnly(2026, 4, 30)),
            (BillFrequency.Yearly, new DateOnly(2027, 2, 28), (byte?)29, new DateOnly(2028, 2, 29)) })
            await Case("recurrence advancement " + item.Item1, async f =>
            {
                f.Clock.Now = new DateTimeOffset(item.Item2.ToDateTime(new TimeOnly(12), DateTimeKind.Utc));
                var id = await SeedBill(f, b => { b.Frequency = item.Item1; b.AnchorDate = item.Item2; b.NextDueDate = item.Item2; b.IntendedDayOfMonth = item.Item3; });
                Expect(await Pay(f, id, Request(due: item.Item2)));
                await f.DbAsync(async db => { var b = await db.RecurringBills.SingleAsync(); Assert(b.AnchorDate == item.Item2 && b.NextDueDate == item.Item4 && b.IntendedDayOfMonth == item.Item3); });
            });
        await Case("monthly day 31 restored after clamped February", async f =>
        {
            f.Clock.Now = new DateTimeOffset(2026, 3, 31, 12, 0, 0, TimeSpan.Zero);
            var id = await SeedBill(f, b => { b.AnchorDate = new(2026, 1, 31); b.NextDueDate = b.AnchorDate; b.IntendedDayOfMonth = 31; });
            Expect(await Pay(f, id, Request(due: new(2026, 1, 31)))); Expect(await Pay(f, id, Request(due: new(2026, 2, 28))));
            await f.DbAsync(async db => Assert((await db.RecurringBills.SingleAsync()).NextDueDate == new DateOnly(2026, 3, 31)));
        });
        foreach (var frequency in Enum.GetValues<BillFrequency>())
            await Case("recurrence overflow rejects without writes " + frequency, async f =>
            {
                f.Clock.Now = new DateTimeOffset(9999, 12, 31, 12, 0, 0, TimeSpan.Zero);
                var id = await SeedBill(f, b => { b.Frequency = frequency; b.AnchorDate = DateOnly.MaxValue;
                    b.NextDueDate = DateOnly.MaxValue; b.IntendedDayOfMonth = frequency == BillFrequency.Weekly ? null : (byte)31; });
                await RejectUnchanged(f, id, Request(due: DateOnly.MaxValue), 409);
            });

        foreach (var invalid in new[] { "weekly-offset", "weekly-day", "quarter-offset", "year-offset", "anchor-day", "due-day", "missing-day", "unsupported", "minimum-anchor", "before-anchor" })
            await Case("invalid anchored schedule " + invalid, async f =>
            {
                var id = await SeedBill(f, b =>
                {
                    switch (invalid)
                    {
                        case "weekly-offset": b.Frequency = BillFrequency.Weekly; b.IntendedDayOfMonth = null; b.AnchorDate = Today.AddDays(-1); break;
                        case "weekly-day": b.Frequency = BillFrequency.Weekly; break;
                        case "quarter-offset": b.Frequency = BillFrequency.Quarterly; b.AnchorDate = new(2026, 10, 3); break;
                        case "year-offset": b.Frequency = BillFrequency.Yearly; b.AnchorDate = new(2026, 10, 3); break;
                        case "anchor-day": b.AnchorDate = new(2026, 10, 2); break;
                        case "due-day": b.AnchorDate = new(2026, 10, 2); b.IntendedDayOfMonth = 2; break;
                        case "missing-day": b.IntendedDayOfMonth = null; break;
                        case "unsupported": b.Frequency = (BillFrequency)99; break;
                        case "minimum-anchor": b.AnchorDate = DateOnly.MinValue; break;
                        case "before-anchor": b.AnchorDate = Today.AddMonths(1); break;
                    }
                });
                await RejectUnchanged(f, id, Request(), 409);
            });

        foreach (var category in new[] { 1, 3, 5, 999 })
            await Case("unavailable category " + category, async f =>
                await RejectUnchanged(f, await SeedBill(f, b => b.CategoryId = category), Request(), 409));
        await Case("shared active Expense category accepts payment", async f => Expect(await Pay(f, await SeedBill(f, b => b.CategoryId = 4))));
        await Case("inactive bill rejects new payment", async f => await RejectUnchanged(f, await SeedBill(f, b => b.IsActive = false), Request(), 409));
        foreach (int? user in new int?[] { null, 2, 3 })
            await Case("authentication and owner privacy " + (user?.ToString() ?? "anonymous"), async f =>
            {
                var id = await SeedBill(f); var before = await Snapshot(f);
                Reject(await Pay(f, id, user: user), user is null ? 401 : 404);
                Assert(await Snapshot(f) == before);
            });
        await Case("missing bill returns 404", async f => Reject(await Pay(f, 999), 404));
        foreach (var invalid in new[] { "bill", "missing-client", "empty-client", "missing-date" })
            await Case("invalid request " + invalid, async f =>
            {
                var id = await SeedBill(f); var request = Request();
                if (invalid == "missing-client") request.ClientId = null;
                if (invalid == "empty-client") request.ClientId = Guid.Empty;
                if (invalid == "missing-date") request.DueDate = null;
                await RejectUnchanged(f, invalid == "bill" ? 0 : id, request, 400);
            });
        await Case("request rejects client financial overposting", _ =>
        {
            var rejected = false;
            try { JsonSerializer.Deserialize<PayBillRequest>("{\"clientId\":\"11111111-1111-1111-1111-111111111111\",\"dueDate\":\"2026-11-03\",\"amount\":1}", new JsonSerializerOptions(JsonSerializerDefaults.Web)); }
            catch (JsonException) { rejected = true; }
            Assert(rejected); return Task.CompletedTask;
        });
        foreach (var amount in new[] { 0m, -1m, 1.001m, 10000000000000000m })
            await Case("invalid scheduled amount " + amount, async f =>
                await RejectUnchanged(f, await SeedBill(f, b => b.Amount = amount), Request(), 400));
        foreach (var currency in new[] { "", "php", "US", "USDD" })
            await Case("invalid scheduled currency " + currency, async f =>
                await RejectUnchanged(f, await SeedBill(f, b => b.CurrencyCode = currency), Request(), 409));
        await Case("unsupported provider currency", async f =>
            await RejectUnchanged(f, await SeedBill(f, b => b.CurrencyCode = "ZZZ"), Request(), 400));

        foreach (var age in new[] { 0, 7, 8, -1 })
            await Case("FX quote age " + age, async f =>
            {
                var id = await SeedBill(f, b => b.CurrencyCode = "USD"); f.Fx.Age = age;
                var before = await Snapshot(f); var result = await Pay(f, id);
                if (age is 0 or 7)
                {
                    var r = Expect(result).Receipt;
                    Assert(r.FxRate == 56.12345679m && r.AmountBase == 561.23m && r.FxRateDate == Today.AddDays(-age));
                }
                else { Reject(result, 503); Assert(await Snapshot(f) == before); }
                Assert(f.Fx.Calls == 1);
            });
        await Case("FX midpoint rounds away from zero", async f =>
        {
            f.Fx.Rate = 1.005m; var r = Expect(await Pay(f, await SeedBill(f, b => { b.CurrencyCode = "USD"; b.Amount = 1m; }))).Receipt;
            Assert(r.AmountBase == 1.01m && r.FxRate == 1.005m);
        });
        await Case("provider outage rejects without payment writes", async f =>
        {
            var id = await SeedBill(f, b => b.CurrencyCode = "USD"); f.Fx.Unavailable = true;
            var before = await Snapshot(f); Reject(await Pay(f, id), 503); Assert(await Snapshot(f) == before);
        });
        await Case("cached valid FX survives provider outage", async f =>
        {
            var id = await SeedBill(f, b => b.CurrencyCode = "USD");
            await f.DbAsync(async db => { db.ExchangeRates.Add(new() { BaseCurrency = "USD", QuoteCurrency = "PHP", RateDate = Today.AddDays(-1), Rate = 55m, FetchedAtUtc = f.Clock.Now.UtcDateTime }); await db.SaveChangesAsync(); });
            f.Fx.Unavailable = true; var r = Expect(await Pay(f, id)).Receipt;
            Assert(r.FxRate == 55m && r.AmountBase == 550m && f.Fx.Calls == 0);
        });
        await Case("UTC midnight change rejects before payment staging", async f =>
        {
            var id = await SeedBill(f); var before = await Snapshot(f);
            using var scope = f.Services.CreateScope();
            var fx = new ObservedConversion(scope.ServiceProvider.GetRequiredService<IConversionService>(), () => f.Clock.Now = f.Clock.Now.AddDays(1));
            Reject(await Service(f, scope.ServiceProvider, conversion: fx).PayAsync(id, Request(), default), 409);
            Assert(await Snapshot(f) == before && !scope.ServiceProvider.GetRequiredService<ApplicationDbContext>().ChangeTracker.Entries().Any());
        });

        foreach (var state in new[] { EntityState.Unchanged, EntityState.Added, EntityState.Modified, EntityState.Deleted })
            foreach (var autoDetect in new[] { false, true })
                await Case($"clean-context guard preserves {state} and automatic detection {autoDetect}", async f =>
                {
                    var id = await SeedBill(f); var before = await Snapshot(f); var saves = f.RowVersions.SaveAttempts;
                    using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
                    var bill = await db.RecurringBills.SingleAsync();
                    if (state == EntityState.Added) db.Categories.Add(new() { Name = "Unsaved", UserId = 1, Type = TransactionType.Expense });
                    if (state == EntityState.Modified) { bill.Name = "Unsaved"; db.Entry(bill).State = state; }
                    if (state == EntityState.Deleted) db.Remove(bill);
                    db.ChangeTracker.AutoDetectChangesEnabled = autoDetect;
                    var entries = db.ChangeTracker.Entries().Select(e => (e.Entity, e.State)).ToArray();
                    Reject(await Service(f, scope.ServiceProvider).PayAsync(id, Request(), default), 409,
                        "Payment requires a clean DbContext. Use a fresh scope and retry.");
                    Assert(db.ChangeTracker.AutoDetectChangesEnabled == autoDetect);
                    Assert(entries.SequenceEqual(db.ChangeTracker.Entries().Select(e => (e.Entity, e.State))));
                    Assert(await Snapshot(f) == before && f.RowVersions.SaveAttempts == saves);
                });

        await Case("simulated EF InMemory stale bill token rejects a competing write", async f =>
        {
            await SeedBill(f); using var a = f.Services.CreateScope(); using var b = f.Services.CreateScope();
            var dbA = a.ServiceProvider.GetRequiredService<ApplicationDbContext>(); var dbB = b.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            var winner = await dbA.RecurringBills.SingleAsync(); var stale = await dbB.RecurringBills.SingleAsync();
            winner.Name = "Winner"; await dbA.SaveChangesAsync(); stale.Name = "Stale";
            var rejected = false; try { await dbB.SaveChangesAsync(); } catch (DbUpdateConcurrencyException) { rejected = true; }
            Assert(rejected); await f.DbAsync(async db => Assert((await db.RecurringBills.SingleAsync()).Name == "Winner"));
        });
        await Case("injected rowversion conflict maps to 409 without automatic retry", async f =>
        {
            var id = await SeedBill(f); var before = await Snapshot(f); using var scope = f.Services.CreateScope();
            var writes = new ObservedWrites(scope.ServiceProvider.GetRequiredService<IFinancialWriteScope>(), true);
            Reject(await Service(f, scope.ServiceProvider, writes: writes).PayAsync(id, Request(), default), 409);
            Assert(writes.Calls == 1 && await Snapshot(f) == before);
        });
        await Case("sorted user bill category resources reach the fake write scope", async f =>
        {
            var id = await SeedBill(f); using var scope = f.Services.CreateScope();
            var writes = new ObservedWrites(scope.ServiceProvider.GetRequiredService<IFinancialWriteScope>());
            Expect(await Service(f, scope.ServiceProvider, writes: writes).PayAsync(id, Request(), default));
            Assert(writes.Resources.SequenceEqual(new[] { "finance:write:user:1", $"finance:write:bill:{id}", "finance:write:category:2" }.Order(StringComparer.Ordinal)));
        });
        foreach (var race in new[] { "category", "deactivate", "delete" })
            await Case("deterministic BeforeWrite race " + race, async f =>
            {
                var id = await SeedBill(f); string after = "";
                var writes = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
                writes.BeforeWrite = async () =>
                {
                    await f.DbAsync(async db => { var bill = await db.RecurringBills.SingleAsync();
                        if (race == "category") bill.CategoryId = 4;
                        if (race == "deactivate") bill.IsActive = false;
                        if (race == "delete") db.Remove(bill);
                        await db.SaveChangesAsync(); });
                    after = await Snapshot(f);
                };
                Reject(await Pay(f, id), race == "delete" ? 404 : 409);
                Assert(after.Length > 0 && await Snapshot(f) == after);
            });
        foreach (var sameKey in new[] { true, false })
            await Case("simulated serialized competing payments same key=" + sameKey, async f =>
            {
                var id = await SeedBill(f); var key = Guid.NewGuid();
                var results = await Task.WhenAll(Pay(f, id, Request(key)), Pay(f, id, Request(sameKey ? key : Guid.NewGuid())));
                Assert(results.Count(r => r.StatusCode == 201) == 1 && results.Count(r => r.StatusCode == (sameKey ? 200 : 409)) == 1);
                await f.DbAsync(async db => Assert(await db.Transactions.CountAsync() == 1 && await db.BillPaymentReceipts.CountAsync() == 1));
            });
        foreach (var corrupt in new[] { "missing", "owner", "amount", "fx", "date", "link" })
            await Case("replay reconciliation detects simulated corruption " + corrupt, async f =>
            {
                var id = await SeedBill(f); var request = Request(); Expect(await Pay(f, id, request));
                await f.DbAsync(async db => { var t = await db.Transactions.SingleAsync();
                    switch (corrupt) { case "missing": db.Remove(t); break; case "owner": t.UserId = 2; break;
                        case "amount": t.Amount++; break; case "fx": t.FxRate++; break;
                        case "date": t.TransactionDate = t.TransactionDate.AddDays(-1); break; case "link": t.BillId = null; break; }
                    await db.SaveChangesAsync(); });
                await RejectUnchanged(f, id, request, 409);
            });
        await Case("accepted payment remains protected through generic API", async f =>
        {
            var r = Expect(await Pay(f, await SeedBill(f))).Receipt; var before = await Snapshot(f);
            Assert((await f.SendAsync("PUT", $"/api/transactions/{r.TransactionId}", new { type = "Expense", categoryId = 2, amount = 99m, currencyCode = "PHP", transactionDate = "2026-11-03" }, f.Issue(1))).Status == 409);
            Assert((await f.SendAsync("DELETE", $"/api/transactions/{r.TransactionId}", bearer: f.Issue(1))).Status == 409);
            Assert(await Snapshot(f) == before);
        });
        await Case("post-staging injected failure escapes and clears tracking; InMemory rollback NOT asserted", async f =>
        {
            var id = await SeedBill(f); using var scope = f.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
            var budgets = new ObservedBudgets(scope.ServiceProvider.GetRequiredService<IBudgetUsageService>(), true);
            var escaped = false;
            try { await Service(f, scope.ServiceProvider, budgets: budgets).PayAsync(id, Request(), default); }
            catch (InvalidOperationException ex) when (ex.Message == "Injected bill budget failure") { escaped = true; }
            Assert(escaped && budgets.Recalculations == 1 && !db.ChangeTracker.Entries().Any());
        });
        Console.WriteLine($"Bill payment suite: {count} cases submitted. InMemory simulation does not verify SQL rollback, constraints, native rowversion, application locks or unique-violation recovery.");
    }
}
