using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Migrations;
using Microsoft.EntityFrameworkCore.Migrations.Operations;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using PersonalFinance.Api.Persistence;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.Domain.Policies;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;
using FinanceTransaction = PersonalFinance.Domain.Entities.Transaction;

if (args.Contains("--bill-migration-sql-only", StringComparer.Ordinal))
    return BillMigrationSqlValidation.Run();

if (args.Contains("--bill-model-only", StringComparer.Ordinal))
    return BillModelValidation.Run();

var defaultConnectionGuard = new RejectConnectionOpening();
using var db = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>()
    .UseSqlServer().AddInterceptors(defaultConnectionGuard).Options);
var model = db.GetService<IDesignTimeModel>().Model;
var passed = 0;
var failed = 0;

void Check(string name, Action test)
{
    try { test(); passed++; Console.WriteLine($"PASS {name}"); }
    catch (Exception ex) { failed++; Console.Error.WriteLine($"FAIL {name}: {ex.Message}"); }
}
void Require(bool condition, string message = "Assertion failed")
{
    if (!condition) throw new InvalidOperationException(message);
}
void Throws<T>(Action action) where T : Exception
{
    try { action(); } catch (T) { return; }
    throw new InvalidOperationException($"Expected {typeof(T).Name}");
}
IEntityType Entity<T>() => model.FindEntityType(typeof(T))!;
void Unique<T>(params string[] columns) =>
    Require(Entity<T>().GetIndexes().Any(i => i.IsUnique && i.Properties.Select(p => p.Name).SequenceEqual(columns)));

Check("Domain remains independent of EF Core, Identity, and Infrastructure", () =>
{
    Require(typeof(Category).Assembly.GetReferencedAssemblies().All(a =>
        !a.Name!.Contains("EntityFrameworkCore", StringComparison.Ordinal)
        && !a.Name.Contains("Identity", StringComparison.Ordinal)
        && !a.Name.Contains("Infrastructure", StringComparison.Ordinal)));
});
Check("Offline schema context uses SQL Server with no configured connection", () =>
{
    Require(db.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer");
    Require(string.IsNullOrEmpty(db.Database.GetConnectionString()));
});

// Synthetic, credential-free configuration for inspection only; never connection targets.
const string SyntheticConnection = "Server=configuration-only.invalid;Database=SyntheticConfiguration;Integrated Security=True;Encrypt=True;Application Name=FinanceSyntheticConfigurationTest;";
const string EnvironmentConnection = SyntheticConnection + "Application Name=FinanceEnvironmentTest;";
const string CommandLineConnection = SyntheticConnection + "Application Name=FinanceCommandLineTest;";

void WithConnectionOverride(string connection, Action action)
{
    const string name = "ConnectionStrings__DefaultConnection";
    var previous = Environment.GetEnvironmentVariable(name);
    try
    {
        Environment.SetEnvironmentVariable(name, connection);
        action();
    }
    finally { Environment.SetEnvironmentVariable(name, previous); }
}

void RequireSyntheticConnection(ApplicationDbContext context, string expected)
{
    Require(context.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer");
    Require(context.Database.GetConnectionString() == expected, "Synthetic configuration was not preserved.");
    Require(context.Database.GetDbConnection().State == System.Data.ConnectionState.Closed);
}

void RequireBaseSettings(string directory)
{
    var configuration = new ConfigurationBuilder().SetBasePath(directory)
        .AddJsonFile("appsettings.json", optional: false).Build();
    Require(configuration["Jwt:Issuer"] == "PersonalFinance.Api");
    Require(configuration["Jwt:Audience"] == "PersonalFinance.Clients");
}

Check("Development factory preserves explicit synthetic configuration without opening it", () =>
{
    using var context = new ApplicationDbContextFactory().CreateDbContext([
        "--environment", "Development", "--ConnectionStrings:DefaultConnection", SyntheticConnection]);
    RequireSyntheticConnection(context, SyntheticConnection);
});

Check("Production factory accepts explicit empty configuration for offline inspection", () =>
{
    using var context = new ApplicationDbContextFactory().CreateDbContext([
        "--environment", "Production", "--ConnectionStrings:DefaultConnection", ""]);
    Require(context.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer");
    Require(string.IsNullOrEmpty(context.Database.GetConnectionString()));
});

Check("Factory honors synthetic environment connection overrides", () =>
    WithConnectionOverride(EnvironmentConnection, () =>
    {
        using var context = new ApplicationDbContextFactory().CreateDbContext(["--environment", "Development"]);
        RequireSyntheticConnection(context, EnvironmentConnection);
    }));

Check("Factory command-line connection overrides take precedence over environment configuration", () =>
    WithConnectionOverride(EnvironmentConnection, () =>
    {
        using var context = new ApplicationDbContextFactory().CreateDbContext([
            "--environment", "Development", "--ConnectionStrings:DefaultConnection", CommandLineConnection]);
        RequireSyntheticConnection(context, CommandLineConnection);
    }));

Check("API directory contains base-setting markers and factory preserves an explicit synthetic connection", () =>
{
    var previousDirectory = Directory.GetCurrentDirectory();
    try
    {
        if (Directory.Exists(Path.Combine(previousDirectory, "PersonalFinance.Api")))
            Directory.SetCurrentDirectory(Path.Combine(previousDirectory, "PersonalFinance.Api"));
        Require(File.Exists("PersonalFinance.Api.csproj"), "Run from the solution or API project directory.");
        RequireBaseSettings(Directory.GetCurrentDirectory());
        using var context = new ApplicationDbContextFactory().CreateDbContext([
            "--environment", "Development", "--ConnectionStrings:DefaultConnection", SyntheticConnection]);
        RequireSyntheticConnection(context, SyntheticConnection);
    }
    finally { Directory.SetCurrentDirectory(previousDirectory); }
});

Check("Runtime registration supports empty in-memory configuration without a connection", () =>
{
    var configuration = new ConfigurationBuilder().AddInMemoryCollection().Build();
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddInfrastructure(configuration);
    using var provider = services.BuildServiceProvider();
    using var scope = provider.CreateScope();
    var context = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
    Require(context.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer");
    Require(string.IsNullOrEmpty(context.Database.GetConnectionString()));
});

Check("Runtime registration preserves synthetic in-memory configuration without opening it", () =>
{
    var configuration = new ConfigurationBuilder().AddInMemoryCollection(
        new Dictionary<string, string?> { ["ConnectionStrings:DefaultConnection"] = SyntheticConnection }).Build();
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddInfrastructure(configuration);
    using var provider = services.BuildServiceProvider();
    using var scope = provider.CreateScope();
    var context = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
    RequireSyntheticConnection(context, SyntheticConnection);
});

Check("Output directory contains base-setting markers and factory preserves an explicit synthetic connection from a temporary working directory", () =>
{
    var previousDirectory = Directory.GetCurrentDirectory();
    try
    {
        Directory.SetCurrentDirectory(Path.GetTempPath());
        RequireBaseSettings(AppContext.BaseDirectory);
        using var context = new ApplicationDbContextFactory().CreateDbContext([
            "--environment", "Development", "--ConnectionStrings:DefaultConnection", SyntheticConnection]);
        RequireSyntheticConnection(context, SyntheticConnection);
    }
    finally { Directory.SetCurrentDirectory(previousDirectory); }
});

Check("All 19 current-model application and Identity tables exist; no separate Users table", () =>
{
    var expected = new[] { "AspNetUsers", "AspNetRoles", "AspNetUserClaims", "AspNetRoleClaims",
        "AspNetUserRoles", "AspNetUserLogins", "AspNetUserTokens", "Categories", "Transactions",
        "Budgets", "RecurringBills", "SavingsGoals", "GoalContributions", "ExchangeRates",
        "Notifications", "MonthlySummaries", "RefreshTokens", "GoalContributionRequests", "BillPaymentReceipts" };
    Require(expected.Order().SequenceEqual(model.GetEntityTypes().Select(x => x.GetTableName()!).Order()));
});
Check("Identity user and role keys and all ownership FKs are integer", () =>
{
    Require(Entity<ApplicationUser>().FindPrimaryKey()!.Properties.Single().ClrType == typeof(int));
    Require(Entity<IdentityRole<int>>().FindPrimaryKey()!.Properties.Single().ClrType == typeof(int));
    Require(model.GetEntityTypes().SelectMany(e => e.GetForeignKeys())
        .Where(f => f.PrincipalEntityType.ClrType == typeof(ApplicationUser))
        .All(f => f.Properties.Single().ClrType is var t && (t == typeof(int) || t == typeof(int?))));
});
Check("All 19 current-model application foreign keys prohibit cascading financial deletion", () =>
{
    var foreignKeys = model.GetEntityTypes().Where(e => !e.GetTableName()!.StartsWith("AspNet", StringComparison.Ordinal))
        .SelectMany(e => e.GetForeignKeys()).ToArray();
    Require(foreignKeys.Length == 19);
    Require(foreignKeys.All(f => f.DeleteBehavior == DeleteBehavior.NoAction));
});
Check("Shared categories have optional ownership; contributions inherit goal ownership", () =>
{
    Require(Entity<Category>().FindProperty("UserId")!.IsNullable);
    Require(Entity<GoalContribution>().FindProperty("UserId") is null);
    Require(Entity<GoalContribution>().GetForeignKeys().Single().PrincipalEntityType.ClrType == typeof(SavingsGoal));
});
Check("Required transaction client uniqueness", () => Unique<FinanceTransaction>("UserId", "ClientId"));
Check("Filtered bill-period uniqueness", () =>
{
    Unique<FinanceTransaction>("BillId", "BillDueDate");
    Require(Entity<FinanceTransaction>().GetIndexes().Single(i => i.Properties.Select(p => p.Name)
        .SequenceEqual(new[] { "BillId", "BillDueDate" })).GetFilter()
        == "[BillId] IS NOT NULL AND [BillDueDate] IS NOT NULL");
});
Check("Required budget scope uniqueness", () => Unique<Budget>("UserId", "CategoryId", "PeriodMonth"));
Check("Required exchange-rate uniqueness", () => Unique<ExchangeRate>("BaseCurrency", "QuoteCurrency", "RateDate"));
Check("Required summary uniqueness", () => Unique<MonthlySummary>("UserId", "SummaryMonth"));
Check("Required token-hash uniqueness", () => Unique<RefreshToken>("TokenHash"));
Check("Required notification deduplication uniqueness", () => Unique<Notification>("UserId", "DeduplicationKey"));
Check("Normalized email is nonnullable, case insensitive, and uniquely indexed without a filter", () =>
{
    Unique<ApplicationUser>("NormalizedEmail");
    var p = Entity<ApplicationUser>().FindProperty("NormalizedEmail")!;
    Require(!p.IsNullable && p.GetCollation() == "Latin1_General_100_CI_AS");
    Require(Entity<ApplicationUser>().GetIndexes().Single(i => i.Properties.SingleOrDefault()?.Name == "NormalizedEmail").GetFilter() is null);
});
Check("Token family and reporting indexes are present", () =>
{
    Require(Entity<RefreshToken>().GetIndexes().Any(i => i.Properties.Select(p => p.Name).SequenceEqual(new[] { "UserId", "TokenFamilyId" })));
    Require(Entity<FinanceTransaction>().GetIndexes().Any(i => i.Properties.Select(p => p.Name).SequenceEqual(new[] { "UserId", "CategoryId", "TransactionDate" })));
});
Check("Money is decimal(18,2); rates are decimal(18,8)", () =>
{
    foreach (var p in model.GetEntityTypes().SelectMany(e => e.GetProperties()).Where(p => p.ClrType == typeof(decimal)))
        Require(p.GetPrecision() == 18 && p.GetScale() == (p.Name is "Rate" or "FxRate" ? 8 : 2), p.Name);
});
Check("DateOnly fields map to SQL date; UTC fields map to datetime2", () =>
{
    foreach (var p in model.GetEntityTypes().SelectMany(e => e.GetProperties()))
    {
        if (p.ClrType == typeof(DateOnly) || p.ClrType == typeof(DateOnly?))
            Require(p.GetColumnType() == "date", p.Name);
        if (p.Name.EndsWith("Utc", StringComparison.Ordinal))
            Require(p.GetColumnType() == "datetime2", p.Name);
    }
});
Check("UTC timestamps reject ambiguous values and restore UTC kind when read", () =>
{
    var converter = Entity<ApplicationUser>().FindProperty("CreatedAtUtc")!.GetValueConverter()!;
    var utc = new DateTime(2026, 10, 31, 0, 0, 0, DateTimeKind.Utc);
    Require((DateTime)converter.ConvertToProvider(utc)! == utc);
    Require(((DateTime)converter.ConvertFromProvider(DateTime.SpecifyKind(utc, DateTimeKind.Unspecified))!).Kind == DateTimeKind.Utc);
    Throws<ArgumentException>(() => converter.ConvertToProvider(DateTime.SpecifyKind(utc, DateTimeKind.Unspecified)));
});
Check("Bill rowversion is store generated and concurrency controlled", () =>
{
    var p = Entity<RecurringBill>().FindProperty("RowVersion")!;
    Require(p.IsConcurrencyToken && p.ValueGenerated == ValueGenerated.OnAddOrUpdate && p.GetColumnType() == "rowversion");
});
Check("Critical checks cover amounts, types, months, billing, and notification references", () =>
{
    var checks = model.GetEntityTypes().SelectMany(e => e.GetCheckConstraints()).Select(c => c.Name).ToHashSet();
    foreach (var name in new[] { "CK_Transactions_Amount", "CK_Transactions_FxRate", "CK_Transactions_FxRateDate",
        "CK_Transactions_BillPeriod", "CK_Transactions_Type", "CK_Budgets_Threshold", "CK_Budgets_PeriodMonth",
        "CK_RecurringBills_Frequency", "CK_RecurringBills_IntendedDay", "CK_SavingsGoals_Status",
        "CK_GoalContributions_Amount", "CK_MonthlySummaries_Month", "CK_Notifications_Reference", "CK_RefreshTokens_TokenHash" })
        Require(checks.Contains(name), name);
});
Check("Budget current status is derived; summaries allow negative net cash flow", () =>
{
    Require(Entity<Budget>().FindProperty("Status") is null);
    Require(!Entity<MonthlySummary>().GetCheckConstraints().Any(c => c.Sql.Contains("[NetCashFlow] >= 0", StringComparison.Ordinal)));
});
Check("Identity services require unique email and resolve the EF user store", () =>
{
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddInfrastructure(new ConfigurationBuilder().Build());
    using var provider = services.BuildServiceProvider();
    Require(provider.GetRequiredService<IOptions<IdentityOptions>>().Value.User.RequireUniqueEmail);
    using var scope = provider.CreateScope();
    Require(scope.ServiceProvider.GetRequiredService<IUserStore<ApplicationUser>>() is not null);
    Require(provider.GetRequiredService<IOptions<CurrencyPolicyOptions>>().Value.MaximumRateAgeDays == 7);
});
Check("FX maximum age can be configured", () =>
{
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddInfrastructure(new ConfigurationBuilder().AddInMemoryCollection(
        new Dictionary<string, string?> { ["CurrencyPolicy:MaximumRateAgeDays"] = "3" }).Build());
    using var provider = services.BuildServiceProvider();
    Require(provider.GetRequiredService<IOptions<CurrencyPolicyOptions>>().Value.MaximumRateAgeDays == 3);
});
Check("Only approved role names are seeded; no default users", () =>
{
    Require(Entity<IdentityRole<int>>().GetSeedData().Select(x => (string)x["Name"]!).Order()
        .SequenceEqual(new[] { "Administrator", "User" }));
    Require(!Entity<ApplicationUser>().GetSeedData().Any());
});
Check("SHA-256 token hash matches a known vector; no plaintext property", () =>
{
    Require(RefreshTokenHash.Compute("abc") == "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD");
    Require(Entity<RefreshToken>().GetProperties().All(p => p.Name != "Token"));
    Require(Entity<RefreshToken>().FindProperty("TokenHash")!.GetColumnType() == "char(64)");
});
Check("Monthly recurrence restores day 31 after February", () =>
{
    var feb = BillRecurrence.NextDueDate(new DateOnly(2027, 1, 31), BillFrequency.Monthly, 31);
    Require(feb == new DateOnly(2027, 2, 28));
    Require(BillRecurrence.NextDueDate(feb, BillFrequency.Monthly, 31) == new DateOnly(2027, 3, 31));
});
Check("Quarterly recurrence restores original day", () =>
{
    var april = BillRecurrence.NextDueDate(new DateOnly(2027, 1, 31), BillFrequency.Quarterly, 31);
    Require(april == new DateOnly(2027, 4, 30));
    Require(BillRecurrence.NextDueDate(april, BillFrequency.Quarterly, 31) == new DateOnly(2027, 7, 31));
});
Check("Yearly recurrence restores February 29 in a leap year", () =>
{
    Require(BillRecurrence.NextDueDate(new DateOnly(2027, 2, 28), BillFrequency.Yearly, 29) == new DateOnly(2028, 2, 29));
});
Check("Weekly recurrence advances seven days", () =>
    Require(BillRecurrence.NextDueDate(new DateOnly(2026, 10, 31), BillFrequency.Weekly, null) == new DateOnly(2026, 11, 7)));
Check("Invalid recurrence parameters are rejected", () =>
{
    Throws<ArgumentOutOfRangeException>(() => BillRecurrence.NextDueDate(new DateOnly(2026, 10, 31), BillFrequency.Monthly, null));
    Throws<ArgumentException>(() => BillRecurrence.NextDueDate(new DateOnly(2026, 10, 31), BillFrequency.Weekly, 31));
});
Check("Same-currency conversion stores rate one and transaction date", () =>
{
    var result = CurrencyConversion.CreateSnapshot(10m, "PHP", "PHP", new DateOnly(2026, 10, 31), 99m, new DateOnly(2026, 10, 30));
    Require(result == (1m, new DateOnly(2026, 10, 31), 10m));
});
Check("Conversion rounds midpoint away from zero", () =>
    Require(CurrencyConversion.CreateSnapshot(1m, "USD", "PHP", new DateOnly(2026, 10, 31), 1.005m, new DateOnly(2026, 10, 31)).AmountBase == 1.01m));
Check("Seven-day-old rates are accepted, eight-day and future rates rejected", () =>
{
    var date = new DateOnly(2026, 10, 31);
    Require(CurrencyConversion.CreateSnapshot(1m, "USD", "PHP", date, 50m, date.AddDays(-7)).AmountBase == 50m);
    Throws<ArgumentException>(() => CurrencyConversion.CreateSnapshot(1m, "USD", "PHP", date, 50m, date.AddDays(-8)));
    Throws<ArgumentException>(() => CurrencyConversion.CreateSnapshot(1m, "USD", "PHP", date, 50m, date.AddDays(1)));
});
Check("Notification retry keys remain stable and later budget transitions stay distinct", () =>
{
    var id = Guid.Parse("f92378be-8384-4a59-8074-d4ae5a2695f5");
    var first = NotificationKeys.BudgetTransition(1, BudgetAlertStatus.Warning, id);
    Require(first == NotificationKeys.BudgetTransition(1, BudgetAlertStatus.Warning, id));
    Require(first != NotificationKeys.BudgetTransition(1, BudgetAlertStatus.Warning, Guid.NewGuid()));
    Require(first.Length <= 200);
});
const string initialMigrationId = "20261031023901_InitialFinanceSchema";
const string categoryMigrationId = "20261031115500_AddCategoryNameUniqueness";
const string savingsMigrationId = "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions";
const string billMigrationId = "20261107054048_AddBillPaymentReceiptsAndGuards";

Migration CompiledMigration(string id)
{
    var assembly = db.GetService<IMigrationsAssembly>();
    return assembly.CreateMigration(assembly.Migrations.Single(m => m.Key == id).Value, db.Database.ProviderName!);
}
string NormalizeSql(string sql) => sql.Replace("\r\n", "\n", StringComparison.Ordinal).Trim();

Check("Exactly four approved compiled migrations exist; applied database state is not inspected", () =>
{
    Require(db.Database.GetMigrations().SequenceEqual(new[] {
        initialMigrationId, categoryMigrationId, savingsMigrationId, billMigrationId }), "Compiled migration IDs/order.");
});
Check("Current model and snapshot align with zero relational differences", () =>
{
    var snapshot = db.GetService<IMigrationsAssembly>().ModelSnapshot
        ?? throw new InvalidOperationException("Current snapshot is missing.");
    var snapshotModel = db.GetService<IModelRuntimeInitializer>().Initialize(snapshot.Model, designTime: true);
    foreach (var inspected in new[] { model, snapshotModel })
    {
        Require(inspected.FindEntityType(typeof(BillPaymentReceipt).FullName!) is not null, "Receipt missing.");
        Require(inspected.FindEntityType(typeof(RecurringBill).FullName!)!.FindProperty("AnchorDate") is not null, "Anchor missing.");
    }
    var differences = db.GetService<IMigrationsModelDiffer>().GetDifferences(
        snapshotModel.GetRelationalModel(), model.GetRelationalModel());
    foreach (var difference in differences)
        Console.WriteLine("UNEXPECTED DRIFT " + difference.GetType().Name);
    Require(differences.Count == 0, "Unexpected model/snapshot differences: " + differences.Count);
});
Check("No pending model changes; database-applied migration state is not inspected", () =>
{
    var pending = db.Database.HasPendingModelChanges();
    Console.WriteLine($"INFO HasPendingModelChanges() = {pending}; database-applied migrations were not queried.");
    Require(!pending, "Current model differs from the compiled snapshot.");
});
Check("Historical initial migration creates 17 tables and restrictive application FKs", () =>
{
    var assembly = db.GetService<IMigrationsAssembly>();
    var migration = assembly.CreateMigration(assembly.Migrations.First(m => m.Key.EndsWith("_InitialFinanceSchema", StringComparison.Ordinal)).Value, db.Database.ProviderName!);
    var tables = migration.UpOperations.OfType<CreateTableOperation>().ToArray();
    Require(tables.Length == 17);
    Require(tables.Where(t => !t.Name.StartsWith("AspNet", StringComparison.Ordinal))
        .SelectMany(t => t.ForeignKeys).All(f => f.OnDelete == ReferentialAction.NoAction));
});
Check("Generated SQL includes unique indexes, rowversion, role seeds, checks, and safely batched guards", () =>
{
    var sql = db.GetService<IMigrator>().GenerateScript("0", initialMigrationId);
    foreach (var text in new[] { "CREATE UNIQUE INDEX [EmailIndex]", "[RowVersion] rowversion",
        "CK_Transactions_BillPeriod", "CK_Budgets_Threshold", "N'Administrator'", "N'User'",
        "EXEC(N'CREATE TRIGGER [TR_Transactions_ProtectBillPayments]",
        "EXEC(N'CREATE TRIGGER [TR_AspNetUsers_LockBaseCurrency]",
        "WHERE [BillId] IS NOT NULL AND [BillDueDate] IS NOT NULL" })
        Require(sql.Contains(text, StringComparison.Ordinal), text);
    Require(!sql.Contains("DROP TABLE", StringComparison.Ordinal));
    Require(!sql.Contains("CREATE DATABASE", StringComparison.Ordinal));
});

Check("Category normalized shadow column is bounded Unicode, stored, and explicitly collated", () =>
{
    var property = model.FindEntityType(typeof(Category))!.FindProperty(CategoryNameNormalization.PropertyName)!;
    Require(property.IsShadowProperty() && property.GetColumnType()=="nvarchar(50)" && property.GetMaxLength()==50);
    Require(property.IsUnicode()==true && property.GetIsStored()==true);
    Require(property.GetComputedColumnSql()==CategoryNameNormalization.ComputedSql);
    Require(property.GetCollation() is null); // Expression owns collation; avoid duplicate COLLATE in generated SQL.
});
Check("Category index protects null owners and inactive records without a filter", () =>
{
    var entity=model.FindEntityType(typeof(Category))!;
    var index=entity.GetIndexes().Single(i=>i.GetDatabaseName()==CategoryNameNormalization.UniqueIndexName);
    Require(index.IsUnique && index.GetFilter() is null && index.IsClustered()==false);
    Require(index.Properties.Select(p=>p.Name).SequenceEqual(new[]{"UserId","Type","NormalizedName"}));
    Require(entity.GetIndexes().Any(i=>i.GetDatabaseName()=="IX_Categories_UserId" && !i.IsUnique));
    Require(entity.GetCheckConstraints().Any(c=>c.Name=="CK_Categories_NormalizedName" && c.Sql.Contains("IS NOT NULL") && c.Sql.Contains("DATALENGTH")));
});
Check("Historical category-uniqueness migration has exactly three additive operations", () =>
{
    var assembly=db.GetService<IMigrationsAssembly>();
    var migration=assembly.CreateMigration(assembly.Migrations.Single(m=>m.Key.EndsWith("_AddCategoryNameUniqueness",StringComparison.Ordinal)).Value,db.Database.ProviderName!);
    Require(migration.UpOperations.Count==3);
    var column=migration.UpOperations.OfType<AddColumnOperation>().Single();
    Require(column.Table=="Categories" && column.Name=="NormalizedName" && column.IsStored==true);
    Require(column.ComputedColumnSql==CategoryNameNormalization.ComputedSql);
    var index=migration.UpOperations.OfType<CreateIndexOperation>().Single();
    Require(index.Table=="Categories" && index.Name==CategoryNameNormalization.UniqueIndexName && index.IsUnique && index.Filter is null);
    Require(migration.UpOperations.OfType<AddCheckConstraintOperation>().Single().Name=="CK_Categories_NormalizedName");
    var sql=db.GetService<IMigrator>().GenerateScript(initialMigrationId, categoryMigrationId);
    Require(sql.Contains("PERSISTED") && sql.Contains("CREATE UNIQUE NONCLUSTERED INDEX"));
    Require(!sql.Contains("Latin1_General_100_CI_AS_KS_WS_SC COLLATE"));
    Require(!sql.Contains("WHERE [UserId] IS NOT NULL") && !sql.Contains("WHERE [NormalizedName] IS NOT NULL"));
});

void Columns(CreateTableOperation table, (string Name, Type Clr, string Sql, bool Nullable, int? Precision, int? Scale, int? Length, string? Identity)[] expected)
{
    Require(table.Schema is null && table.Columns.Count == expected.Length, table.Name + " column count/schema.");
    Require(table.Columns.Select(c => c.Name).SequenceEqual(expected.Select(c => c.Name)), table.Name + " column order/names.");
    foreach (var item in expected)
    {
        var column = table.Columns.Single(c => c.Name == item.Name);
        Require(column.Table == table.Name && column.Schema is null
            && column.ClrType == item.Clr && column.ColumnType == item.Sql
            && column.IsNullable == item.Nullable && column.Precision == item.Precision
            && column.Scale == item.Scale && column.MaxLength == item.Length
            && Equals(column["SqlServer:Identity"], item.Identity)
            && column.DefaultValue is null && column.DefaultValueSql is null
            && column.ComputedColumnSql is null, table.Name + "." + item.Name + " column definition.");
    }
}
void TableChecks(CreateTableOperation table, (string Name, string Sql)[] expected)
{
    Require(table.CheckConstraints.Count == expected.Length, table.Name + " check count.");
    foreach (var item in expected)
        Require(table.CheckConstraints.Any(c => c.Name == item.Name && c.Table == table.Name
            && c.Schema is null && c.Sql == item.Sql), table.Name + "." + item.Name);
}
void TableKeys(CreateTableOperation table, string key, string[] columns,
    (string Name, string Column, string Principal, string PrincipalColumn)[] expected)
{
    Require(table.PrimaryKey is not null && table.PrimaryKey.Name == key
        && table.PrimaryKey.Table == table.Name && table.PrimaryKey.Columns.SequenceEqual(columns)
        && table.UniqueConstraints.Count == 0, table.Name + " primary/unique keys.");
    Require(table.ForeignKeys.Count == expected.Length, table.Name + " foreign-key count.");
    foreach (var item in expected)
        Require(table.ForeignKeys.Any(f => f.Name == item.Name && f.Table == table.Name
            && f.Columns.SequenceEqual(new[] { item.Column }) && f.PrincipalTable == item.Principal
            && f.PrincipalColumns!.SequenceEqual(new[] { item.PrincipalColumn })
            && f.Schema is null && f.PrincipalSchema is null
            && f.OnDelete == ReferentialAction.NoAction && f.OnUpdate == ReferentialAction.NoAction),
            table.Name + "." + item.Name);
}
void Indexes(IReadOnlyList<MigrationOperation> operations, string table,
    (string Name, string[] Columns, bool Unique, string? Filter)[] expected)
{
    var indexes = operations.OfType<CreateIndexOperation>().ToArray();
    Require(indexes.Length == expected.Length, table + " index count.");
    for (var i = 0; i < expected.Length; i++)
    {
        var actual = indexes[i];
        var item = expected[i];
        Require(actual.Name == item.Name && actual.Table == table && actual.Schema is null
            && actual.Columns.SequenceEqual(item.Columns) && actual.IsUnique == item.Unique
            && actual.Filter == item.Filter && actual.IsDescending is null, table + "." + item.Name);
    }
}

Check("Historical savings migration has exactly six ordered operations and rowversions", () =>
{
    var ops = CompiledMigration(savingsMigrationId).UpOperations;
    Require(ops.Select(o => o.GetType()).SequenceEqual(new[] {
        typeof(AddColumnOperation), typeof(AddColumnOperation), typeof(CreateTableOperation),
        typeof(CreateIndexOperation), typeof(CreateIndexOperation), typeof(CreateIndexOperation) }), "Savings operation sequence.");
    for (var i = 0; i < 2; i++)
    {
        var column = (AddColumnOperation)ops[i];
        Require(column.Table == (i == 0 ? "SavingsGoals" : "GoalContributions")
            && column.Name == "RowVersion" && column.ClrType == typeof(byte[])
            && column.ColumnType == "rowversion" && column.IsRowVersion && !column.IsNullable
            && column.DefaultValue is byte[] { Length: 0 } && column.DefaultValueSql is null
            && column.ComputedColumnSql is null && column.Schema is null, "Historical rowversion " + i);
    }
});
Check("Historical savings ledger table, five checks and two restrictive foreign keys", () =>
{
    var table = (CreateTableOperation)CompiledMigration(savingsMigrationId).UpOperations[2];
    Require(table.Name == "GoalContributionRequests", "Savings table.");
    Columns(table, new (string, Type, string, bool, int?, int?, int?, string?)[]
    {
        ("RequestId", typeof(long), "bigint", false, null, null, null, "1, 1"),
        ("UserId", typeof(int), "int", false, null, null, null, null),
        ("ClientId", typeof(Guid), "uniqueidentifier", true, null, null, null, null),
        ("GoalId", typeof(int), "int", false, null, null, null, null),
        ("ContributionId", typeof(int), "int", false, null, null, null, null),
        ("OriginalAmount", typeof(decimal), "decimal(18,2)", false, 18, 2, null, null),
        ("OriginalContributedOn", typeof(DateOnly), "date", false, null, null, null, null),
        ("OriginalNote", typeof(string), "nvarchar(200)", true, null, null, 200, null),
        ("CanonicalVersion", typeof(short), "smallint", false, null, null, null, null),
        ("RecordedAtUtc", typeof(DateTime), "datetime2", false, null, null, null, null),
        ("DeletedAtUtc", typeof(DateTime), "datetime2", true, null, null, null, null)
    });
    TableChecks(table, new[]
    {
        ("CK_GoalContributionRequests_Amount", "[OriginalAmount] > 0"),
        ("CK_GoalContributionRequests_CanonicalVersion", "[CanonicalVersion] = 1"),
        ("CK_GoalContributionRequests_ClientId", "[ClientId] IS NULL OR [ClientId] <> '00000000-0000-0000-0000-000000000000'"),
        ("CK_GoalContributionRequests_ContributionId", "[ContributionId] > 0"),
        ("CK_GoalContributionRequests_DeletedAtUtc", "[DeletedAtUtc] IS NULL OR [DeletedAtUtc] >= [RecordedAtUtc]")
    });
    TableKeys(table, "PK_GoalContributionRequests", new[] { "RequestId" }, new[]
    {
        ("FK_GoalContributionRequests_AspNetUsers_UserId", "UserId", "AspNetUsers", "Id"),
        ("FK_GoalContributionRequests_SavingsGoals_GoalId", "GoalId", "SavingsGoals", "GoalId")
    });
});
Check("Historical savings ledger has exactly three approved indexes", () =>
{
    Indexes(CompiledMigration(savingsMigrationId).UpOperations, "GoalContributionRequests",
        new (string, string[], bool, string?)[]
    {
        ("IX_GoalContributionRequests_ContributionId", new[] { "ContributionId" }, true, null),
        ("IX_GoalContributionRequests_GoalId", new[] { "GoalId" }, false, null),
        ("IX_GoalContributionRequests_UserId_ClientId", new[] { "UserId", "ClientId" }, true, "[ClientId] IS NOT NULL")
    });
});
Check("Fourth migration has exactly thirteen ordered operations", () =>
{
    var ops = CompiledMigration(billMigrationId).UpOperations;
    Require(ops.Count == 13, "Fourth operation count.");
    Require(ops.Select(o => o.GetType()).SequenceEqual(new[] {
        typeof(SqlOperation), typeof(AddColumnOperation), typeof(CreateTableOperation),
        typeof(AddCheckConstraintOperation), typeof(AddCheckConstraintOperation),
        typeof(CreateIndexOperation), typeof(CreateIndexOperation), typeof(CreateIndexOperation), typeof(CreateIndexOperation),
        typeof(SqlOperation), typeof(SqlOperation), typeof(SqlOperation), typeof(SqlOperation) }), "Fourth operation sequence.");
    Require(ops.OfType<SqlOperation>().Count() == 5
        && ops.OfType<SqlOperation>().All(o => !o.SuppressTransaction), "Exactly five transactional SQL operations required.");
});
Check("Fourth migration requires AnchorDate without default, computed value or backfill", () =>
{
    var column = (AddColumnOperation)CompiledMigration(billMigrationId).UpOperations[1];
    Require(column.Name == "AnchorDate" && column.Table == "RecurringBills" && column.Schema is null
        && column.ClrType == typeof(DateOnly) && column.ColumnType == "date" && !column.IsNullable
        && column.DefaultValue is null && column.DefaultValueSql is null && column.ComputedColumnSql is null
        && !column.IsRowVersion, "AnchorDate definition.");
});
Check("Fourth receipt table has exact columns, ten checks and four restrictive foreign keys", () =>
{
    var table = (CreateTableOperation)CompiledMigration(billMigrationId).UpOperations[2];
    Require(table.Name == "BillPaymentReceipts", "Receipt table.");
    Columns(table, new (string, Type, string, bool, int?, int?, int?, string?)[]
    {
        ("ReceiptId", typeof(long), "bigint", false, null, null, null, "1, 1"),
        ("UserId", typeof(int), "int", false, null, null, null, null),
        ("ClientId", typeof(Guid), "uniqueidentifier", false, null, null, null, null),
        ("BillId", typeof(int), "int", false, null, null, null, null),
        ("TransactionId", typeof(int), "int", false, null, null, null, null),
        ("DueDate", typeof(DateOnly), "date", false, null, null, null, null),
        ("CategoryId", typeof(int), "int", false, null, null, null, null),
        ("Amount", typeof(decimal), "decimal(18,2)", false, 18, 2, null, null),
        ("CurrencyCode", typeof(string), "char(3)", false, null, null, null, null),
        ("FxRate", typeof(decimal), "decimal(18,8)", false, 18, 8, null, null),
        ("FxRateDate", typeof(DateOnly), "date", false, null, null, null, null),
        ("AmountBase", typeof(decimal), "decimal(18,2)", false, 18, 2, null, null),
        ("BaseCurrency", typeof(string), "char(3)", false, null, null, null, null),
        ("PaidOn", typeof(DateOnly), "date", false, null, null, null, null),
        ("CanonicalVersion", typeof(short), "smallint", false, null, null, null, null),
        ("RecordedAtUtc", typeof(DateTime), "datetime2", false, null, null, null, null)
    });
    TableChecks(table, new[]
    {
        ("CK_BillPaymentReceipts_Amount", "[Amount] > 0"),
        ("CK_BillPaymentReceipts_AmountBase", "[AmountBase] >= 0"),
        ("CK_BillPaymentReceipts_BaseCurrency", "DATALENGTH([BaseCurrency]) = 3 AND [BaseCurrency] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'"),
        ("CK_BillPaymentReceipts_CanonicalVersion", "[CanonicalVersion] = 1"),
        ("CK_BillPaymentReceipts_ClientId", "[ClientId] <> '00000000-0000-0000-0000-000000000000'"),
        ("CK_BillPaymentReceipts_CurrencyCode", "DATALENGTH([CurrencyCode]) = 3 AND [CurrencyCode] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'"),
        ("CK_BillPaymentReceipts_DueDate", "[DueDate] <= [PaidOn]"),
        ("CK_BillPaymentReceipts_FxRate", "[FxRate] > 0"),
        ("CK_BillPaymentReceipts_FxRateDate", "[FxRateDate] <= [PaidOn]"),
        ("CK_BillPaymentReceipts_PaidOn", "[PaidOn] = CONVERT(date, [RecordedAtUtc])")
    });
    TableKeys(table, "PK_BillPaymentReceipts", new[] { "ReceiptId" }, new[]
    {
        ("FK_BillPaymentReceipts_AspNetUsers_UserId", "UserId", "AspNetUsers", "Id"),
        ("FK_BillPaymentReceipts_Categories_CategoryId", "CategoryId", "Categories", "CategoryId"),
        ("FK_BillPaymentReceipts_RecurringBills_BillId", "BillId", "RecurringBills", "BillId"),
        ("FK_BillPaymentReceipts_Transactions_TransactionId", "TransactionId", "Transactions", "TransactionId")
    });
});
Check("Fourth migration has exact bill checks and four ordered receipt indexes", () =>
{
    var ops = CompiledMigration(billMigrationId).UpOperations;
    var anchor = (AddCheckConstraintOperation)ops[3];
    var reminder = (AddCheckConstraintOperation)ops[4];
    Require(anchor.Name == "CK_RecurringBills_AnchorDate" && anchor.Table == "RecurringBills" && anchor.Schema is null
        && anchor.Sql == "[AnchorDate] > '00010101' AND [NextDueDate] >= [AnchorDate]", "Anchor check.");
    Require(reminder.Name == "CK_RecurringBills_ReminderDaysBefore" && reminder.Table == "RecurringBills" && reminder.Schema is null
        && reminder.Sql == "[ReminderDaysBefore] BETWEEN 0 AND 30", "Reminder check.");
    Indexes(ops, "BillPaymentReceipts", new (string, string[], bool, string?)[]
    {
        ("IX_BillPaymentReceipts_BillId_DueDate", new[] { "BillId", "DueDate" }, true, null),
        ("IX_BillPaymentReceipts_CategoryId", new[] { "CategoryId" }, false, null),
        ("IX_BillPaymentReceipts_TransactionId", new[] { "TransactionId" }, true, null),
        ("IX_BillPaymentReceipts_UserId_ClientId", new[] { "UserId", "ClientId" }, true, null)
    });
});

Check("Fourth migration first eligibility SQL is exact and fail-closed", () =>
{
    var sql = (SqlOperation)CompiledMigration(billMigrationId).UpOperations[0];
    const string expected = """
        IF @@TRANCOUNT = 0 OR XACT_STATE() <> 1
            THROW 51030, 'This migration requires an active committable transaction.', 1;
        
        IF OBJECT_ID(N'dbo.RecurringBills', N'U') IS NULL
            OR OBJECT_ID(N'dbo.Transactions', N'U') IS NULL
            THROW 51031, 'Expected financial tables are missing.', 1;
        
        IF OBJECT_ID(N'dbo.BillPaymentReceipts') IS NOT NULL
            THROW 51032, 'Unexpected payment receipt object exists. Reconciliation is required.', 1;
        
        IF COL_LENGTH(N'dbo.RecurringBills', N'AnchorDate') IS NOT NULL
            THROW 51033, 'Unexpected AnchorDate column exists. Reconciliation is required.', 1;
        
        DECLARE @RecurringBillCount bigint;
        DECLARE @LinkedTransactionCount bigint;
        
        SELECT @RecurringBillCount = COUNT_BIG(*)
        FROM [dbo].[RecurringBills] WITH (TABLOCKX, HOLDLOCK);
        
        SELECT @LinkedTransactionCount = COUNT_BIG(*)
        FROM [dbo].[Transactions] WITH (TABLOCKX, HOLDLOCK)
        WHERE [BillId] IS NOT NULL
           OR [BillDueDate] IS NOT NULL;
        
        IF @RecurringBillCount <> 0
            THROW 51034, 'Existing recurring bills require an approved AnchorDate backfill.', 1;
        
        IF @LinkedTransactionCount <> 0
            THROW 51035, 'Existing bill-linked transactions require payment-history reconciliation.', 1;
        """;
    Require(!sql.SuppressTransaction && NormalizeSql(sql.Sql) == NormalizeSql(expected), "Approved SQL differs: operation 0.");
});

Check("Fourth migration transaction INSERT validation SQL is exact", () =>
{
    var sql = (SqlOperation)CompiledMigration(billMigrationId).UpOperations[9];
    const string expected = """
        EXEC(N'CREATE TRIGGER [dbo].[TR_Transactions_ValidateBillPaymentInsert]
        ON [dbo].[Transactions]
        AFTER INSERT
        AS
        BEGIN
            SET NOCOUNT ON;
        
            IF NOT EXISTS
            (
                SELECT 1
                FROM inserted
                WHERE [BillId] IS NOT NULL
                   OR [BillDueDate] IS NOT NULL
            )
                RETURN;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted
                WHERE
                    ([BillId] IS NOT NULL AND [BillDueDate] IS NULL)
                    OR
                    ([BillId] IS NULL AND [BillDueDate] IS NOT NULL)
            )
                THROW 51040, ''A bill payment requires both bill linkage fields.'', 1;
        
            DECLARE @Bills TABLE
            (
                [BillId] int NOT NULL PRIMARY KEY,
                [UserId] int NOT NULL,
                [CategoryId] int NOT NULL,
                [Amount] decimal(18,2) NOT NULL,
                [CurrencyCode] char(3) NOT NULL,
                [NextDueDate] date NOT NULL,
                [IsActive] bit NOT NULL
            );
        
            INSERT INTO @Bills
            (
                [BillId], [UserId], [CategoryId], [Amount],
                [CurrencyCode], [NextDueDate], [IsActive]
            )
            SELECT
                b.[BillId], b.[UserId], b.[CategoryId], b.[Amount],
                b.[CurrencyCode], b.[NextDueDate], b.[IsActive]
            FROM [dbo].[RecurringBills] AS b WITH (UPDLOCK, HOLDLOCK)
            WHERE EXISTS
            (
                SELECT 1
                FROM inserted AS i
                WHERE i.[BillId] = b.[BillId]
                  AND i.[BillId] IS NOT NULL
            );
        
            DECLARE @Categories TABLE
            (
                [CategoryId] int NOT NULL PRIMARY KEY,
                [UserId] int NULL,
                [Type] nvarchar(10) NOT NULL,
                [IsActive] bit NOT NULL
            );
        
            INSERT INTO @Categories
            (
                [CategoryId], [UserId], [Type], [IsActive]
            )
            SELECT
                c.[CategoryId], c.[UserId], c.[Type], c.[IsActive]
            FROM [dbo].[Categories] AS c WITH (UPDLOCK, HOLDLOCK)
            WHERE EXISTS
            (
                SELECT 1
                FROM @Bills AS b
                WHERE b.[CategoryId] = c.[CategoryId]
            );
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS i
                LEFT JOIN @Bills AS b ON b.[BillId] = i.[BillId]
                LEFT JOIN @Categories AS c ON c.[CategoryId] = b.[CategoryId]
                WHERE
                    (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
                    AND
                    (
                        b.[BillId] IS NULL
                        OR b.[UserId] <> i.[UserId]
                        OR b.[IsActive] <> 1
                        OR CONVERT(varbinary(max), i.[Type])
                            <> CONVERT(varbinary(max), N''Expense'')
                        OR i.[CategoryId] <> b.[CategoryId]
                        OR i.[Amount] <> b.[Amount]
                        OR CONVERT(varbinary(max), i.[CurrencyCode])
                            <> CONVERT(varbinary(max), b.[CurrencyCode])
                        OR i.[BillDueDate] <> b.[NextDueDate]
                        OR c.[CategoryId] IS NULL
                        OR c.[IsActive] <> 1
                        OR CONVERT(varbinary(max), c.[Type])
                            <> CONVERT(varbinary(max), N''Expense'')
                        OR (c.[UserId] IS NOT NULL AND c.[UserId] <> i.[UserId])
                    )
            )
                THROW 51041, ''Bill payment does not match an active owned bill and accessible expense category.'', 1;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS i
                WHERE
                    (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
                    AND
                    (
                        i.[BillDueDate] > i.[TransactionDate]
                        OR i.[TransactionDate] <> CONVERT(date, i.[CreatedAtUtc])
                        OR i.[Description] IS NULL
                        OR CONVERT(varbinary(max), i.[Description])
                            <> CONVERT(varbinary(max), N''Recurring bill payment'')
                    )
            )
                THROW 51042, ''Bill payment date or description is invalid.'', 1;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS i
                WHERE
                    (i.[BillId] IS NOT NULL OR i.[BillDueDate] IS NOT NULL)
                    AND i.[ClientId] = ''00000000-0000-0000-0000-000000000000''
            )
                THROW 51043, ''A bill payment requires a nonempty ClientId.'', 1;
        END;');
        """;
    Require(!sql.SuppressTransaction && NormalizeSql(sql.Sql) == NormalizeSql(expected), "Approved SQL differs: operation 9.");
    var batch = NormalizeSql(sql.Sql);
    Require(batch.StartsWith("EXEC(N'", StringComparison.Ordinal) && batch.EndsWith("');", StringComparison.Ordinal), "Dynamic SQL batch.");
    var body = batch[7..^3];
    Require(!body.Replace("''", "", StringComparison.Ordinal).Contains('\''), "Unescaped dynamic SQL quote.");
});

Check("Fourth migration receipt INSERT validation SQL is exact", () =>
{
    var sql = (SqlOperation)CompiledMigration(billMigrationId).UpOperations[10];
    const string expected = """
        EXEC(N'CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_ValidateInsert]
        ON [dbo].[BillPaymentReceipts]
        AFTER INSERT
        AS
        BEGIN
            SET NOCOUNT ON;
        
            IF NOT EXISTS (SELECT 1 FROM inserted)
                RETURN;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS r
                WHERE r.[CanonicalVersion] <> 1
                   OR r.[ClientId] = ''00000000-0000-0000-0000-000000000000''
            )
                THROW 51050, ''Receipt request identity or canonical version is invalid.'', 1;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS r
                LEFT JOIN [dbo].[Transactions] AS t WITH (UPDLOCK, HOLDLOCK)
                    ON t.[TransactionId] = r.[TransactionId]
                LEFT JOIN [dbo].[RecurringBills] AS b WITH (UPDLOCK, HOLDLOCK)
                    ON b.[BillId] = r.[BillId]
                LEFT JOIN [dbo].[AspNetUsers] AS u WITH (UPDLOCK, HOLDLOCK)
                    ON u.[Id] = r.[UserId]
                WHERE
                    t.[TransactionId] IS NULL
                    OR b.[BillId] IS NULL
                    OR u.[Id] IS NULL
                    OR t.[UserId] <> r.[UserId]
                    OR b.[UserId] <> r.[UserId]
                    OR t.[ClientId] <> r.[ClientId]
                    OR t.[BillId] IS NULL
                    OR t.[BillDueDate] IS NULL
                    OR t.[BillId] <> r.[BillId]
                    OR t.[BillDueDate] <> r.[DueDate]
                    OR CONVERT(varbinary(max), t.[Type])
                        <> CONVERT(varbinary(max), N''Expense'')
                    OR t.[CategoryId] <> r.[CategoryId]
                    OR t.[Amount] <> r.[Amount]
                    OR CONVERT(varbinary(max), t.[CurrencyCode])
                        <> CONVERT(varbinary(max), r.[CurrencyCode])
                    OR t.[FxRate] <> r.[FxRate]
                    OR t.[FxRateDate] <> r.[FxRateDate]
                    OR t.[AmountBase] <> r.[AmountBase]
                    OR t.[TransactionDate] <> r.[PaidOn]
                    OR t.[CreatedAtUtc] <> r.[RecordedAtUtc]
                    OR t.[Description] IS NULL
                    OR CONVERT(varbinary(max), t.[Description])
                        <> CONVERT(varbinary(max), N''Recurring bill payment'')
                    OR CONVERT(varbinary(max), u.[BaseCurrency])
                        <> CONVERT(varbinary(max), r.[BaseCurrency])
            )
                THROW 51051, ''Receipt does not match protected payment history.'', 1;
        END;');
        """;
    Require(!sql.SuppressTransaction && NormalizeSql(sql.Sql) == NormalizeSql(expected), "Approved SQL differs: operation 10.");
    var batch = NormalizeSql(sql.Sql);
    Require(batch.StartsWith("EXEC(N'", StringComparison.Ordinal) && batch.EndsWith("');", StringComparison.Ordinal), "Dynamic SQL batch.");
    var body = batch[7..^3];
    Require(!body.Replace("''", "", StringComparison.Ordinal).Contains('\''), "Unescaped dynamic SQL quote.");
});

Check("Fourth migration receipt append-only SQL is exact", () =>
{
    var sql = (SqlOperation)CompiledMigration(billMigrationId).UpOperations[11];
    const string expected = """
        EXEC(N'CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_AppendOnly]
        ON [dbo].[BillPaymentReceipts]
        AFTER UPDATE, DELETE
        AS
        BEGIN
            SET NOCOUNT ON;
        
            IF EXISTS (SELECT 1 FROM deleted)
                THROW 51060, ''Accepted bill-payment receipts cannot be modified or deleted.'', 1;
        END;');
        """;
    Require(!sql.SuppressTransaction && NormalizeSql(sql.Sql) == NormalizeSql(expected), "Approved SQL differs: operation 11.");
    var batch = NormalizeSql(sql.Sql);
    Require(batch.StartsWith("EXEC(N'", StringComparison.Ordinal) && batch.EndsWith("');", StringComparison.Ordinal), "Dynamic SQL batch.");
    var body = batch[7..^3];
    Require(!body.Replace("''", "", StringComparison.Ordinal).Contains('\''), "Unescaped dynamic SQL quote.");
});

Check("Fourth migration transaction protection replacement SQL is exact", () =>
{
    var sql = (SqlOperation)CompiledMigration(billMigrationId).UpOperations[12];
    const string expected = """
        EXEC(N'CREATE OR ALTER TRIGGER [dbo].[TR_Transactions_ProtectBillPayments]
        ON [dbo].[Transactions]
        AFTER UPDATE, DELETE
        AS
        BEGIN
            SET NOCOUNT ON;
        
            IF NOT EXISTS (SELECT 1 FROM deleted)
                RETURN;
        
            IF EXISTS
            (
                SELECT 1
                FROM deleted AS d
                WHERE d.[BillId] IS NOT NULL
                   OR d.[BillDueDate] IS NOT NULL
            )
                THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;
        
            IF EXISTS
            (
                SELECT 1
                FROM inserted AS i
                WHERE i.[BillId] IS NOT NULL
                   OR i.[BillDueDate] IS NOT NULL
            )
                THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;
        
            IF EXISTS
            (
                SELECT 1
                FROM deleted AS d
                INNER JOIN [dbo].[BillPaymentReceipts] AS r WITH (HOLDLOCK)
                    ON r.[TransactionId] = d.[TransactionId]
            )
                THROW 51070, ''Bill-payment transactions cannot be modified or deleted.'', 1;
        END;');
        """;
    Require(!sql.SuppressTransaction && NormalizeSql(sql.Sql) == NormalizeSql(expected), "Approved SQL differs: operation 12.");
    var batch = NormalizeSql(sql.Sql);
    Require(batch.StartsWith("EXEC(N'", StringComparison.Ordinal) && batch.EndsWith("');", StringComparison.Ordinal), "Dynamic SQL batch.");
    var body = batch[7..^3];
    Require(!body.Replace("''", "", StringComparison.Ordinal).Contains('\''), "Unescaped dynamic SQL quote.");
});

Check("Fourth migration Down refuses before exposing rollback operations", () =>
{
    var migration = CompiledMigration(billMigrationId);
    try
    {
        _ = migration.DownOperations;
    }
    catch (InvalidOperationException ex) when (ex.Message ==
        "This migration is forward-only and must not be downgraded automatically because doing so could destroy accepted financial history.")
    {
        return;
    }
    throw new InvalidOperationException("Expected the exact forward-only Down refusal.");
});
Check("Current model and snapshot retain exact financial trigger metadata and disabled SQL OUTPUT", () =>
{
    var snapshot = db.GetService<IMigrationsAssembly>().ModelSnapshot
        ?? throw new InvalidOperationException("Snapshot missing.");
    var snapshotModel = db.GetService<IModelRuntimeInitializer>().Initialize(snapshot.Model, designTime: true);
    foreach (var inspected in new[] { model, snapshotModel })
    {
        foreach (var item in new[] {
            (typeof(BillPaymentReceipt).FullName!, new[] { "TR_BillPaymentReceipts_AppendOnly", "TR_BillPaymentReceipts_ValidateInsert" }),
            (typeof(FinanceTransaction).FullName!, new[] { "TR_Transactions_ProtectBillPayments", "TR_Transactions_ValidateBillPaymentInsert" }) })
        {
            var entity = inspected.FindEntityType(item.Item1)
                ?? throw new InvalidOperationException("Entity missing: " + item.Item1);
            Require(entity.GetDeclaredTriggers().Select(t => t.ModelName).Order(StringComparer.Ordinal)
                .SequenceEqual(item.Item2.Order(StringComparer.Ordinal)), item.Item1 + " trigger names.");
            Require(entity.FindAnnotation("SqlServer:UseSqlOutputClause")?.Value is false, item.Item1 + " SQL OUTPUT.");
        }
    }
});
Check("Default offline model validation made no connection-opening attempt", () =>
    Require(defaultConnectionGuard.Attempts == 0, "Database connection opening was attempted."));
Console.WriteLine($"Schema validation: {passed} passed, {failed} failed. No database connections were opened.");
return failed == 0 ? 0 : 1;

internal static class BillModelValidation
{
    public static int Run()
    {
        var passed = 0;
        var failed = 0;
        var guard = new RejectConnectionOpening();
        var options = new DbContextOptionsBuilder<ApplicationDbContext>()
            .UseSqlServer().AddInterceptors(guard).Options;
        void Require(bool value, string detail)
        {
            if (!value) throw new InvalidOperationException(detail);
        }
        void Check(string name, Action action)
        {
            try { action(); passed++; Console.WriteLine("PASS " + name); }
            catch (Exception ex)
            {
                failed++;
                Console.Error.WriteLine($"FAIL {name}: {ex.GetType().Name}: {ex.Message}");
            }
        }

        using var context = new ApplicationDbContext(options);
        IModel? design = null;
        Check("Runtime and design-time SQL Server models construct without a connection", () =>
        {
            Require(context.Model.FindEntityType(typeof(BillPaymentReceipt)) is not null, "Runtime receipt missing.");
            design = context.GetService<IDesignTimeModel>().Model;
            Require(design.FindEntityType(typeof(BillPaymentReceipt)) is not null, "Design-time receipt missing.");
        });
        if (design is not null)
        {
            var receipt = design.FindEntityType(typeof(BillPaymentReceipt))!;
            var bill = design.FindEntityType(typeof(RecurringBill))!;
            string Currency(string column) => $"DATALENGTH([{column}]) = 3 AND [{column}] COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z]%'";
            void Checks(IEntityType entity, Dictionary<string, string> expected)
            {
                var actual = entity.GetCheckConstraints().ToDictionary(
                    c => c.Name ?? throw new InvalidOperationException("Unnamed check constraint."), c => c.Sql);
                Require(actual.Count == expected.Count, "Unexpected check-constraint count.");
                foreach (var item in expected)
                    Require(actual.TryGetValue(item.Key, out var sql) && sql == item.Value, item.Key);
            }
            Check("Receipt table and all 16 required properties", () =>
            {
                Require(receipt.GetTableName() == "BillPaymentReceipts", "Receipt table mismatch.");
                var expected = new Dictionary<string, Type>
                {
                    ["ReceiptId"] = typeof(long), ["UserId"] = typeof(int), ["ClientId"] = typeof(Guid),
                    ["BillId"] = typeof(int), ["TransactionId"] = typeof(int), ["DueDate"] = typeof(DateOnly),
                    ["CategoryId"] = typeof(int), ["Amount"] = typeof(decimal), ["CurrencyCode"] = typeof(string),
                    ["FxRate"] = typeof(decimal), ["FxRateDate"] = typeof(DateOnly), ["AmountBase"] = typeof(decimal),
                    ["BaseCurrency"] = typeof(string), ["PaidOn"] = typeof(DateOnly),
                    ["CanonicalVersion"] = typeof(short), ["RecordedAtUtc"] = typeof(DateTime)
                };
                Require(receipt.GetProperties().Count() == 16, "Unexpected receipt property count.");
                foreach (var item in expected)
                {
                    var p = receipt.FindProperty(item.Key);
                    Require(p is not null && p.ClrType == item.Value && !p.IsNullable && !p.IsShadowProperty(), item.Key);
                }
            });
            Check("Receipt primary key and SQL Server identity", () =>
            {
                Require(receipt.FindPrimaryKey()!.Properties.Select(p => p.Name).SequenceEqual(new[] { "ReceiptId" }), "Receipt key mismatch.");
                var p = receipt.FindProperty("ReceiptId")!;
                Require(p.ValueGenerated == ValueGenerated.OnAdd
                    && p.GetValueGenerationStrategy() == SqlServerValueGenerationStrategy.IdentityColumn, "Identity mapping mismatch.");
            });
            Check("Four required restrictive receipt foreign keys", () =>
            {
                var expected = new Dictionary<string, Type>
                {
                    ["UserId"] = typeof(ApplicationUser), ["BillId"] = typeof(RecurringBill),
                    ["TransactionId"] = typeof(FinanceTransaction), ["CategoryId"] = typeof(Category)
                };
                Require(receipt.GetForeignKeys().Count() == 4, "Unexpected receipt relationship count.");
                foreach (var item in expected)
                {
                    var fk = receipt.GetForeignKeys().Single(f => f.Properties.Select(p => p.Name).SequenceEqual(new[] { item.Key }));
                    Require(fk.PrincipalEntityType.ClrType == item.Value && fk.IsRequired
                        && fk.DeleteBehavior == DeleteBehavior.NoAction, item.Key);
                }
            });
            Check("Three unfiltered unique receipt indexes", () =>
            {
                var unique = receipt.GetIndexes().Where(i => i.IsUnique).ToArray();
                Require(unique.Length == 3, "Unexpected unique-index count.");
                foreach (var names in new[] { new[] { "UserId", "ClientId" }, new[] { "BillId", "DueDate" }, new[] { "TransactionId" } })
                    Require(unique.Any(i => i.Properties.Select(p => p.Name).SequenceEqual(names) && i.GetFilter() is null), string.Join(",", names));
            });
            Check("Receipt monetary precision, currency and date mappings", () =>
            {
                foreach (var name in new[] { "Amount", "AmountBase", "FxRate" })
                {
                    var p = receipt.FindProperty(name)!;
                    Require(p.GetPrecision() == 18 && p.GetScale() == (name == "FxRate" ? 8 : 2), name);
                }
                foreach (var name in new[] { "CurrencyCode", "BaseCurrency" })
                    Require(receipt.FindProperty(name)!.GetColumnType() == "char(3)", name);
                foreach (var name in new[] { "DueDate", "FxRateDate", "PaidOn" })
                    Require(receipt.FindProperty(name)!.GetColumnType() == "date", name);
                Require(receipt.FindProperty("RecordedAtUtc")!.GetColumnType() == "datetime2", "RecordedAtUtc");
            });
            Check("Ten exact receipt check constraints", () => Checks(receipt, new()
            {
                ["CK_BillPaymentReceipts_ClientId"] = "[ClientId] <> '00000000-0000-0000-0000-000000000000'",
                ["CK_BillPaymentReceipts_Amount"] = "[Amount] > 0",
                ["CK_BillPaymentReceipts_AmountBase"] = "[AmountBase] >= 0",
                ["CK_BillPaymentReceipts_FxRate"] = "[FxRate] > 0",
                ["CK_BillPaymentReceipts_CurrencyCode"] = Currency("CurrencyCode"),
                ["CK_BillPaymentReceipts_BaseCurrency"] = Currency("BaseCurrency"),
                ["CK_BillPaymentReceipts_FxRateDate"] = "[FxRateDate] <= [PaidOn]",
                ["CK_BillPaymentReceipts_DueDate"] = "[DueDate] <= [PaidOn]",
                ["CK_BillPaymentReceipts_PaidOn"] = "[PaidOn] = CONVERT(date, [RecordedAtUtc])",
                ["CK_BillPaymentReceipts_CanonicalVersion"] = "[CanonicalVersion] = 1"
            }));
            Check("Receipt after-save restrictions in both models", () =>
            {
                foreach (var entity in new[] { receipt, context.Model.FindEntityType(typeof(BillPaymentReceipt))! })
                    foreach (var p in entity.GetProperties().Where(p => p.Name != "ReceiptId"))
                        Require(p.GetAfterSaveBehavior() == PropertySaveBehavior.Throw, p.Name);
            });
            Check("Existing bill mappings, anchor and rowversion", () =>
            {
                Require(bill.GetTableName() == "RecurringBills" && bill.FindPrimaryKey()!.Properties.Single().Name == "BillId", "Bill identity.");
                Require(bill.FindProperty("AnchorDate")!.ClrType == typeof(DateOnly)
                    && bill.FindProperty("AnchorDate")!.GetColumnType() == "date"
                    && !bill.FindProperty("AnchorDate")!.IsNullable, "Anchor mapping.");
                Require(bill.FindProperty("NextDueDate")!.GetColumnType() == "date", "Due cursor.");
                Require(bill.FindProperty("Amount")!.GetPrecision() == 18 && bill.FindProperty("Amount")!.GetScale() == 2, "Bill money.");
                Require(bill.FindProperty("CurrencyCode")!.GetColumnType() == "char(3)", "Bill currency.");
                Require(bill.FindProperty("Name")!.GetMaxLength() == 100 && !bill.FindProperty("Name")!.IsNullable, "Bill name.");
                Require(bill.FindProperty("Frequency")!.GetTypeMapping().Converter?.ProviderClrType == typeof(string)
                    && bill.FindProperty("Frequency")!.GetMaxLength() == 20, "Frequency conversion.");
                Require(Equals(bill.FindProperty("IsActive")!.GetDefaultValue(), true)
                    && bill.FindProperty("IntendedDayOfMonth")!.IsNullable, "Bill defaults.");
                var version = bill.FindProperty("RowVersion")!;
                Require(version.IsConcurrencyToken && version.ValueGenerated == ValueGenerated.OnAddOrUpdate
                    && version.GetColumnType() == "rowversion", "Bill rowversion.");
                Require(bill.GetForeignKeys().Count() == 2, "Bill relationship count.");
                foreach (var item in new[] { ("UserId", typeof(ApplicationUser)), ("CategoryId", typeof(Category)) })
                    Require(bill.GetForeignKeys().Any(f => f.Properties.Single().Name == item.Item1
                        && f.PrincipalEntityType.ClrType == item.Item2 && f.IsRequired
                        && f.DeleteBehavior == DeleteBehavior.NoAction), item.Item1);
            });
            Check("Seven exact recurring-bill check constraints", () => Checks(bill, new()
            {
                ["CK_RecurringBills_Amount"] = "[Amount] > 0",
                ["CK_RecurringBills_Name"] = "LEN(LTRIM(RTRIM([Name]))) > 0",
                ["CK_RecurringBills_CurrencyCode"] = Currency("CurrencyCode"),
                ["CK_RecurringBills_Frequency"] = "[Frequency] COLLATE Latin1_General_100_BIN2 IN ('Weekly','Monthly','Quarterly','Yearly')",
                ["CK_RecurringBills_IntendedDay"] = "([Frequency] = 'Weekly' AND [IntendedDayOfMonth] IS NULL) OR ([Frequency] IN ('Monthly','Quarterly','Yearly') AND [IntendedDayOfMonth] IS NOT NULL AND [IntendedDayOfMonth] BETWEEN 1 AND 31)",
                ["CK_RecurringBills_ReminderDaysBefore"] = "[ReminderDaysBefore] BETWEEN 0 AND 30",
                ["CK_RecurringBills_AnchorDate"] = "[AnchorDate] > '00010101' AND [NextDueDate] >= [AnchorDate]"
            }));
            Check("Receipt UTC converter accepts UTC, rejects ambiguous kinds and restores UTC", () =>
            {
                var converter = receipt.FindProperty("RecordedAtUtc")!.GetValueConverter()!;
                var utc = new DateTime(2026, 11, 6, 12, 0, 0, DateTimeKind.Utc);
                Require(Equals(converter.ConvertToProvider(utc), utc), "UTC round trip.");
                Require(((DateTime)converter.ConvertFromProvider(DateTime.SpecifyKind(utc, DateTimeKind.Unspecified))!).Kind == DateTimeKind.Utc, "Read kind.");
                foreach (var kind in new[] { DateTimeKind.Unspecified, DateTimeKind.Local })
                {
                    try { converter.ConvertToProvider(DateTime.SpecifyKind(utc, kind)); }
                    catch (ArgumentException ex) when (ex.Message.Contains("DateTimeKind.Utc", StringComparison.Ordinal)) { continue; }
                    throw new InvalidOperationException("Expected UTC-kind rejection: " + kind);
                }
            });
            foreach (var property in receipt.GetProperties().Where(p => p.Name != "ReceiptId"))
                Check("Tracked mutation rejected without SaveChanges: " + property.Name, () =>
                {
                    using var tracking = new ApplicationDbContext(options);
                    var value = new BillPaymentReceipt { UserId = 1, ClientId = Guid.NewGuid(), BillId = 1,
                        TransactionId = 1, CategoryId = 1, Amount = 10m, CurrencyCode = "PHP", FxRate = 1m,
                        AmountBase = 10m, BaseCurrency = "PHP", DueDate = new(2026, 11, 1),
                        FxRateDate = new(2026, 11, 6), PaidOn = new(2026, 11, 6),
                        RecordedAtUtc = new(2026, 11, 6, 12, 0, 0, DateTimeKind.Utc) };
                    var entry = tracking.Entry(value);
                    entry.Property("ReceiptId").CurrentValue = 1L;
                    entry.State = EntityState.Unchanged;
                    tracking.ChangeTracker.DetectChanges();
                    var p = entry.Property(property.Name);
                    object replacement = p.CurrentValue switch
                    {
                        int n => n + 1, short n => (short)(n + 1), decimal n => n + 1m,
                        Guid _ => Guid.NewGuid(), string s => s == "PHP" ? "USD" : "PHP",
                        DateOnly d => d.AddDays(1), DateTime d => d.AddMinutes(1),
                        _ => throw new InvalidOperationException("Unsupported mutation type.")
                    };
                    try
                    {
                        p.CurrentValue = replacement;
                        tracking.ChangeTracker.DetectChanges();
                        // EF enforces after-save restrictions while preparing tracked entries
                        // for persistence, not during change detection. Invoke that check only:
                        // no SaveChanges, command generation, connection or database execution.
#pragma warning disable EF1001 // Narrow offline verification of the pinned EF Core validation path.
                        entry.GetInfrastructure().PrepareToSave();
#pragma warning restore EF1001
                    }
                    catch (InvalidOperationException ex) when (
                        ex.Message.Contains("read-only", StringComparison.OrdinalIgnoreCase)
                        && ex.Message.Contains("BillPaymentReceipt." + property.Name, StringComparison.Ordinal))
                    { return; }
                    throw new InvalidOperationException("Expected property-specific read-only rejection.");
                });
        }
        Check("No connection string or connection-opening attempt", () =>
        {
            Require(context.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer", "Wrong provider.");
            Require(string.IsNullOrEmpty(context.Database.GetConnectionString()), "Unexpected connection string.");
            Require(guard.Attempts == 0, "Database connection opening was attempted.");
        });
        Console.WriteLine($"Bill model validation: {passed} passed, {failed} failed; connection attempts: {guard.Attempts}. No SaveChanges executed.");
        return failed == 0 ? 0 : 1;
    }
}

internal sealed class RejectConnectionOpening : Microsoft.EntityFrameworkCore.Diagnostics.DbConnectionInterceptor
{
    public int Attempts { get; private set; }
    private void Reject()
    {
        Attempts++;
        throw new InvalidOperationException("Offline validation prohibits database connection opening.");
    }
    public override Microsoft.EntityFrameworkCore.Diagnostics.InterceptionResult ConnectionOpening(
        System.Data.Common.DbConnection connection,
        Microsoft.EntityFrameworkCore.Diagnostics.ConnectionEventData eventData,
        Microsoft.EntityFrameworkCore.Diagnostics.InterceptionResult result)
    {
        Reject();
        return result;
    }
    public override ValueTask<Microsoft.EntityFrameworkCore.Diagnostics.InterceptionResult> ConnectionOpeningAsync(
        System.Data.Common.DbConnection connection,
        Microsoft.EntityFrameworkCore.Diagnostics.ConnectionEventData eventData,
        Microsoft.EntityFrameworkCore.Diagnostics.InterceptionResult result,
        CancellationToken cancellationToken = default)
    {
        Reject();
        return ValueTask.FromResult(result);
    }
}

internal static class BillMigrationSqlValidation
{
    public static int Run()
    {
        const string from = "20261103141326_AddSavingsGoalContributionLedgerAndRowVersions";
        const string to = "20261107054048_AddBillPaymentReceiptsAndGuards";
        var guard = new RejectConnectionOpening();
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>()
            .UseSqlServer().AddInterceptors(guard).Options);
        var passed = 0;
        var failed = 0;
        string? sql = null;
        void Require(bool value, string message)
        {
            if (!value) throw new InvalidOperationException(message);
        }
        void Check(string name, Action action)
        {
            try { action(); passed++; Console.WriteLine("PASS " + name); }
            catch (Exception ex)
            {
                failed++;
                Console.Error.WriteLine($"FAIL {name}: {ex.GetType().Name}: {ex.Message}");
            }
        }
        string Normalize(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal).Trim();
        string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

        Check("SQL-only context is connectionless SQL Server and initially closed", () =>
        {
            Require(context.Database.ProviderName == "Microsoft.EntityFrameworkCore.SqlServer", "Wrong provider.");
            Require(string.IsNullOrEmpty(context.Database.GetConnectionString()), "Unexpected connection string.");
            Require(context.Database.GetDbConnection().State == System.Data.ConnectionState.Closed, "Connection is not closed.");
        });
        Migration? migration = null;
        Check("Exact source and target migration IDs exist in order", () =>
        {
            var assembly = context.GetService<IMigrationsAssembly>();
            var ids = assembly.Migrations.Keys.ToArray();
            Require(ids.Contains(from, StringComparer.Ordinal) && ids.Contains(to, StringComparer.Ordinal)
                && Array.IndexOf(ids, to) == Array.IndexOf(ids, from) + 1, "Migration range is not the exact adjacent pair.");
            migration = assembly.CreateMigration(assembly.Migrations[to], context.Database.ProviderName!);
        });
        if (failed == 0 && migration is not null)
        {
            Check("Thirteen approved operations and five transaction-enabled SQL batches", () =>
            {
                var operations = migration.UpOperations;
                Require(operations.Select(o => o.GetType()).SequenceEqual(new[] {
                    typeof(SqlOperation), typeof(AddColumnOperation), typeof(CreateTableOperation),
                    typeof(AddCheckConstraintOperation), typeof(AddCheckConstraintOperation),
                    typeof(CreateIndexOperation), typeof(CreateIndexOperation), typeof(CreateIndexOperation), typeof(CreateIndexOperation),
                    typeof(SqlOperation), typeof(SqlOperation), typeof(SqlOperation), typeof(SqlOperation) }), "Operation sequence changed.");
                Require(operations.OfType<SqlOperation>().Count() == 5
                    && operations.OfType<SqlOperation>().All(o => !o.SuppressTransaction), "Nontransactional SQL operation.");
                var expectedHashes = new[] {
                    "0EBEF266C196834C8AF94D9D9A08B53143AAA273C4031A5DD11B6128B049FAD7",
                    "7036580868EB78185F5159FED9861C13558FAEABF18FEBE4AE3B9EDF9F878F7A",
                    "E5284EF199CB9FCC5F762AABBA53B6A4E69A5E372B16898C1EEAF1178C83C13B",
                    "8C051E24D3202C7D50B8106EA42EE13CBB6EA23930B3D24A8D3AB61D1D91D470",
                    "769C8804499BB044F2AD8DEF3024A4F9EA1C7FE880AF7E0CE92C60618E8D525C" };
                Require(operations.OfType<SqlOperation>().Select(o => Hash(Normalize(o.Sql))).SequenceEqual(expectedHashes),
                    "Eligibility SQL or approved trigger body changed.");
            });
            if (failed == 0)
                Check("Generate only third-to-fourth migration SQL in memory", () =>
                {
                    sql = context.GetService<IMigrator>().GenerateScript(from, to, MigrationsSqlGenerationOptions.Default);
                    Require(!string.IsNullOrWhiteSpace(sql), "Generated SQL is empty.");
                });
        }
        if (sql is not null && migration is not null)
        {
            var generated = Normalize(sql);
            var lines = sql.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
            Console.WriteLine("Generated SQL (review only; not executed):");
            for (var i = 0; i < lines.Length; i++)
                Console.WriteLine($"{i + 1:D4}: {lines[i]}");
            Console.WriteLine($"SQL line count: {lines.Length}");
            Console.WriteLine("SQL SHA-256 (UTF-8, exact returned string): " + Hash(sql));

            var batches = System.Text.RegularExpressions.Regex.Matches(generated,
                @"EXEC\(N'((?:''|[^'])*)'\);",
                System.Text.RegularExpressions.RegexOptions.Singleline).Cast<System.Text.RegularExpressions.Match>().ToArray();
            var outside = System.Text.RegularExpressions.Regex.Replace(generated, @"EXEC\(N'((?:''|[^'])*)'\);",
                "", System.Text.RegularExpressions.RegexOptions.Singleline);
            var outsideCode = System.Text.RegularExpressions.Regex.Replace(outside, @"N?'(?:''|[^'])*'", "''");
            Check("Single transaction encloses eligibility, schema, guards and history", () =>
            {
                var starts = System.Text.RegularExpressions.Regex.Matches(outsideCode, @"\bBEGIN\s+TRANSACTION\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                var commits = System.Text.RegularExpressions.Regex.Matches(outsideCode, @"\bCOMMIT\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                Require(starts.Count == 1 && commits.Count == 1, "Expected one BEGIN TRANSACTION and one COMMIT.");
                Require(!System.Text.RegularExpressions.Regex.IsMatch(outsideCode, @"\bROLLBACK\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase), "Unexpected ROLLBACK.");
                var eligibility = generated.IndexOf("IF @@TRANCOUNT = 0 OR XACT_STATE() <> 1", StringComparison.Ordinal);
                var firstMutation = generated.IndexOf("ALTER TABLE [RecurringBills] ADD [AnchorDate]", StringComparison.Ordinal);
                var history = generated.IndexOf("INSERT INTO [__EFMigrationsHistory]", StringComparison.Ordinal);
                Require(starts[0].Index < outsideCode.IndexOf("IF @@TRANCOUNT", StringComparison.Ordinal)
                    && eligibility >= 0 && firstMutation > eligibility && history > firstMutation
                    && generated.LastIndexOf("COMMIT;", StringComparison.Ordinal) > history, "Transaction/eligibility ordering.");
                Require(System.Text.RegularExpressions.Regex.Replace(outside, @"(?m)^\s*GO\s*$", "").Contains("COMMIT;", StringComparison.Ordinal),
                    "GO must be treated as a batch separator, not a transaction boundary.");
            });
            Check("Required AnchorDate has no default or automatic backfill", () =>
            {
                var anchor = (AddColumnOperation)migration.UpOperations[1];
                Require(anchor.Table == "RecurringBills" && anchor.Name == "AnchorDate" && !anchor.IsNullable
                    && anchor.ColumnType == "date" && anchor.DefaultValue is null && anchor.DefaultValueSql is null
                    && anchor.ComputedColumnSql is null, "Anchor metadata.");
                Require(System.Text.RegularExpressions.Regex.Matches(outside,
                    @"ALTER TABLE \[RecurringBills\] ADD \[AnchorDate\] date NOT NULL;").Count == 1, "Anchor SQL.");
                Require(!System.Text.RegularExpressions.Regex.IsMatch(outsideCode, @"\bUPDATE\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase), "Unexpected backfill/update.");
            });
            Check("Only approved receipt table, bill checks and four indexes are scaffolded", () =>
            {
                var table = (CreateTableOperation)migration.UpOperations[2];
                Require(table.Name == "BillPaymentReceipts" && table.Columns.Count == 16
                    && table.CheckConstraints.Count == 10 && table.ForeignKeys.Count == 4, "Receipt structure.");
                Require(table.PrimaryKey?.Name == "PK_BillPaymentReceipts"
                    && table.ForeignKeys.All(f => f.OnDelete == ReferentialAction.NoAction), "Receipt keys.");
                foreach (var column in table.Columns)
                    Require(generated.Contains($"[{column.Name}] {column.ColumnType}", StringComparison.Ordinal), "Missing column: " + column.Name);
                foreach (var name in table.CheckConstraints.Select(c => c.Name).Concat(table.ForeignKeys.Select(f => f.Name))
                    .Append("PK_BillPaymentReceipts").Concat(migration.UpOperations.OfType<AddCheckConstraintOperation>().Select(c => c.Name)))
                    Require(generated.Contains("CONSTRAINT [" + name + "]", StringComparison.Ordinal), "Missing constraint: " + name);
                var indexes = migration.UpOperations.OfType<CreateIndexOperation>().ToArray();
                Require(indexes.Select(i => i.Name).SequenceEqual(new[] {
                    "IX_BillPaymentReceipts_BillId_DueDate", "IX_BillPaymentReceipts_CategoryId",
                    "IX_BillPaymentReceipts_TransactionId", "IX_BillPaymentReceipts_UserId_ClientId" }), "Index names/order.");
                foreach (var index in indexes)
                    Require(generated.Contains("CREATE " + (index.IsUnique ? "UNIQUE " : "") + "INDEX [" + index.Name
                        + "] ON [BillPaymentReceipts] (" + string.Join(", ", index.Columns.Select(c => "[" + c + "]")) + ");",
                        StringComparison.Ordinal), "Index SQL: " + index.Name);
            });
            Check("Four correctly escaped dynamic trigger batches match approved SQL in order", () =>
            {
                var expected = migration.UpOperations.OfType<SqlOperation>().Skip(1).ToArray();
                Require(batches.Length == 4, "Expected exactly four dynamic batches.");
                for (var i = 0; i < batches.Length; i++)
                    Require(Normalize(batches[i].Value) == Normalize(expected[i].Sql), "Dynamic batch mismatch: " + i);
                var definitions = new[] {
                    "CREATE TRIGGER [dbo].[TR_Transactions_ValidateBillPaymentInsert]\nON [dbo].[Transactions]\nAFTER INSERT",
                    "CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_ValidateInsert]\nON [dbo].[BillPaymentReceipts]\nAFTER INSERT",
                    "CREATE TRIGGER [dbo].[TR_BillPaymentReceipts_AppendOnly]\nON [dbo].[BillPaymentReceipts]\nAFTER UPDATE, DELETE",
                    "CREATE OR ALTER TRIGGER [dbo].[TR_Transactions_ProtectBillPayments]\nON [dbo].[Transactions]\nAFTER UPDATE, DELETE" };
                for (var i = 0; i < batches.Length; i++)
                    Require(Normalize(batches[i].Groups[1].Value.Replace("''", "'", StringComparison.Ordinal))
                        .StartsWith(definitions[i], StringComparison.Ordinal), "Trigger name/event/order: " + i);
            });
            Check("History INSERT records only the fourth migration after all guards", () =>
            {
                var history = context.GetService<IHistoryRepository>().GetInsertScript(new HistoryRow(to, "10.0.13"));
                Require(System.Text.RegularExpressions.Regex.Matches(outsideCode, @"\bINSERT\s+INTO\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase).Count == 1, "Unexpected history/data INSERT.");
                var historyPosition = generated.IndexOf(Normalize(history), StringComparison.Ordinal);
                Require(historyPosition >= 0 && batches.Length == 4
                    && historyPosition > batches[3].Index + batches[3].Length, "History content/order.");
                Require(!generated.Contains(from, StringComparison.Ordinal), "Source migration must not be applied again.");
            });
            Check("Every generated command is transactional and no unreviewed SQL remains", () =>
            {
                var commands = context.GetService<IMigrationsSqlGenerator>().Generate(
                    migration.UpOperations, context.GetService<IDesignTimeModel>().Model);
                Require(commands.Count > 0 && commands.All(c => !c.TransactionSuppressed), "Suppressed transaction command.");
                var remainder = generated;
                var offset = 0;
                foreach (var command in commands)
                {
                    var text = Normalize(command.CommandText);
                    var position = generated.IndexOf(text, offset, StringComparison.Ordinal);
                    Require(position >= offset, "Missing or reordered migration command.");
                    offset = position + text.Length;
                    var removeAt = remainder.IndexOf(text, StringComparison.Ordinal);
                    Require(removeAt >= 0, "Command cannot be accounted for.");
                    remainder = remainder.Remove(removeAt, text.Length);
                }
                var history = Normalize(context.GetService<IHistoryRepository>().GetInsertScript(new HistoryRow(to, "10.0.13")));
                var historyAt = remainder.IndexOf(history, StringComparison.Ordinal);
                Require(historyAt >= 0, "History INSERT cannot be accounted for.");
                remainder = remainder.Remove(historyAt, history.Length);
                remainder = System.Text.RegularExpressions.Regex.Replace(remainder, @"(?m)^\s*GO\s*$", "");
                remainder = System.Text.RegularExpressions.Regex.Replace(remainder, @"\s+", " ").Trim();
                Require(remainder == "BEGIN TRANSACTION; COMMIT;", "Unexpected script prologue, transaction wrapper or extra SQL: " + remainder);
            });
            Check("No destructive or unrelated DDL/DML exists outside approved trigger bodies", () =>
            {
                Require(!System.Text.RegularExpressions.Regex.IsMatch(outsideCode,
                    @"\b(DROP|TRUNCATE|DELETE|UPDATE|MERGE|EXEC|EXECUTE|USE|GRANT|DENY|REVOKE|DISABLE)\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase), "Unexpected destructive/control statement.");
                var ddl = System.Text.RegularExpressions.Regex.Matches(outsideCode,
                    @"\b(?:CREATE\s+(?:UNIQUE\s+)?(?:TABLE|INDEX)|ALTER\s+TABLE)\s+\[[^\]]+\]",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase).Select(m => m.Value).ToArray();
                Require(ddl.SequenceEqual(new[] {
                    "ALTER TABLE [RecurringBills]", "CREATE TABLE [BillPaymentReceipts]",
                    "ALTER TABLE [RecurringBills]", "ALTER TABLE [RecurringBills]",
                    "CREATE UNIQUE INDEX [IX_BillPaymentReceipts_BillId_DueDate]",
                    "CREATE INDEX [IX_BillPaymentReceipts_CategoryId]",
                    "CREATE UNIQUE INDEX [IX_BillPaymentReceipts_TransactionId]",
                    "CREATE UNIQUE INDEX [IX_BillPaymentReceipts_UserId_ClientId]" }), "Unexpected structural SQL.");
                Require(!System.Text.RegularExpressions.Regex.IsMatch(outsideCode, @"\b(?:CREATE|ALTER)\s+DATABASE\b",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase), "Unexpected database DDL.");
            });
        }
        Check("Connection remained closed and no opening was attempted", () =>
        {
            Require(guard.Attempts == 0, "Database connection opening was attempted.");
            Require(string.IsNullOrEmpty(context.Database.GetConnectionString()), "Connection string changed.");
            Require(context.Database.GetDbConnection().State == System.Data.ConnectionState.Closed, "Connection is not closed.");
        });
        Console.WriteLine($"Bill migration SQL validation: {passed} passed, {failed} failed, 0 skipped; connection attempts: {guard.Attempts}. SQL was not executed.");
        Console.WriteLine(failed == 0 ? "PASS — UNSAFE TO APPLY; SQL Server behavior unverified." : "FAIL — UNSAFE TO APPLY.");
        return failed == 0 ? 0 : 1;
    }
}


