# Phase 4 Development Connection Preparation

Date: October 31, 2026

The API and EF design-time factory are configured for the approved development target. No migration was applied and no database/schema-changing SQL was executed.

## Configuration

Only PersonalFinance.Api/appsettings.Development.json contains the local connection:

    Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Trusted_Connection=True;Encrypt=True;TrustServerCertificate=True;

The JSON file escapes the backslash as required by JSON. Windows Authentication is used; no username, password, or production credentials were added. Existing logging settings were preserved. Base appsettings.json and launchSettings.json were unchanged.

The existing launch profiles already set ASPNETCORE_ENVIRONMENT=Development. Production does not inherit the development LocalDB connection. Production configuration must be supplied separately through approved deployment configuration.

## Changed files

- PersonalFinance.Api/appsettings.Development.json — added ConnectionStrings:DefaultConnection.
- PersonalFinance.Api/Persistence/ApplicationDbContextFactory.cs — loads the API's normal environment-specific configuration and uses DefaultConnection without opening it.
- tests/PersonalFinance.SchemaValidation/Program.cs — kept metadata verification independent of connection configuration and added seven connection/configuration checks.
- docs/Phase4_Connection_Preparation_Report.md — this report.

No package references, project references, entity classes, Identity policy, DbContext schema mappings, migrations, model snapshots, or existing migration SQL were changed.

## Design-time behavior

The factory now uses WebApplication.CreateBuilder with the API assembly name and forwarded tooling arguments. This loads base/environment JSON, development user secrets when configured, environment variables, and command-line overrides using normal API configuration behavior.

It locates configuration when invoked from the solution root, the API project directory, or the compiled output directory. It returns a context without opening a database connection. If no connection is configured in another environment, it still allows offline model inspection.

The existing Infrastructure runtime registration already reads DefaultConnection, so it did not require changes.

Connection configuration and design-time factory behavior follow Microsoft's documentation:
- https://learn.microsoft.com/en-us/aspnet/core/fundamentals/configuration/?view=aspnetcore-10.0
- https://learn.microsoft.com/en-us/ef/core/cli/dbcontext-creation

For conventional EF tooling, explicitly select Development:

    dotnet ef dbcontext info --project PersonalFinance.Infrastructure --startup-project PersonalFinance.Api -- --environment Development

The matching cached EF 10.0.13 tool was used through dotnet exec with the API deps/runtimeconfig and Infrastructure assembly, because the pinned local tool's restoration was previously blocked. No package/tool changes were made.

Actual dbcontext info output:

    Type: PersonalFinance.Infrastructure.Persistence.ApplicationDbContext
    Provider name: Microsoft.EntityFrameworkCore.SqlServer
    Database name: PersonalFinanceDb
    Data source: (localdb)\MSSQLLocalDB
    Options: EngineType=SqlServer

The effective connection settings were separately verified:
- Windows integrated security: true.
- Encryption: true.
- TrustServerCertificate: true.
- No SQL username/password.
- Constructing the context leaves the connection closed.

EF adds an Application Name diagnostic keyword to its effective connection string. The development JSON retains the exact approved string. Initial tests using raw connection-string equality were corrected to compare effective server/database/authentication/encryption settings rather than incidental provider-added diagnostics.

## Read-only database existence check

A temporary validation executable obtained the connection from the updated factory, changed only InitialCatalog to master for the check, and attempted a connection with a five-second timeout and retries disabled.

The sole planned SQL statement was:

    SELECT name, state_desc
    FROM sys.databases
    WHERE name = @databaseName;

The parameter value was PersonalFinanceDb. No target database creation, attachment, migration, or schema/data modification operation was requested.

Connection opening failed before the SELECT executed:

    SQL Network Interfaces, error: 50 - Local Database Runtime error occurred.
    Cannot create an automatic instance. See the Windows Application event log for error details.

Result: database existence/state could not be determined from the agent's Windows execution session. This does not establish that PersonalFinanceDb is missing, and does not invalidate the user's successful SSMS connection. No SQL Server instance creation/start commands were issued and no retries or schema-changing workarounds were attempted.

## Build and tests

Executed:

    dotnet build PersonalFinancePlatform.slnx

Result: PASS, zero warnings and zero errors.

Executed:

    dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore

Result: PASS, 44 passed and zero failed. This includes the original 37 schema/policy checks, with the original offline-provider check retained independently of the factory, plus seven relevant checks:

1. Development factory resolves the approved LocalDB settings without opening a connection.
2. Production does not inherit the Development connection.
3. Environment connection overrides work.
4. Command-line overrides take precedence over environment configuration.
5. Factory resolves configuration from the API project directory.
6. Runtime DI reads the same development JSON connection.
7. Factory resolves copied configuration outside the source working directory.

The configuration tests do not open database connections. The separate existence probe was the only attempted SQL connection.

EF migrations has-pending-model-changes was executed using the Development factory and reported:

    No changes have been made to the model since the last migration.

No migration commands that apply or revert database changes were run.

## Preservation verification

SHA-256 comparisons against 51 existing source/configuration/report/SQL files showed only the three intended existing-file modifications listed above. Existing migration files, designer, snapshot, FinanceSchemaGuards, and docs/InitialFinanceSchema.sql remained unchanged.

Also preserved: API Program.cs, HTTPS redirection, OpenAPI registration, controller routing, Identity registration, appsettings.json, launch profiles, package references, solution/project configuration, and Domain/Infrastructure application code.

The build used the existing process-local temporary APPDATA/package-cache settings and NuGetAudit=false to avoid the previously established sandbox NuGet configuration/network restrictions. This did not modify project configuration. No new online vulnerability audit is claimed by this connection-preparation task.

## Remaining items

- Database existence remains unverified because the agent could not connect to LocalDB. It can be checked under the user's SSMS session using the read-only query above.
- The initial schema remains unapplied.
- Explicit user approval is required before applying InitialFinanceSchema to PersonalFinanceDb.

Connection preparation is complete. No database was created, deleted, or modified, and no migration was applied.

