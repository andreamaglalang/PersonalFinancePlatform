using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.SavingsSqlTests;

// These methods are NOT registered with Program. Every case requires a future approved,
// provisioned and migrated disposable target. No timing races or transaction emulation.
internal static class SavingsSqlServiceTests
{
    public static async Task RunAsync(SavingsSqlFixture fixture,
        Func<string, Func<Task>, Task> check, CancellationToken ct)
    {
        Task Case(string name, Func<CancellationToken, Task> test) =>
            SavingsSqlFixture.CheckAsync("SQL service: " + name, test, check, ct);

        await Case("native goal version persists and advances; stale preconditions reject", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            SavingsSqlAssert.NativeVersion(goal.Version);
            await using var s = await fixture.OpenAsync(user, token);
            var row = await s.Db.SavingsGoals.AsNoTracking().SingleAsync(g => g.GoalId == goal.GoalId, token);
            SavingsSqlAssert.Require(Convert.ToBase64String(row.RowVersion) == goal.Version,
                "Native goal version must persist across contexts.");
            SavingsSqlAssert.Status(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Updated", TargetAmount = 100m }, null, token), 428);
            var updated = SavingsSqlAssert.Value(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Updated", TargetAmount = 100m }, goal.Version, token));
            Changed(goal.Version, updated.Version);
            SavingsSqlAssert.Status(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Stale", TargetAmount = 1m }, goal.Version, token), 412);
            SavingsSqlAssert.Status(await s.Service.CancelAsync(goal.GoalId, goal.Version, token), 412);
            SavingsSqlAssert.Status(await s.Service.DeleteAsync(goal.GoalId, goal.Version, token), 412);
            await using var verify = await fixture.OpenAsync(user, token);
            SavingsSqlAssert.Require(SavingsSqlAssert.Value(await verify.Service.GetAsync(goal.GoalId, token)) == updated,
                "Rejected stale mutations must preserve the winner.");
        });

        await Case("native EF concurrency detects an obsolete tracked original", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var stale = await fixture.OpenAsync(user, token);
            var old = await stale.Db.SavingsGoals.SingleAsync(g => g.GoalId == goal.GoalId, token);
            var original = old.RowVersion.ToArray();
            await using var winner = await fixture.OpenAsync(user, token);
            var updated = SavingsSqlAssert.Value(await winner.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Winner", TargetAmount = 100m }, goal.Version, token));
            old.Name = "Obsolete writer";
            SavingsSqlAssert.Require(stale.Db.Entry(old).Property(g => g.RowVersion).OriginalValue.SequenceEqual(original),
                "EF original concurrency value must remain the originally loaded native token.");
            var rejected = false;
            try { await stale.Db.SaveChangesAsync(token); }
            catch (DbUpdateConcurrencyException) { rejected = true; }
            SavingsSqlAssert.Require(rejected, "Native stale UPDATE must affect no row and raise concurrency failure.");
            await using var verify = await fixture.OpenAsync(user, token);
            SavingsSqlAssert.Require(SavingsSqlAssert.Value(await verify.Service.GetAsync(goal.GoalId, token)) == updated,
                "Obsolete direct write must not overwrite the winner.");
        });

        await Case("each contribution mutation advances parent despite unchanged Active status", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var created = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 10m), token), 201);
            Changed(goal.Version, created.Goal.Version);
            SavingsSqlAssert.NativeVersion(created.CurrentContribution!.Version);
            await using (var fresh = await fixture.OpenAsync(user, token))
            {
                var loaded = SavingsSqlAssert.Value(await fresh.Service.GetContributionAsync(goal.GoalId,
                    created.Receipt.ContributionId, token));
                SavingsSqlAssert.Require(loaded == created.CurrentContribution, "Contribution token must survive reload.");
            }
            var corrected = SavingsSqlAssert.Value(await s.Service.UpdateContributionAsync(goal.GoalId,
                created.Receipt.ContributionId, new() { Amount = 11m, ContributedOn = fixture.Clock.Today, Note = "corrected" },
                created.CurrentContribution.Version, token));
            Changed(created.Goal.Version, corrected.Goal.Version);
            Changed(created.CurrentContribution.Version, corrected.Contribution.Version);
            SavingsSqlAssert.Status(await s.Service.UpdateContributionAsync(goal.GoalId, created.Receipt.ContributionId,
                new() { Amount = 12m, ContributedOn = fixture.Clock.Today }, created.CurrentContribution.Version, token), 412);
            SavingsSqlAssert.Status(await s.Service.DeleteContributionAsync(goal.GoalId, created.Receipt.ContributionId,
                created.CurrentContribution.Version, token), 412);
            var deleted = SavingsSqlAssert.Value(await s.Service.DeleteContributionAsync(goal.GoalId,
                created.Receipt.ContributionId, corrected.Contribution.Version, token));
            Changed(corrected.Goal.Version, deleted.Goal.Version);
            SavingsSqlAssert.Require(new[] { created.Goal, corrected.Goal, deleted.Goal }.All(g => g.Status == "Active"),
                "Parent advancement must not depend on changing status.");
            await using var verify = await fixture.OpenAsync(user, token);
            SavingsSqlAssert.Require(SavingsSqlAssert.Value(await verify.Service.GetAsync(goal.GoalId, token)) == deleted.Goal,
                "Final parent version and zero total must persist.");
        });

        await Case("identical replay performs no SaveChanges and changes no stored state", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            var observer = new SavingsSqlFailureInterceptor();
            await using var s = await fixture.OpenAsync(user, token, observer);
            var request = Request(fixture, 10m);
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            var saves = observer.SaveAttempts;
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(saves == 2 && observer.SaveAttempts == saves && replay.Replayed && !replay.Deleted
                && replay.Receipt == first.Receipt && replay.CurrentContribution == first.CurrentContribution
                && replay.Goal == first.Goal, "Replay must return the existing receipt/current state without saves.");
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token),
                "Replay must leave persisted parent, child and ledger unchanged.");
        });

        await Case("conflicting ClientId preserves original accepted state", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10m);
            SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            request.Amount = 11m;
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 409);
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token),
                "Conflict must not change any accepted financial state.");
        });

        await Case("same ClientId with independently changed date conflicts without writes", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10m, "original");
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var before = await FinancialFootprintAsync(fixture, [user], token);
            // Only the date changes; it remains historical, so this is a payload conflict, not future-date validation.
            request.ContributedOn = fixture.Clock.Today.AddDays(-1);
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 409);
            SavingsSqlAssert.Require(before == await FinancialFootprintAsync(fixture, [user], token),
                "Date conflict must not change the original receipt, contribution, versions or financial rows.");
            request.ContributedOn = first.Receipt.OriginalContributedOn;
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(replay.Replayed && replay.Receipt == first.Receipt
                && replay.CurrentContribution == first.CurrentContribution, "Date conflict must preserve original replay semantics.");
            SavingsSqlAssert.Require(before == await FinancialFootprintAsync(fixture, [user], token), "Replay after date conflict wrote data.");
        });

        await Case("same ClientId with independently changed owned goal conflicts without writes", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var other = SavingsSqlAssert.Value(await s.Service.CreateAsync(new() { Name = "Other owned goal", TargetAmount = 100m }, token), 201);
            var request = Request(fixture, 10m, "original");
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var before = await FinancialFootprintAsync(fixture, [user], token);
            // Payload fields and authenticated owner stay identical; only the route's owned goal changes.
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(other.GoalId, request, token), 409);
            SavingsSqlAssert.Require(before == await FinancialFootprintAsync(fixture, [user], token),
                "Goal conflict must leave both goals, contributions and retained request history unchanged.");
            await using var verify = await fixture.OpenAsync(user, token);
            SavingsSqlAssert.Require(!await verify.Db.GoalContributions.AnyAsync(c => c.GoalId == other.GoalId, token)
                && !await verify.Db.GoalContributionRequests.AnyAsync(r => r.GoalId == other.GoalId, token),
                "Rejected goal reuse must create neither a contribution nor a ledger entry.");
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(replay.Replayed && replay.Receipt == first.Receipt
                && replay.CurrentContribution == first.CurrentContribution, "Goal conflict must preserve accepted originals.");
            SavingsSqlAssert.Require(before == await FinancialFootprintAsync(fixture, [user], token), "Replay after goal conflict wrote data.");
        });

        await Case("replay after correction returns current values and immutable originals", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10m, "original");
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var corrected = SavingsSqlAssert.Value(await s.Service.UpdateContributionAsync(goal.GoalId,
                first.Receipt.ContributionId, new() { Amount = 12m, ContributedOn = fixture.Clock.Today.AddDays(-1), Note = "correction" },
                first.CurrentContribution!.Version, token));
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(replay.Replayed && replay.Receipt == first.Receipt
                && replay.CurrentContribution == corrected.Contribution && replay.Goal == corrected.Goal,
                "Original replay must not restore pre-correction values.");
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token), "Replay changed stored state.");
        });

        await Case("deleted receipt survives deletion, cancellation and delayed retry", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10m);
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var deleted = SavingsSqlAssert.Value(await s.Service.DeleteContributionAsync(goal.GoalId,
                first.Receipt.ContributionId, first.CurrentContribution!.Version, token));
            var cancelled = SavingsSqlAssert.Value(await s.Service.CancelAsync(goal.GoalId, deleted.Goal.Version, token));
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(replay.Replayed && replay.Deleted && replay.CurrentContribution is null
                && replay.Receipt.RequestId == first.Receipt.RequestId
                && replay.Receipt.DeletedAtUtc == fixture.Clock.GetUtcNow().UtcDateTime
                && replay.Receipt.OriginalAmount == 10m && replay.Goal == cancelled,
                "Delayed retry must return a retained deletion receipt without resurrection.");
            SavingsSqlAssert.Status(await s.Service.DeleteAsync(goal.GoalId, cancelled.Version, token), 409);
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token),
                "Deleted replay/protected goal deletion must not mutate history.");
        });

        await Case("Cancelled is terminal; repeat cancellation and accepted replay are read only", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10m);
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            var cancelled = SavingsSqlAssert.Value(await s.Service.CancelAsync(goal.GoalId, first.Goal.Version, token));
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            SavingsSqlAssert.Require(SavingsSqlAssert.Value(await s.Service.CancelAsync(goal.GoalId, cancelled.Version, token)) == cancelled,
                "Repeated cancellation must preserve version.");
            SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 1m), token), 409);
            SavingsSqlAssert.Status(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Cannot edit", TargetAmount = 10m }, cancelled.Version, token), 409);
            SavingsSqlAssert.Status(await s.Service.UpdateContributionAsync(goal.GoalId, first.Receipt.ContributionId,
                new() { Amount = 1m, ContributedOn = fixture.Clock.Today }, first.CurrentContribution!.Version, token), 409);
            SavingsSqlAssert.Status(await s.Service.DeleteContributionAsync(goal.GoalId, first.Receipt.ContributionId,
                first.CurrentContribution.Version, token), 409);
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token),
                "Cancelled-goal operations must leave all financial state unchanged.");
        });

        await Case("canonical notes and decimal payload comparison use immutable request version", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var request = Request(fixture, 10.00m, "\u00a0A  B\u3000");
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            request.Amount = 10m; request.Note = "A  B";
            var replay = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token));
            SavingsSqlAssert.Require(replay.Receipt == first.Receipt && replay.Receipt.OriginalNote == "A  B"
                && replay.Receipt.CanonicalVersion == 1, "Equivalent boundary trimming must replay canonical version one.");
            request.Note = "A B";
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 409);
            request.Note = "a  B";
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 409);
            var blank = Request(fixture, 1m, " \t\u00a0");
            SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, blank, token), 201);
            blank.Note = null;
            SavingsSqlAssert.Require(SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, blank, token))
                .Receipt.OriginalNote is null, "Blank and null notes must compare canonically.");
        });

        await Case("same ClientId is independent across real user scopes", async token =>
        {
            var (userA, goalA) = await SeedAsync(fixture, token);
            var (userB, goalB) = await SeedAsync(fixture, token);
            var request = Request(fixture, 5m);
            await using var a = await fixture.OpenAsync(userA, token);
            await using var b = await fixture.OpenAsync(userB, token, administrator: true);
            var first = SavingsSqlAssert.Value(await a.Service.CreateContributionAsync(goalA.GoalId, request, token), 201);
            var second = SavingsSqlAssert.Value(await b.Service.CreateContributionAsync(goalB.GoalId, request, token), 201);
            SavingsSqlAssert.Require(first.Receipt.RequestId != second.Receipt.RequestId, "User scopes need distinct ledger rows.");
            SavingsSqlAssert.Status(await b.Service.GetAsync(goalA.GoalId, token), 404);
            SavingsSqlAssert.Status(await b.Service.GetContributionAsync(goalA.GoalId, first.Receipt.ContributionId, token), 404);
            SavingsSqlAssert.Status(await b.Service.GetContributionAsync(goalB.GoalId, first.Receipt.ContributionId, token), 404);
            var before = await FinancialFootprintAsync(fixture, [userA, userB], token);
            var foreignCreation = Request(fixture, 2m, "must not be recorded");
            // Genuine current versions are supplied so rejection cannot be explained by stale/missing preconditions.
            (string Name, Func<Task> Invoke)[] forbidden =
            [
                ("goal update", async () => SavingsSqlAssert.Status(await b.Service.UpdateAsync(goalA.GoalId,
                    new() { Name = "Foreign edit", TargetAmount = 200m }, first.Goal.Version, token), 404)),
                ("goal cancellation", async () => SavingsSqlAssert.Status(await b.Service.CancelAsync(goalA.GoalId,
                    first.Goal.Version, token), 404)),
                ("goal deletion", async () => SavingsSqlAssert.Status(await b.Service.DeleteAsync(goalA.GoalId,
                    first.Goal.Version, token), 404)),
                ("contribution creation", async () => SavingsSqlAssert.Status(await b.Service.CreateContributionAsync(goalA.GoalId,
                    foreignCreation, token), 404)),
                ("contribution correction", async () => SavingsSqlAssert.Status(await b.Service.UpdateContributionAsync(goalA.GoalId,
                    first.Receipt.ContributionId, new() { Amount = 9m, ContributedOn = fixture.Clock.Today.AddDays(-1), Note = "Foreign correction" },
                    first.CurrentContribution!.Version, token), 404)),
                ("contribution deletion", async () => SavingsSqlAssert.Status(await b.Service.DeleteContributionAsync(goalA.GoalId,
                    first.Receipt.ContributionId, first.CurrentContribution!.Version, token), 404))
            ];
            foreach (var operation in forbidden)
            {
                await operation.Invoke();
                SavingsSqlAssert.Require(before == await FinancialFootprintAsync(fixture, [userA, userB], token),
                    "Administrator foreign " + operation.Name + " changed owned records, ledger history or unrelated finance rows.");
                await using var verify = await fixture.OpenAsync(userA, token);
                SavingsSqlAssert.Require(!await verify.Db.GoalContributionRequests.AnyAsync(r => r.ClientId == foreignCreation.ClientId
                    && (r.UserId == userA || r.UserId == userB), token), "Foreign mutation recorded an unintended request ledger entry.");
            }
        });

        await Case("failure between real saves rolls back contribution, parent and ledger", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            var failure = new SavingsSqlFailureInterceptor(failBeforeLedger: true);
            var request = Request(fixture, 100m);
            await using (var s = await fixture.OpenAsync(user, token, failure))
            {
                var observed = false;
                try { await s.Service.CreateContributionAsync(goal.GoalId, request, token); }
                catch (SavingsSqlInjectedFailure) { observed = true; }
                SavingsSqlAssert.Require(observed && failure.Injected && failure.SuccessfulSaves == 1
                    && failure.PersistedContributionId.HasValue && s.Db.Database.CurrentTransaction is null
                    && !s.Db.ChangeTracker.Entries().Any(),
                    "Expected injected failure, unwound real transaction and cleared tracker.");
            }
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token),
                "Real rollback must restore the original persisted rowversion/status and leave no partial rows.");
            await using var retry = await fixture.OpenAsync(user, token);
            var accepted = SavingsSqlAssert.Value(await retry.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
            SavingsSqlAssert.Require(!accepted.Replayed && accepted.Goal.Status == "Completed",
                "Rolled-back request must remain eligible for first acceptance.");
            // Identity gaps/global database rowversion increments are explicitly NOT rollback failures.
        });

        await Case("completion, overfunding, correction, target changes and reopening persist", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 100m), token), 201);
            SavingsSqlAssert.Require(first.Goal.Status == "Completed" && first.Goal.ProgressPercentage == 100m,
                "Target funding must complete the goal.");
            var extra = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 25m), token), 201);
            Changed(first.Goal.Version, extra.Goal.Version);
            SavingsSqlAssert.Require(extra.Goal.SavedAmount == 125m && extra.Goal.RemainingAmount == -25m
                && extra.Goal.SurplusAmount == 25m && extra.Goal.ProgressPercentage == 125m,
                "Completed goals must permit overfunding and expose consistent progress.");
            var corrected = SavingsSqlAssert.Value(await s.Service.UpdateContributionAsync(goal.GoalId,
                first.Receipt.ContributionId, new() { Amount = 20m, ContributedOn = fixture.Clock.Today },
                first.CurrentContribution!.Version, token));
            SavingsSqlAssert.Require(corrected.Goal.Status == "Active" && corrected.Goal.SavedAmount == 45m, "Correction must reopen.");
            var lower = SavingsSqlAssert.Value(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Lower target", TargetAmount = 40m }, corrected.Goal.Version, token));
            SavingsSqlAssert.Require(lower.Status == "Completed", "Lower target must recalculate lifecycle.");
            var higher = SavingsSqlAssert.Value(await s.Service.UpdateAsync(goal.GoalId,
                new() { Name = "Higher target", TargetAmount = 100m }, lower.Version, token));
            SavingsSqlAssert.Require(higher.Status == "Active", "Higher target must reopen.");
            await using var verify = await fixture.OpenAsync(user, token);
            var persisted = await verify.Db.SavingsGoals.AsNoTracking().SingleAsync(g => g.GoalId == goal.GoalId, token);
            SavingsSqlAssert.Require(persisted.Status == SavingsGoalStatus.Active
                && SavingsSqlAssert.Value(await verify.Service.GetAsync(goal.GoalId, token)) == higher,
                "Lifecycle and progress must agree after reload.");
        });

        await Case("aggregate ceiling accepts maximum and rejects overflow atomically", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token, FinanceValidation.MaximumMoney);
            await using var s = await fixture.OpenAsync(user, token);
            var maximum = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId,
                Request(fixture, FinanceValidation.MaximumMoney), token), 201);
            SavingsSqlAssert.Require(maximum.Goal.SavedAmount == FinanceValidation.MaximumMoney, "Maximum must remain decimal-exact.");
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, .01m), token), 409);
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token), "Overflow rejection mutated state.");
        });

        await Case("deleting completed-goal funding reopens and preserves ledger", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var accepted = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 100m), token), 201);
            SavingsSqlAssert.Require(accepted.Goal.Status == "Completed", "Expected completed baseline.");
            var deleted = SavingsSqlAssert.Value(await s.Service.DeleteContributionAsync(goal.GoalId,
                accepted.Receipt.ContributionId, accepted.CurrentContribution!.Version, token));
            Changed(accepted.Goal.Version, deleted.Goal.Version);
            SavingsSqlAssert.Require(deleted.Goal.Status == "Active" && deleted.Goal.SavedAmount == 0m,
                "Deleting funding must reopen a completed goal.");
            await using var verify = await fixture.OpenAsync(user, token);
            var ledger = await verify.Db.GoalContributionRequests.AsNoTracking()
                .SingleAsync(r => r.RequestId == accepted.Receipt.RequestId, token);
            var stored = await verify.Db.SavingsGoals.AsNoTracking().SingleAsync(g => g.GoalId == goal.GoalId, token);
            SavingsSqlAssert.Require(ledger.DeletedAtUtc.HasValue && ledger.OriginalAmount == 100m
                && stored.Status == SavingsGoalStatus.Active
                && !await verify.Db.GoalContributions.AnyAsync(c => c.GoalId == goal.GoalId, token),
                "Deletion must persist reopening and retain the original ledger evidence.");
        });

        await Case("empty deletion succeeds; active history and notifications protect goals", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            SavingsSqlAssert.Status(await s.Service.DeleteAsync(goal.GoalId, goal.Version, token), 204);
            SavingsSqlAssert.Status(await s.Service.GetAsync(goal.GoalId, token), 404);
            var funded = SavingsSqlAssert.Value(await s.Service.CreateAsync(new() { Name = "Funded", TargetAmount = 10m }, token), 201);
            var accepted = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(funded.GoalId, Request(fixture, 1m), token), 201);
            SavingsSqlAssert.Status(await s.Service.DeleteAsync(funded.GoalId, accepted.Goal.Version, token), 409);
            foreach (var reference in new[] { "SavingsGoal", "Goal", "goal-prefix", "savings-prefix" })
            {
                var protectedGoal = SavingsSqlAssert.Value(await s.Service.CreateAsync(new() { Name = "Notification", TargetAmount = 10m }, token), 201);
                s.Db.Notifications.Add(new Notification
                {
                    UserId = user, Type = "SyntheticSavings", Message = "Synthetic protected reference",
                    ReferenceType = reference.EndsWith("prefix", StringComparison.Ordinal) ? null : reference,
                    ReferenceId = reference.EndsWith("prefix", StringComparison.Ordinal) ? null : protectedGoal.GoalId,
                    DeduplicationKey = reference == "goal-prefix" ? $"goal:{protectedGoal.GoalId}:synthetic"
                        : reference == "savings-prefix" ? $"savings-goal:{protectedGoal.GoalId}:synthetic" : Guid.NewGuid().ToString("N"),
                    CreatedAtUtc = fixture.Clock.GetUtcNow().UtcDateTime
                });
                await s.Db.SaveChangesAsync(token);
                SavingsSqlAssert.Status(await s.Service.DeleteAsync(protectedGoal.GoalId, protectedGoal.Version, token), 409);
            }
        });

        await Case("UTC date and decimal validation reject invalid writes without touching versions", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            var first = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, 1m), token), 201);
            var before = await SnapshotAsync(fixture, user, goal.GoalId, token);
            foreach (var amount in new[] { 0m, -1m, 1.001m, FinanceValidation.MaximumMoney + .01m })
                SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, Request(fixture, amount), token), 400);
            var future = Request(fixture, 1m); future.ContributedOn = fixture.Clock.Today.AddDays(1);
            SavingsSqlAssert.Status(await s.Service.CreateContributionAsync(goal.GoalId, future, token), 400);
            SavingsSqlAssert.Status(await s.Service.UpdateContributionAsync(goal.GoalId, first.Receipt.ContributionId,
                new() { Amount = 1m, ContributedOn = fixture.Clock.Today.AddDays(1) }, first.CurrentContribution!.Version, token), 400);
            SavingsSqlAssert.Require(before == await SnapshotAsync(fixture, user, goal.GoalId, token), "Validation failures must leave tokens/history unchanged.");
        });

        await Case("bounded 125-row history paginates and totals without expense or budget writes", async token =>
        {
            var (user, goal) = await SeedAsync(fixture, token, 1000m);
            await using var s = await fixture.OpenAsync(user, token);
            var ratesBefore = await s.Db.ExchangeRates.CountAsync(token);
            var seeded = new List<(int Id, decimal Amount, DateOnly Date, string Currency)>();
            for (var index = 0; index < 125; index++)
            {
                // Varied amounts make per-item mapping checks meaningful while preserving the original total of 125.
                var amount = index == 124 ? 1m : index % 2 == 0 ? .50m : 1.50m;
                var request = Request(fixture, amount); request.ContributedOn = fixture.Clock.Today.AddDays(-(index % 5));
                var accepted = SavingsSqlAssert.Value(await s.Service.CreateContributionAsync(goal.GoalId, request, token), 201);
                seeded.Add((accepted.Receipt.ContributionId, request.Amount!.Value, request.ContributedOn!.Value, "PHP"));
            }
            await using var verify = await fixture.OpenAsync(user, token);
            var seen = new List<int>();
            // Independent oracle: seeded inputs and generated IDs, not another read of the response's backing rows.
            var expected = seeded.OrderByDescending(c => c.Date).ThenByDescending(c => c.Id).ToArray();
            for (var page = 1; page <= 3; page++)
            {
                var result = SavingsSqlAssert.Value(await verify.Service.ListContributionsAsync(goal.GoalId,
                    new() { Page = page, PageSize = 50 }, token));
                SavingsSqlAssert.Require(result.TotalCount == 125 && result.Page == page && result.PageSize == 50
                    && result.Items.Count == (page == 3 ? 25 : 50), "History pagination metadata/count is incorrect.");
                var actualPage = result.Items.Select(c => (c.ContributionId, c.Amount, c.ContributedOn, c.BaseCurrency));
                SavingsSqlAssert.Require(actualPage.SequenceEqual(expected.Skip((page - 1) * 50).Take(50))
                    && result.Items.All(c => c.GoalId == goal.GoalId),
                    "Every history item must match its seeded ID, amount, date, currency and exact page boundary/order.");
                seen.AddRange(result.Items.Select(c => c.ContributionId));
            }
            var filtered = SavingsSqlAssert.Value(await verify.Service.ListContributionsAsync(goal.GoalId,
                new() { FromDate = fixture.Clock.Today, ToDate = fixture.Clock.Today, PageSize = 100 }, token));
            var loaded = SavingsSqlAssert.Value(await verify.Service.GetAsync(goal.GoalId, token));
            SavingsSqlAssert.Require(filtered.Items.Select(c => (c.ContributionId, c.Amount, c.ContributedOn, c.BaseCurrency))
                .SequenceEqual(expected.Where(c => c.Date == fixture.Clock.Today))
                && filtered.Items.All(c => c.GoalId == goal.GoalId), "Filtered history must match all seeded fields in order.");
            SavingsSqlAssert.Require(seen.SequenceEqual(expected.Select(c => c.Id)) && seen.Distinct().Count() == seeded.Count
                && filtered.TotalCount == 25 && loaded.SavedAmount == 125m && loaded.RemainingAmount == 875m
                && loaded.ProgressPercentage == 12.5m && loaded.BaseCurrency == "PHP",
                "History order, filters, totals and base currency must remain consistent.");
            SavingsSqlAssert.Require(!await verify.Db.Transactions.AnyAsync(t => t.UserId == user, token)
                && !await verify.Db.Budgets.AnyAsync(b => b.UserId == user, token)
                && await verify.Db.ExchangeRates.CountAsync(token) == ratesBefore, "Savings must not create expenses, budgets or FX cache rows.");
            // This bounded correctness case is not a performance benchmark or concurrency test.
        });
    }

    private static CreateGoalContributionRequest Request(SavingsSqlFixture f, decimal amount, string? note = null) =>
        new() { ClientId = Guid.NewGuid(), Amount = amount, ContributedOn = f.Clock.Today, Note = note };

    private static async Task<(int User, SavingsGoalResponse Goal)> SeedAsync(SavingsSqlFixture f,
        CancellationToken ct, decimal target = 100m)
    {
        var user = await f.NewUserAsync(ct);
        await using var s = await f.OpenAsync(user, ct);
        return (user, SavingsSqlAssert.Value(await s.Service.CreateAsync(new()
            { Name = "Synthetic SQL goal", TargetAmount = target }, ct), 201));
    }

    private static void Changed(string before, string after)
    {
        SavingsSqlAssert.NativeVersion(before); SavingsSqlAssert.NativeVersion(after);
        SavingsSqlAssert.Require(before != after, "Native rowversion must advance after mutation.");
    }

    private static async Task<string> SnapshotAsync(SavingsSqlFixture f, int user, int goal, CancellationToken ct)
    {
        await using var s = await f.OpenAsync(user, ct);
        var parent = await s.Db.SavingsGoals.AsNoTracking().SingleAsync(g => g.GoalId == goal && g.UserId == user, ct);
        var contributions = await s.Db.GoalContributions.AsNoTracking().Where(c => c.GoalId == goal)
            .OrderBy(c => c.ContributionId).Select(c => new { c.ContributionId, c.GoalId, c.Amount, c.ContributedOn, c.Note, c.RowVersion }).ToListAsync(ct);
        var requests = await s.Db.GoalContributionRequests.AsNoTracking().Where(r => r.GoalId == goal)
            .OrderBy(r => r.RequestId).ToListAsync(ct);
        return JsonSerializer.Serialize(new { Parent = parent, Contributions = contributions, Requests = requests });
    }

    private static async Task<string> FinancialFootprintAsync(SavingsSqlFixture f, int[] users, CancellationToken ct)
    {
        await using var s = await f.OpenAsync(users[0], ct);
        var goals = await s.Db.SavingsGoals.AsNoTracking().Where(g => users.Contains(g.UserId))
            .OrderBy(g => g.GoalId).ToListAsync(ct);
        var contributions = await s.Db.GoalContributions.AsNoTracking().Where(c => users.Contains(c.Goal.UserId))
            .OrderBy(c => c.ContributionId).Select(c => new { c.ContributionId, c.GoalId, c.Amount, c.ContributedOn, c.Note, c.RowVersion }).ToListAsync(ct);
        var ledger = await s.Db.GoalContributionRequests.AsNoTracking().Where(r => users.Contains(r.UserId))
            .OrderBy(r => r.RequestId).ToListAsync(ct);
        // These newly created synthetic users have no unrelated financial rows: unchanged zero counts
        // detect accidental creation. Full savings snapshots also detect changes/deletions and version writes.
        var transactions = await s.Db.Transactions.CountAsync(t => users.Contains(t.UserId), ct);
        var budgets = await s.Db.Budgets.CountAsync(b => users.Contains(b.UserId), ct);
        var bills = await s.Db.RecurringBills.CountAsync(b => users.Contains(b.UserId), ct);
        var summaries = await s.Db.MonthlySummaries.CountAsync(m => users.Contains(m.UserId), ct);
        var notifications = await s.Db.Notifications.CountAsync(n => users.Contains(n.UserId), ct);
        var rates = await s.Db.ExchangeRates.CountAsync(ct);
        SavingsSqlAssert.Require(transactions == 0 && budgets == 0 && bills == 0 && summaries == 0 && notifications == 0,
            "Synthetic savings users unexpectedly have unrelated financial records.");
        return JsonSerializer.Serialize(new { Goals = goals, Contributions = contributions, Ledger = ledger,
            Transactions = transactions, Budgets = budgets, Bills = bills, Summaries = summaries, Notifications = notifications, Rates = rates });
    }
}
