using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Finance.Contracts;

namespace PersonalFinance.SavingsSqlTests;

// Dormant real-SQL tests. Direct invalid writes intentionally bypass service validation to
// exercise SQL constraints. All rows are synthetic; no legacy enrollment or cleanup is done.
internal static class SavingsSqlSchemaTests
{
    public static async Task RunAsync(SavingsSqlFixture fixture,
        Func<string, Func<Task>, Task> check, CancellationToken ct)
    {
        Task Case(string name, Func<CancellationToken, Task> test) =>
            SavingsSqlFixture.CheckAsync("SQL schema: " + name, test, check, ct);

        await Case("native rowversion columns and ledger index metadata", async token =>
        {
            var user = await fixture.NewUserAsync(token);
            await using var s = await fixture.OpenAsync(user, token);
            var versions = await s.Db.Database.SqlQueryRaw<int>("""
                SELECT COUNT(*) AS [Value] FROM sys.columns
                WHERE [object_id] IN (OBJECT_ID(N'dbo.SavingsGoals'), OBJECT_ID(N'dbo.GoalContributions'))
                  AND [name] = N'RowVersion' AND [system_type_id] = 189 AND [is_nullable] = 0
                """).SingleAsync(token);
            SavingsSqlAssert.Require(versions == 2, "Both columns must be native nonnullable SQL rowversions.");
            var indexes = await s.Db.Database.SqlQueryRaw<IndexColumnMetadata>("""
                SELECT sc.[name] AS [SchemaName], t.[name] AS [TableName], i.[name] AS [IndexName],
                       i.[is_unique] AS [IsUnique], i.[is_disabled] AS [IsDisabled],
                       i.[has_filter] AS [HasFilter], i.[filter_definition] AS [FilterDefinition],
                       CONVERT(int, ic.[key_ordinal]) AS [KeyOrdinal], c.[name] AS [ColumnName],
                       ic.[is_included_column] AS [IsIncludedColumn], ic.[is_descending_key] AS [IsDescendingKey]
                FROM sys.indexes AS i
                JOIN sys.tables AS t ON t.[object_id] = i.[object_id]
                JOIN sys.schemas AS sc ON sc.[schema_id] = t.[schema_id]
                JOIN sys.index_columns AS ic ON ic.[object_id] = i.[object_id] AND ic.[index_id] = i.[index_id]
                JOIN sys.columns AS c ON c.[object_id] = ic.[object_id] AND c.[column_id] = ic.[column_id]
                WHERE t.[name] = N'GoalContributionRequests' AND i.[is_primary_key] = 0
                """).ToListAsync(token);
            // These exact three secondary indexes/ordered keys come from the reviewed migration.
            (string Name, bool Unique, string[] Columns, bool Filtered)[] expectedIndexes =
            [
                ("IX_GoalContributionRequests_ContributionId", true, ["ContributionId"], false),
                ("IX_GoalContributionRequests_UserId_ClientId", true, ["UserId", "ClientId"], true),
                ("IX_GoalContributionRequests_GoalId", false, ["GoalId"], false)
            ];
            SavingsSqlAssert.Require(indexes.Select(i => i.IndexName).Distinct(StringComparer.Ordinal).Count() == 3
                && indexes.Count == expectedIndexes.Sum(i => i.Columns.Length), "Unexpected ledger index set/columns.");
            foreach (var expectedIndex in expectedIndexes)
            {
                var columns = indexes.Where(i => i.IndexName == expectedIndex.Name).OrderBy(i => i.KeyOrdinal).ToArray();
                SavingsSqlAssert.Require(columns.Length == expectedIndex.Columns.Length
                    && columns.Select(i => i.ColumnName).SequenceEqual(expectedIndex.Columns, StringComparer.Ordinal)
                    && columns.Select(i => i.KeyOrdinal).SequenceEqual(Enumerable.Range(1, expectedIndex.Columns.Length))
                    && columns.All(i => i.SchemaName == "dbo" && i.TableName == "GoalContributionRequests"
                        && i.IsUnique == expectedIndex.Unique && !i.IsDisabled && !i.IsIncludedColumn && !i.IsDescendingKey
                        && i.HasFilter == expectedIndex.Filtered
                        && (expectedIndex.Filtered
                            ? i.FilterDefinition is "[ClientId] IS NOT NULL" or "([ClientId] IS NOT NULL)"
                            : i.FilterDefinition is null)),
                    "Ledger index table, ordered keys, uniqueness or exact filter differs from the migration.");
            }
            var checks = await s.Db.Database.SqlQueryRaw<int>("""
                SELECT COUNT(*) AS [Value] FROM sys.check_constraints
                WHERE [parent_object_id] = OBJECT_ID(N'dbo.GoalContributionRequests')
                  AND [is_disabled] = 0 AND [is_not_trusted] = 0
                """).SingleAsync(token);
            SavingsSqlAssert.Require(checks == 5, "Expected five enabled trusted ledger CHECK constraints.");
            var foreignKeys = await s.Db.Database.SqlQueryRaw<ForeignKeyColumnMetadata>("""
                SELECT fk.[name] AS [ForeignKeyName], ps.[name] AS [ParentSchema], pt.[name] AS [ParentTable],
                       pc.[name] AS [ParentColumn], rs.[name] AS [ReferencedSchema], rt.[name] AS [ReferencedTable],
                       rc.[name] AS [ReferencedColumn], fc.[constraint_column_id] AS [ColumnOrdinal],
                       CONVERT(int, fk.[delete_referential_action]) AS [DeleteAction],
                       fk.[is_disabled] AS [IsDisabled], fk.[is_not_trusted] AS [IsNotTrusted]
                FROM sys.foreign_keys AS fk
                JOIN sys.foreign_key_columns AS fc ON fc.[constraint_object_id] = fk.[object_id]
                JOIN sys.tables AS pt ON pt.[object_id] = fk.[parent_object_id]
                JOIN sys.schemas AS ps ON ps.[schema_id] = pt.[schema_id]
                JOIN sys.columns AS pc ON pc.[object_id] = pt.[object_id] AND pc.[column_id] = fc.[parent_column_id]
                JOIN sys.tables AS rt ON rt.[object_id] = fk.[referenced_object_id]
                JOIN sys.schemas AS rs ON rs.[schema_id] = rt.[schema_id]
                JOIN sys.columns AS rc ON rc.[object_id] = rt.[object_id] AND rc.[column_id] = fc.[referenced_column_id]
                WHERE pt.[name] = N'GoalContributionRequests'
                """).ToListAsync(token);
            (string Name, string Source, string Table, string Referenced)[] expectedForeignKeys =
            [
                ("FK_GoalContributionRequests_AspNetUsers_UserId", "UserId", "AspNetUsers", "Id"),
                ("FK_GoalContributionRequests_SavingsGoals_GoalId", "GoalId", "SavingsGoals", "GoalId")
            ];
            SavingsSqlAssert.Require(foreignKeys.Count == 2, "Expected exactly two single-column ledger foreign keys.");
            foreach (var expectedKey in expectedForeignKeys)
            {
                var columns = foreignKeys.Where(k => k.ForeignKeyName == expectedKey.Name).OrderBy(k => k.ColumnOrdinal).ToArray();
                SavingsSqlAssert.Require(columns.Length == 1 && columns[0].ColumnOrdinal == 1
                    && columns[0].ParentSchema == "dbo" && columns[0].ParentTable == "GoalContributionRequests"
                    && columns[0].ParentColumn == expectedKey.Source && columns[0].ReferencedSchema == "dbo"
                    && columns[0].ReferencedTable == expectedKey.Table && columns[0].ReferencedColumn == expectedKey.Referenced
                    && columns[0].DeleteAction == 0 && !columns[0].IsDisabled && !columns[0].IsNotTrusted,
                    "Ledger FK name, ordered column mapping, table association or NO ACTION behavior differs from the migration.");
            }
        });

        (string Name, Action<GoalContributionRequest> Mutate)[] invalid =
        [
            ("CK_GoalContributionRequests_Amount", r => r.OriginalAmount = 0m),
            ("CK_GoalContributionRequests_ClientId", r => r.ClientId = Guid.Empty),
            ("CK_GoalContributionRequests_ContributionId", r => r.ContributionId = 0),
            ("CK_GoalContributionRequests_CanonicalVersion", r => r.CanonicalVersion = 2),
            ("CK_GoalContributionRequests_DeletedAtUtc", r => r.DeletedAtUtc = r.RecordedAtUtc.AddTicks(-1)),
            ("FK_GoalContributionRequests_AspNetUsers_UserId", r => r.UserId = -1),
            ("FK_GoalContributionRequests_SavingsGoals_GoalId", r => r.GoalId = -1)
        ];
        foreach (var item in invalid)
            await Case(item.Name + " rejects isolated invalid write", async token =>
            {
                var (user, goal) = await SeedGoalAsync(fixture, token);
                await using var s = await fixture.OpenAsync(user, token);
                var row = Ledger(fixture, user, goal, int.MaxValue);
                item.Mutate(row);
                s.Db.GoalContributionRequests.Add(row);
                await ExpectConstraintAsync(() => s.Db.SaveChangesAsync(token), 547, item.Name);
                await using var verify = await fixture.OpenAsync(user, token);
                SavingsSqlAssert.Require(!await verify.Db.GoalContributionRequests.AnyAsync(r => r.GoalId == goal, token),
                    "Rejected ledger write must leave no row.");
            });

        foreach (var key in new[] { "ClientId", "ContributionId" })
            await Case("unique " + key + " rejects duplicate", async token =>
            {
                var (user, goal) = await SeedGoalAsync(fixture, token);
                await using var create = await fixture.OpenAsync(user, token);
                var accepted = SavingsSqlAssert.Value(await create.Service.CreateContributionAsync(goal,
                    new() { ClientId = Guid.NewGuid(), Amount = 1m, ContributedOn = fixture.Clock.Today }, token), 201);
                if (key == "ContributionId")
                    SavingsSqlAssert.Value(await create.Service.DeleteContributionAsync(goal, accepted.Receipt.ContributionId,
                        accepted.CurrentContribution!.Version, token));
                await using var s = await fixture.OpenAsync(user, token);
                var row = Ledger(fixture, user, goal,
                    key == "ContributionId" ? accepted.Receipt.ContributionId : int.MaxValue);
                if (key == "ClientId") row.ClientId = accepted.Receipt.ClientId;
                s.Db.GoalContributionRequests.Add(row);
                var index = key == "ClientId" ? "IX_GoalContributionRequests_UserId_ClientId"
                    : "IX_GoalContributionRequests_ContributionId";
                await ExpectConstraintAsync(() => s.Db.SaveChangesAsync(token), null, index);
                await using var verify = await fixture.OpenAsync(user, token);
                SavingsSqlAssert.Require(await verify.Db.GoalContributionRequests.CountAsync(r => r.GoalId == goal, token) == 1,
                    "Duplicate ledger write must preserve exactly the original receipt.");
            });

        await Case("contribution goal foreign key rejects orphan", async token =>
        {
            var user = await fixture.NewUserAsync(token);
            await using var s = await fixture.OpenAsync(user, token);
            s.Db.GoalContributions.Add(new() { GoalId = -1, Amount = 1m, ContributedOn = fixture.Clock.Today });
            await ExpectConstraintAsync(() => s.Db.SaveChangesAsync(token), 547,
                "FK_GoalContributions_SavingsGoals_GoalId");
        });

        (string Name, Action<SavingsGoal> Mutate)[] invalidGoals =
        [
            ("CK_SavingsGoals_Target", g => g.TargetAmount = 0m),
            ("CK_SavingsGoals_Name", g => g.Name = "   "),
            ("CK_SavingsGoals_Status", g => g.Status = (PersonalFinance.Domain.Enums.SavingsGoalStatus)999)
        ];
        foreach (var item in invalidGoals)
            await Case(item.Name + " rejects invalid goal", async token =>
            {
                var user = await fixture.NewUserAsync(token);
                await using var s = await fixture.OpenAsync(user, token);
                var row = new SavingsGoal { UserId = user, Name = "Synthetic", TargetAmount = 10m };
                item.Mutate(row);
                s.Db.SavingsGoals.Add(row);
                await ExpectConstraintAsync(() => s.Db.SaveChangesAsync(token), 547, item.Name);
            });

        await Case("contribution amount CHECK rejects zero", async token =>
        {
            var (user, goal) = await SeedGoalAsync(fixture, token);
            await using var s = await fixture.OpenAsync(user, token);
            s.Db.GoalContributions.Add(new() { GoalId = goal, Amount = 0m, ContributedOn = fixture.Clock.Today });
            await ExpectConstraintAsync(() => s.Db.SaveChangesAsync(token), 547, "CK_GoalContributions_Amount");
        });

        await Case("ledger FK protects previously funded goal after contribution deletion", async token =>
        {
            var (user, goal) = await SeedGoalAsync(fixture, token);
            await using var service = await fixture.OpenAsync(user, token);
            var accepted = SavingsSqlAssert.Value(await service.Service.CreateContributionAsync(goal,
                new() { ClientId = Guid.NewGuid(), Amount = 1m, ContributedOn = fixture.Clock.Today }, token), 201);
            SavingsSqlAssert.Value(await service.Service.DeleteContributionAsync(goal, accepted.Receipt.ContributionId,
                accepted.CurrentContribution!.Version, token));
            await using var direct = await fixture.OpenAsync(user, token);
            var row = await direct.Db.SavingsGoals.SingleAsync(g => g.GoalId == goal, token);
            direct.Db.SavingsGoals.Remove(row);
            await ExpectConstraintAsync(() => direct.Db.SaveChangesAsync(token), 547,
                "FK_GoalContributionRequests_SavingsGoals_GoalId");
        });
    }

    private sealed class IndexColumnMetadata
    {
        public string SchemaName { get; set; } = string.Empty;
        public string TableName { get; set; } = string.Empty;
        public string IndexName { get; set; } = string.Empty;
        public bool IsUnique { get; set; }
        public bool IsDisabled { get; set; }
        public bool HasFilter { get; set; }
        public string? FilterDefinition { get; set; }
        public int KeyOrdinal { get; set; }
        public string ColumnName { get; set; } = string.Empty;
        public bool IsIncludedColumn { get; set; }
        public bool IsDescendingKey { get; set; }
    }

    private sealed class ForeignKeyColumnMetadata
    {
        public string ForeignKeyName { get; set; } = string.Empty;
        public string ParentSchema { get; set; } = string.Empty;
        public string ParentTable { get; set; } = string.Empty;
        public string ParentColumn { get; set; } = string.Empty;
        public string ReferencedSchema { get; set; } = string.Empty;
        public string ReferencedTable { get; set; } = string.Empty;
        public string ReferencedColumn { get; set; } = string.Empty;
        public int ColumnOrdinal { get; set; }
        public int DeleteAction { get; set; }
        public bool IsDisabled { get; set; }
        public bool IsNotTrusted { get; set; }
    }

    private static GoalContributionRequest Ledger(SavingsSqlFixture f, int user, int goal, int contribution) => new()
    {
        UserId = user, GoalId = goal, ContributionId = contribution, ClientId = Guid.NewGuid(),
        OriginalAmount = 1m, OriginalContributedOn = f.Clock.Today, CanonicalVersion = 1,
        RecordedAtUtc = f.Clock.GetUtcNow().UtcDateTime
    };

    private static async Task<(int User, int Goal)> SeedGoalAsync(SavingsSqlFixture f, CancellationToken ct)
    {
        var user = await f.NewUserAsync(ct);
        await using var s = await f.OpenAsync(user, ct);
        var goal = SavingsSqlAssert.Value(await s.Service.CreateAsync(new() { Name = "Schema fixture", TargetAmount = 100m }, ct), 201);
        return (user, goal.GoalId);
    }

    private static async Task ExpectConstraintAsync(Func<Task<int>> save, int? number, string constraint)
    {
        try { await save(); }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException sql)
        {
            SavingsSqlAssert.Require(sql.Errors.Cast<SqlError>().Any(e =>
                (number.HasValue ? e.Number == number.Value : e.Number is 2601 or 2627)
                && e.Message.Contains(constraint, StringComparison.Ordinal)),
                "Expected the specific native SQL constraint/index violation.");
            return;
        }
        throw new InvalidOperationException("Expected SQL to reject the invalid write.");
    }
}
