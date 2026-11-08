using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class SavingsGoalService(ApplicationDbContext db, ICurrentUser current,
    IFinancialWriteScope writes, TimeProvider clock) : FinanceServiceBase(db, current, writes), ISavingsGoalService
{
    public Task<FinanceResult<SavingsGoalResponse>> CreateAsync(CreateSavingsGoalRequest request, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        ValidateGoal(request);
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var currency = await CurrencyAsync(userId, true, token);
            var goal = new SavingsGoal { UserId = userId, Name = request.Name.Trim(),
                TargetAmount = request.TargetAmount!.Value, TargetDate = request.TargetDate, Status = SavingsGoalStatus.Active };
            Db.SavingsGoals.Add(goal);
            await Db.SaveChangesAsync(token);
            return FinanceResult<SavingsGoalResponse>.Success(GoalResponse(goal, 0m, currency), 201);
        }, ct);
    });

    public Task<FinanceResult<PagedResponse<SavingsGoalResponse>>> ListAsync(SavingsGoalQuery query, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        if (!FinanceValidation.ValidPage(query.Page, query.PageSize)
            || (query.Status is not null && query.Status is not ("Active" or "Completed" or "Cancelled")))
            throw new FinanceException(400, "Invalid savings goal filters or pagination.");
        var rows = Db.SavingsGoals.AsNoTracking().Where(g => g.UserId == userId);
        if (query.Status is not null)
        {
            var status = Enum.Parse<SavingsGoalStatus>(query.Status);
            rows = rows.Where(g => g.Status == status);
        }
        var count = await rows.LongCountAsync(ct);
        var goals = await rows.OrderByDescending(g => g.GoalId).Skip((query.Page - 1) * query.PageSize)
            .Take(query.PageSize).ToListAsync(ct);
        var totals = await TotalsAsync(userId, goals.Select(g => g.GoalId).ToArray(), ct);
        var currency = await CurrencyAsync(userId, false, ct);
        return FinanceResult<PagedResponse<SavingsGoalResponse>>.Success(new(
            goals.Select(g => GoalResponse(g, totals.GetValueOrDefault(g.GoalId), currency)).ToArray(),
            query.Page, query.PageSize, count));
    });

    public Task<FinanceResult<SavingsGoalResponse>> GetAsync(int id, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        var goal = await Db.SavingsGoals.AsNoTracking().SingleOrDefaultAsync(g => g.GoalId == id && g.UserId == userId, ct)
            ?? throw GoalNotFound();
        return FinanceResult<SavingsGoalResponse>.Success(await GoalResponseAsync(goal, userId, ct));
    });

    public Task<FinanceResult<SavingsGoalResponse>> UpdateAsync(int id, UpdateSavingsGoalRequest request,
        string? expectedVersion, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var goal = await OwnedGoalAsync(id, userId, token);
            CheckVersion(expectedVersion, goal.RowVersion);
            RequireMutable(goal);
            ValidateGoal(request);
            var total = await TotalAsync(userId, id, token);
            var currency = await CurrencyAsync(userId, true, token);
            goal.Name = request.Name.Trim();
            goal.TargetAmount = request.TargetAmount!.Value;
            goal.TargetDate = request.TargetDate;
            RecalculateAndTouch(goal, total);
            await Db.SaveChangesAsync(token);
            return FinanceResult<SavingsGoalResponse>.Success(GoalResponse(goal, total, currency));
        }, ct);
    });

    public Task<FinanceResult<SavingsGoalResponse>> CancelAsync(int id, string? expectedVersion, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var goal = await OwnedGoalAsync(id, userId, token);
            CheckVersion(expectedVersion, goal.RowVersion);
            var total = await TotalAsync(userId, id, token);
            var currency = await CurrencyAsync(userId, true, token);
            if (goal.Status != SavingsGoalStatus.Cancelled)
            {
                goal.Status = SavingsGoalStatus.Cancelled;
                await Db.SaveChangesAsync(token);
            }
            return FinanceResult<SavingsGoalResponse>.Success(GoalResponse(goal, total, currency));
        }, ct);
    });

    public Task<FinanceResult<bool>> DeleteAsync(int id, string? expectedVersion, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var goal = await OwnedGoalAsync(id, userId, token);
            CheckVersion(expectedVersion, goal.RowVersion);
            if (await Db.GoalContributions.AnyAsync(c => c.GoalId == id, token)
                || await Db.GoalContributionRequests.AnyAsync(r => r.GoalId == id, token)
                || await HasGoalNotificationsAsync(id, token))
                throw new FinanceException(409, "A goal with protected financial history cannot be deleted.");
            Db.SavingsGoals.Remove(goal);
            await Db.SaveChangesAsync(token);
            return FinanceResult<bool>.Success(true, 204);
        }, ct);
    });

    public Task<FinanceResult<ContributionCreateResponse>> CreateContributionAsync(int goalId,
        CreateGoalContributionRequest request, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        ValidateContribution(request);
        if (request.ClientId is null || request.ClientId == Guid.Empty)
            throw new FinanceException(400, "A nonempty client-generated ClientId is required.");
        var clientId = request.ClientId.Value;
        var now = clock.GetUtcNow().UtcDateTime;
        var today = DateOnly.FromDateTime(now);
        try
        {
            return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
            {
                var goal = await OwnedGoalAsync(goalId, userId, token);
                var accepted = await ReceiptByClientAsync(userId, clientId, token);
                if (accepted is not null)
                    return await ReplayAsync(goal, accepted, request, userId, token);
                RequireMutable(goal);
                ValidateContributionDate(request.ContributedOn!.Value, today);
                var total = AddToTotal(await TotalAsync(userId, goalId, token), request.Amount!.Value);
                var currency = await CurrencyAsync(userId, true, token);
                var contribution = new GoalContribution { GoalId = goalId, Amount = request.Amount.Value,
                    ContributedOn = request.ContributedOn.Value, Note = GoalContributionIdempotency.CanonicalizeNote(request.Note) };
                Db.GoalContributions.Add(contribution);
                RecalculateAndTouch(goal, total);
                await Db.SaveChangesAsync(token);
                var receipt = new GoalContributionRequest { UserId = userId, ClientId = clientId, GoalId = goalId,
                    ContributionId = contribution.ContributionId, OriginalAmount = contribution.Amount,
                    OriginalContributedOn = contribution.ContributedOn, OriginalNote = contribution.Note,
                    CanonicalVersion = GoalContributionIdempotency.CanonicalVersion, RecordedAtUtc = now };
                Db.GoalContributionRequests.Add(receipt);
                await Db.SaveChangesAsync(token);
                return FinanceResult<ContributionCreateResponse>.Success(new(ReceiptResponse(receipt, currency), false, false,
                    ContributionResponse(contribution, currency), GoalResponse(goal, total, currency)), 201);
            }, ct);
        }
        catch (DbUpdateException ex) when (IsUniqueViolation(ex))
        {
            // The failed scope has rolled back. Recover only an actual owner-scoped ledger winner.
            Db.ChangeTracker.Clear();
            return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
            {
                var goal = await OwnedGoalAsync(goalId, userId, token);
                var accepted = await ReceiptByClientAsync(userId, clientId, token);
                if (accepted is null)
                    throw new FinanceException(409, "The contribution conflicts with existing financial records.");
                return await ReplayAsync(goal, accepted, request, userId, token);
            }, ct);
        }
    });

    public Task<FinanceResult<PagedResponse<GoalContributionResponse>>> ListContributionsAsync(int goalId,
        GoalContributionQuery query, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        await RequireOwnedGoalReadAsync(goalId, userId, ct);
        if (!FinanceValidation.ValidPage(query.Page, query.PageSize)
            || (query.FromDate.HasValue && query.ToDate.HasValue && query.FromDate > query.ToDate))
            throw new FinanceException(400, "Invalid contribution filters or pagination.");
        var rows = OwnedContributions(userId, goalId).AsNoTracking();
        if (query.FromDate.HasValue) rows = rows.Where(c => c.ContributedOn >= query.FromDate.Value);
        if (query.ToDate.HasValue) rows = rows.Where(c => c.ContributedOn <= query.ToDate.Value);
        var count = await rows.LongCountAsync(ct);
        var contributions = await rows.OrderByDescending(c => c.ContributedOn).ThenByDescending(c => c.ContributionId)
            .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize).ToListAsync(ct);
        var currency = await CurrencyAsync(userId, false, ct);
        return FinanceResult<PagedResponse<GoalContributionResponse>>.Success(new(
            contributions.Select(c => ContributionResponse(c, currency)).ToArray(), query.Page, query.PageSize, count));
    });

    public Task<FinanceResult<GoalContributionResponse>> GetContributionAsync(int goalId, int contributionId,
        CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        var contribution = await OwnedContributions(userId, goalId).AsNoTracking()
            .SingleOrDefaultAsync(c => c.ContributionId == contributionId, ct) ?? throw ContributionNotFound();
        return FinanceResult<GoalContributionResponse>.Success(
            ContributionResponse(contribution, await CurrencyAsync(userId, false, ct)));
    });

    public Task<FinanceResult<GoalContributionMutationResponse>> UpdateContributionAsync(int goalId, int contributionId,
        UpdateGoalContributionRequest request, string? expectedVersion, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        var today = DateOnly.FromDateTime(clock.GetUtcNow().UtcDateTime);
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var goal = await OwnedGoalAsync(goalId, userId, token);
            var contribution = await OwnedContributionAsync(goalId, contributionId, userId, token);
            CheckVersion(expectedVersion, contribution.RowVersion);
            RequireMutable(goal);
            ValidateContribution(request);
            ValidateContributionDate(request.ContributedOn!.Value, today);
            await RequireLedgerAsync(goalId, contributionId, userId, token);
            var total = await TotalAsync(userId, goalId, token);
            total = AddToTotal(total - contribution.Amount, request.Amount!.Value);
            var currency = await CurrencyAsync(userId, true, token);
            contribution.Amount = request.Amount.Value;
            contribution.ContributedOn = request.ContributedOn.Value;
            contribution.Note = GoalContributionIdempotency.CanonicalizeNote(request.Note);
            // Even an identical correction receives a new version; accepted original receipt stays immutable.
            Db.Entry(contribution).Property(c => c.Amount).IsModified = true;
            RecalculateAndTouch(goal, total);
            await Db.SaveChangesAsync(token);
            return FinanceResult<GoalContributionMutationResponse>.Success(new(
                ContributionResponse(contribution, currency), GoalResponse(goal, total, currency)));
        }, ct);
    });

    public Task<FinanceResult<ContributionDeletionResponse>> DeleteContributionAsync(int goalId, int contributionId,
        string? expectedVersion, CancellationToken ct) => SavingsGuard(async () =>
    {
        var userId = UserId;
        return await Writes.ExecuteAsync([FinanceValidation.UserLock(userId)], async token =>
        {
            var goal = await OwnedGoalAsync(goalId, userId, token);
            var contribution = await OwnedContributionAsync(goalId, contributionId, userId, token);
            CheckVersion(expectedVersion, contribution.RowVersion);
            RequireMutable(goal);
            var receipt = await RequireLedgerAsync(goalId, contributionId, userId, token);
            var now = clock.GetUtcNow().UtcDateTime;
            if (now < receipt.RecordedAtUtc)
                throw new FinanceException(409, "The deletion time precedes the accepted request. Retry later.");
            var total = await TotalAsync(userId, goalId, token) - contribution.Amount;
            var currency = await CurrencyAsync(userId, true, token);
            receipt.DeletedAtUtc = now;
            Db.GoalContributions.Remove(contribution);
            RecalculateAndTouch(goal, total);
            await Db.SaveChangesAsync(token);
            return FinanceResult<ContributionDeletionResponse>.Success(new(contributionId, true, GoalResponse(goal, total, currency)));
        }, ct);
    });

    private async Task<FinanceResult<ContributionCreateResponse>> ReplayAsync(SavingsGoal goal, GoalContributionRequest receipt,
        CreateGoalContributionRequest request, int userId, CancellationToken ct)
    {
        if (!GoalContributionIdempotency.Matches(receipt, goal.GoalId, request.Amount!.Value,
            request.ContributedOn!.Value, request.Note))
            throw new FinanceException(409, "ClientId has already been accepted with a different contribution payload.");
        var current = await OwnedContributions(userId, goal.GoalId).AsNoTracking()
            .SingleOrDefaultAsync(c => c.ContributionId == receipt.ContributionId, ct);
        if ((receipt.DeletedAtUtc.HasValue && current is not null) || (!receipt.DeletedAtUtc.HasValue && current is null))
            throw new FinanceException(409, "The retained request and contribution history require reconciliation.");
        var currency = await CurrencyAsync(userId, false, ct);
        var total = await TotalAsync(userId, goal.GoalId, ct);
        return FinanceResult<ContributionCreateResponse>.Success(new(ReceiptResponse(receipt, currency), true,
            receipt.DeletedAtUtc.HasValue, current is null ? null : ContributionResponse(current, currency), GoalResponse(goal, total, currency)));
    }

    private Task<GoalContributionRequest?> ReceiptByClientAsync(int userId, Guid clientId, CancellationToken ct) =>
        Db.GoalContributionRequests.AsNoTracking().SingleOrDefaultAsync(r => r.UserId == userId && r.ClientId == clientId, ct);

    private async Task<GoalContributionRequest> RequireLedgerAsync(int goalId, int contributionId, int userId, CancellationToken ct)
    {
        var receipt = await Db.GoalContributionRequests.SingleOrDefaultAsync(r => r.UserId == userId
            && r.GoalId == goalId && r.ContributionId == contributionId, ct);
        if (receipt is null)
            throw new FinanceException(409, "Contribution history must be enrolled before it can be changed.");
        await Db.Entry(receipt).ReloadAsync(ct);
        if (Db.Entry(receipt).State == EntityState.Detached || receipt.UserId != userId || receipt.GoalId != goalId
            || receipt.ContributionId != contributionId || receipt.DeletedAtUtc.HasValue)
            throw new FinanceException(409, "Contribution history changed. Reload and retry.");
        return receipt;
    }

    private async Task<SavingsGoal> OwnedGoalAsync(int id, int userId, CancellationToken ct)
    {
        var goal = await Db.SavingsGoals.SingleOrDefaultAsync(g => g.GoalId == id && g.UserId == userId, ct)
            ?? throw GoalNotFound();
        await Db.Entry(goal).ReloadAsync(ct);
        if (Db.Entry(goal).State == EntityState.Detached || goal.UserId != userId) throw GoalNotFound();
        return goal;
    }

    private async Task RequireOwnedGoalReadAsync(int id, int userId, CancellationToken ct)
    {
        if (!await Db.SavingsGoals.AsNoTracking().AnyAsync(g => g.GoalId == id && g.UserId == userId, ct)) throw GoalNotFound();
    }

    private IQueryable<GoalContribution> OwnedContributions(int userId, int goalId) =>
        Db.GoalContributions.Where(c => c.GoalId == goalId && c.Goal.UserId == userId);

    private async Task<GoalContribution> OwnedContributionAsync(int goalId, int id, int userId, CancellationToken ct)
    {
        var contribution = await OwnedContributions(userId, goalId).SingleOrDefaultAsync(c => c.ContributionId == id, ct)
            ?? throw ContributionNotFound();
        await Db.Entry(contribution).ReloadAsync(ct);
        if (Db.Entry(contribution).State == EntityState.Detached || contribution.GoalId != goalId) throw ContributionNotFound();
        return contribution;
    }

    private async Task<Dictionary<int, decimal>> TotalsAsync(int userId, int[] goalIds, CancellationToken ct)
    {
        var totals = new Dictionary<int, decimal>();
        if (goalIds.Length == 0) return totals;
        // Stream bounded additions instead of allowing SQL SUM(decimal(38,2)) to overflow a CLR decimal.
        var amounts = Db.GoalContributions.AsNoTracking().Where(c => goalIds.Contains(c.GoalId) && c.Goal.UserId == userId)
            .Select(c => new { c.GoalId, c.Amount });
        await foreach (var item in amounts.AsAsyncEnumerable().WithCancellation(ct))
            totals[item.GoalId] = AddToTotal(totals.GetValueOrDefault(item.GoalId), item.Amount);
        return totals;
    }

    private async Task<decimal> TotalAsync(int userId, int goalId, CancellationToken ct) =>
        (await TotalsAsync(userId, [goalId], ct)).GetValueOrDefault(goalId);

    private static decimal AddToTotal(decimal total, decimal amount)
    {
        if (total < 0 || total > FinanceValidation.MaximumMoney || amount <= 0
            || amount > FinanceValidation.MaximumMoney - total)
            throw new FinanceException(409, "Total savings exceeds the supported monetary range or requires reconciliation.");
        return total + amount;
    }

    private void RecalculateAndTouch(SavingsGoal goal, decimal total)
    {
        goal.Status = Progress(goal, total).Status;
        // Force a real SQL UPDATE even if Status stays the same: SQL Server advances the parent rowversion.
        Db.Entry(goal).Property(g => g.Status).IsModified = true;
    }

    private static SavingsGoalProgress Progress(SavingsGoal goal, decimal total)
    {
        try { return SavingsGoalProgressCalculator.Calculate(total, goal.TargetAmount, goal.Status); }
        catch (ArgumentOutOfRangeException)
        { throw new FinanceException(409, "Savings values require reconciliation before this operation can complete."); }
    }

    private async Task<SavingsGoalResponse> GoalResponseAsync(SavingsGoal goal, int userId, CancellationToken ct) =>
        GoalResponse(goal, await TotalAsync(userId, goal.GoalId, ct), await CurrencyAsync(userId, false, ct));

    private static SavingsGoalResponse GoalResponse(SavingsGoal goal, decimal total, string currency)
    {
        var progress = Progress(goal, total);
        return new(goal.GoalId, goal.Name, goal.TargetAmount, goal.TargetDate, progress.Status.ToString(),
            progress.TotalSavedAmount, progress.RemainingAmount, progress.SurplusAmount, progress.ProgressPercentage,
            currency, EncodeVersion(goal.RowVersion));
    }

    private static GoalContributionResponse ContributionResponse(GoalContribution contribution, string currency) =>
        new(contribution.ContributionId, contribution.GoalId, contribution.Amount, contribution.ContributedOn,
            contribution.Note, currency, EncodeVersion(contribution.RowVersion));

    private static GoalContributionReceipt ReceiptResponse(GoalContributionRequest receipt, string currency) =>
        new(receipt.RequestId, receipt.ClientId!.Value, receipt.GoalId, receipt.ContributionId, receipt.OriginalAmount,
            receipt.OriginalContributedOn, receipt.OriginalNote, receipt.CanonicalVersion, receipt.RecordedAtUtc,
            receipt.DeletedAtUtc, currency);

    private async Task<string> CurrencyAsync(int userId, bool locked, CancellationToken ct) =>
        await FinanceData.BaseCurrencyAsync(Db, userId, locked, ct) ?? throw new FinanceException(401, "Account unavailable.");

    private Task<bool> HasGoalNotificationsAsync(int goalId, CancellationToken ct)
    {
        var prefix = $"goal:{goalId}:";
        var savingsPrefix = $"savings-goal:{goalId}:";
        // Polymorphic history has no FK. Future writers must use this same user write lock.
        return Db.Notifications.AnyAsync(n => (n.ReferenceId == goalId && n.ReferenceType != null
            && (n.ReferenceType.ToUpper() == "SAVINGSGOAL" || n.ReferenceType.ToUpper() == "GOAL"))
            || n.DeduplicationKey.StartsWith(prefix) || n.DeduplicationKey.StartsWith(savingsPrefix), ct);
    }

    private static string EncodeVersion(byte[] version)
    {
        if (version.Length != 8) throw new FinanceException(503, "A financial record version is unavailable.");
        return Convert.ToBase64String(version);
    }

    private static void CheckVersion(string? expected, byte[] actual)
    {
        if (string.IsNullOrWhiteSpace(expected)) throw new FinanceException(428, "A resource version precondition is required.");
        if (expected.Length != 12) throw new FinanceException(400, "Invalid resource version.");
        byte[] decoded;
        try { decoded = Convert.FromBase64String(expected); }
        catch (FormatException) { throw new FinanceException(400, "Invalid resource version."); }
        if (decoded.Length != 8 || Convert.ToBase64String(decoded) != expected)
            throw new FinanceException(400, "Invalid resource version.");
        if (!decoded.AsSpan().SequenceEqual(actual)) throw new FinanceException(412, "The resource changed. Reload and retry.");
    }

    private static void RequireMutable(SavingsGoal goal)
    {
        if (goal.Status == SavingsGoalStatus.Cancelled) throw new FinanceException(409, "Cancelled goals are financially immutable.");
    }

    private static void ValidateGoal(UpdateSavingsGoalRequest request)
    {
        if (!FinanceValidation.Valid(request) || string.IsNullOrWhiteSpace(request.Name))
            throw new FinanceException(400, "Invalid savings goal data.");
        FinanceValidation.ValidateMoney(request.TargetAmount!.Value);
    }

    private static void ValidateContribution(UpdateGoalContributionRequest request)
    {
        if (!FinanceValidation.Valid(request)) throw new FinanceException(400, "Invalid contribution data.");
        FinanceValidation.ValidateMoney(request.Amount!.Value);
    }

    private static void ValidateContributionDate(DateOnly date, DateOnly today)
    {
        if (date > today) throw new FinanceException(400, "Contribution dates cannot be later than today in UTC.");
    }

    private static FinanceException GoalNotFound() => new(404, "Savings goal not found.");
    private static FinanceException ContributionNotFound() => new(404, "Contribution not found.");
    private static bool IsUniqueViolation(DbUpdateException exception) => exception.InnerException is SqlException sql
        && sql.Errors.Cast<SqlError>().Any(e => e.Number is 2601 or 2627);

    private Task<FinanceResult<T>> SavingsGuard<T>(Func<Task<FinanceResult<T>>> operation) => Guard(async () =>
    {
        try { return await operation(); }
        catch (DbUpdateConcurrencyException)
        {
            Db.ChangeTracker.Clear();
            return FinanceResult<T>.Failure(412, "The resource changed. Reload and retry.");
        }
        catch
        {
            // No failed tracked mutation may be saved by a later operation in this request scope.
            Db.ChangeTracker.Clear();
            throw;
        }
    });
}
