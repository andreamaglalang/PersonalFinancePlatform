# Development Administrator Provisioning

## Purpose and current status

PersonalFinance.AdminProvisioning is a separate, manually invoked .NET 10 console application. It creates a NEW local-development account with User and Administrator memberships through the existing ASP.NET Core Identity configuration.

Implementation and isolated tests are complete. The console tool has NOT been executed, no administrator account has been created, and no operation against PersonalFinanceDb has been performed during this implementation. Provisioning requires the user's separate approval.

No API startup, public registration, JWT implementation, controllers, entities, Identity tables, migrations, or database configuration were modified. No packages were added. Existing regular accounts are never promoted, reset, or changed.

## Files

Created:

- tools/PersonalFinance.AdminProvisioning/PersonalFinance.AdminProvisioning.csproj
- tools/PersonalFinance.AdminProvisioning/Program.cs
- tools/PersonalFinance.AdminProvisioning/AdministratorProvisioner.cs
- tools/PersonalFinance.AdminProvisioning/SqlProvisioningDatabase.cs
- tests/PersonalFinance.AdminProvisioningTests/PersonalFinance.AdminProvisioningTests.csproj
- tests/PersonalFinance.AdminProvisioningTests/Program.cs
- docs/Development_Admin_Provisioning.md

Modified:

- PersonalFinancePlatform.slnx: includes the console project. Tests retain the existing separately executed console-test convention.

The tool references Infrastructure, not the API executable. It calls AddInfrastructure to reuse SQL Server, UserManager<ApplicationUser>, RoleManager<IdentityRole<int>>, password policy, normalized email handling, and EF stores. Its ephemeral Data Protection provider satisfies the existing default Identity token-provider dependencies without writing a key ring. The tool does not issue password-reset tokens, access tokens, or refresh tokens.

## Safety gates

- DOTNET_ENVIRONMENT must be exactly Development. ASPNETCORE_ENVIRONMENT, if set, must also be Development.
- Run from the solution root in an interactive terminal. Redirected input/output and command-line arguments are rejected.
- Configuration precedence is API appsettings.json, appsettings.Development.json, the API project's existing user-secret store if configured, then environment variables.
- The resolved EF connection must point either to (localdb)\MSSQLLocalDB and PersonalFinanceDb using Windows Authentication, or to a disposable `OWLI_Runtime_*` catalog on any server (SQL or Windows authentication; see [portable runtime](runtime/README.md)). AttachDbFilename is always rejected. The full connection string is never printed.
- The confirmation phrase names the verified catalog: `CREATE DEVELOPMENT ADMINISTRATOR IN <catalog>`.
- A read-only live query verifies DB_NAME() matches the configured catalog; for PersonalFinanceDb it also requires SERVERPROPERTY('IsLocalDB') = 1. No server-name assumption is substituted for this connected database check.
- This read-only target check occurs before the write confirmation and is repeated immediately before the provisioning transaction.
- The operator supplies email, full name, password, and password confirmation interactively. Passwords are masked, not accepted in process arguments or configuration, and never logged.
- Exact confirmation text is required before writes: CREATE DEVELOPMENT ADMINISTRATOR IN PersonalFinanceDb
- Email and username are entered separately and trimmed. Blank/overlength usernames and characters excluded by the existing Identity policy are rejected. Identity normalizes both identifiers automatically.
- Existing normalized email or username is rejected independently. Rerunning the tool cannot promote or reset an existing account.
- Both approved roles must already exist; the tool neither creates roles nor runs migrations.
- Account creation uses UserManager.CreateAsync and AddToRolesAsync for User and Administrator.
- All Identity writes use the same scoped ApplicationDbContext within one serializable EF transaction. Failure rolls back the new account and memberships. No existing account is deleted as compensation.
- New users use PHP, UTC creation time, and lockout enabled. Email confirmation is not fabricated.
- Logging providers are disabled for this console process. Unexpected errors produce a generic message, not SQL/exception/credential details.

No writes happen merely by building the solution. Normal API startup has no reference to the provisioning entry point.

## How to run — only after explicit provisioning approval

Run these commands yourself in an interactive PowerShell terminal from the solution root AFTER approving creation of the new development account:

~~~powershell
Set-Location '<repository root>'
$env:DOTNET_ENVIRONMENT = 'Development'
$env:ASPNETCORE_ENVIRONMENT = 'Development'
dotnet run --project tools/PersonalFinance.AdminProvisioning/PersonalFinance.AdminProvisioning.csproj
~~~

Do not supply email, password, or tokens as command-line arguments. Do not paste secrets into checked-in files.

The tool reads the existing development connection settings and environment/user-secret overrides. Unexpected overrides cause refusal, not connection to another approved-looking target. Windows access to the existing LocalDB database is required. The tool does not create the database or apply InitialFinanceSchema.

After the target is verified:

1. Enter an email distinct from both existing regular accounts.
2. Enter a separate username (up to 256 characters), then a full name (up to 100 characters).
3. Enter and confirm a unique strong password. Identity requires at least 12 characters, uppercase, lowercase, a digit, a nonalphanumeric character, and four distinct characters; this tool also limits passwords to 128 characters.
4. Review the target and displayed trimmed email, username, and full name before confirmation.
5. Type the exact confirmation phrase shown above. Any other input aborts.
6. Success prints the new integer ID and assigned roles. It does not print a password or token.

Press Escape at a password prompt to cancel. A mismatch or validation failure stops without creating an account. On duplicate email, choose a genuinely new email in a subsequent invocation; never repurpose either regular account.

If a commit suffers an ambiguous connection failure, the tool reports that no success was confirmed. Check the newly requested email through an authorized read-only inspection before retrying. The tool never resets an existing account on retry.

The provisioning tool was not executed during implementation.

## Administrator category verification after provisioning

Use the existing HTTPS launch profile and log in through POST /api/auth/login. Keep the returned JWT private and use the Authorization: Bearer header.

1. GET /api/auth/me must return the new ID and both roles. Check that each original regular account still has only User.
2. As anonymous, POST /api/categories/defaults must return 401.
3. As either regular user, POST, PUT, and DELETE on the defaults routes must return 403.
4. As the administrator, POST /api/categories/defaults with a unique name:

~~~json
{
  "name": "Administrator verification UNIQUE_SUFFIX",
  "type": "Expense",
  "colorHEX": "#336699",
  "isActive": true
}
~~~

Expect 201 and isSystemDefault=true. Save the returned categoryId.
5. PUT /api/categories/defaults/{categoryId} with a renamed value, the same Expense type, and isActive=false. Expect 200.
6. GET /api/categories/{categoryId} confirms the edit. GET /api/categories?includeInactive=true includes the archived category.
7. DELETE /api/categories/defaults/{categoryId} for this NEW, unreferenced test category returns 204; GET afterwards returns 404.
8. Administrator access to another account's private category must return 404. Ordinary /api/categories/{id} writes to a system category remain 403; use the defaults routes.
9. Referenced system categories must remain protected: deletion or a type change returns 409. Use dedicated test data when checking this, preserving existing financial history.

The current category service rejects duplicate names of the same type within the same owner/system scope. Use a unique test name. No existing category behavior was changed by this tool.

## Build and tests

Executed:

~~~powershell
dotnet build PersonalFinancePlatform.slnx
dotnet run --project tests/PersonalFinance.AdminProvisioningTests
dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore
~~~

Results:

| Verification | Result |
| --- | --- |
| Complete four-project solution build | PASS: zero warnings, zero errors |
| Focused provisioning checks | PASS: 20 passed, zero failed |
| Existing schema checks | PASS: 44 passed, zero failed; no database connections |
| Existing API security suite | INCOMPLETE: 2 passed, 21 failed during fixture setup because Microsoft.AspNetCore.Identity.UI 10.0.14 could not be loaded |
| API security dependency restore retry | BLOCKED: NU1301, socket permission denied for api.nuget.org |
| Real SQL transaction/rollback and live target verification | NOT EXECUTED |
| Interactive password/confirmation behavior in a live console | Implemented and inspected; NOT manually executed |
| Administrator provisioning | NOT EXECUTED |
| Migrations/database changes | NONE |

The API security failures are not an authorization pass/fail verdict: fixtures could not start due to a missing runtime assembly in that test project's resolved output. The API project already referenced Identity.UI 10.0.14 before this work. No API package references were changed. Restore that test project locally and rerun it before considering the full regression suite verified.

The provisioning suite invokes the reusable provisioning class, not the console entry point. It uses real Identity normalization/password validation/hash generation with test-only in-memory Identity stores and a transaction test double. Checks cover environment and connection-target restrictions, confirmation rejection, successful assignment of both roles, rejection of an existing normalized email while preserving two regular test accounts, weak/mismatched passwords, and rollback after role-assignment or simulated precommit failure.

Rollback checks verify the provisioning control flow with a transaction double; they do NOT certify actual SQL Server rollback, isolation, or concurrency. These need a separately approved, isolated SQL integration environment. No tests connect to PersonalFinanceDb.

Development fixes made during implementation: corrected the user-secrets overload used by the console configuration builder; supplied an ephemeral Data Protection provider required by the existing Identity default token providers. The final build and focused tests pass.

## Retaining or removing the tool

It is safe to retain the source as a development utility. Do not deploy/publish its executable with the API. Publish the API project explicitly rather than publishing every solution project. The API does not reference or execute the tool.

To remove it later, remove its project entry from the solution and remove its tools and test directories and this document through your normal source-control workflow. No migration is needed. Removing source or binaries does not remove an already-created database account or revoke issued sessions.

Any later account disablement, role removal, session revocation, or deletion is a separate approved operation through Identity. Do not directly delete database rows or modify either regular test account. Retaining the tool does not automatically create additional administrators.

## Separate username and demo selection

Selected identifiers: full name Admin Demo, email admin.demo@demo.test, username admin.demo. Identity normalizes email to ADMIN.DEMO@DEMO.TEST and username to ADMIN.DEMO independently. API login remains email-based. No migration or authentication-policy change is required. Account creation remains separately authorized; this documentation does not authorize provisioning.
