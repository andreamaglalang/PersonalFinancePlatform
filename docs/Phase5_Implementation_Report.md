# Phase 5 Authentication and Authorization Implementation Report

Date: October 31, 2026

Authentication implementation is complete and builds successfully. Six endpoints, Identity account handling, signed JWTs, rotating refresh tokens, replay revocation, authorization policies, ownership helpers, and endpoint protections are implemented.

**Verification: 94 automated checks passed, zero failed. The final solution build has zero warnings and zero errors.** These results include in-memory service and HTTP middleware tests, not successful SQL Server integration tests.

No migration was required or generated. Existing migrations and SQL remain unchanged. No connection to PersonalFinanceDb was opened, no development records were modified, and no migration/database update was executed. The user's confirmation that InitialFinanceSchema has already been applied is the current database-status authority; older reports describe the earlier preparation state.

## Reviewed implementation and authority

Read the actual v3.1 DOCX authentication, finalized-policy, refresh-token, ownership, and functional-requirement content; Phase3_Implementation_Report.md; Phase3_Final_Verification.md; Phase4_Connection_Preparation_Report.md; the solution/project references; Program.cs; existing Identity models/configurations; DbContext/factory; and automated checks.

Preserved the three-project dependency structure, integer-key Identity, all financial entities/constraints/relationships, the development LocalDB connection, launch profiles, HTTPS redirection, controller routing, and OpenAPI support.

## Package changes

One direct package reference was added:

| Project | Package | Version |
| --- | --- | --- |
| PersonalFinance.Api | Microsoft.AspNetCore.Authentication.JwtBearer | 10.0.13 |

Existing EF Core, Identity EF, OpenAPI, and the Pkcs override remain at 10.0.13. The resolved API graph now uses Microsoft.IdentityModel.* and System.IdentityModel.Tokens.Jwt 8.19.2 through JwtBearer. No test-framework, database-provider, or unrelated packages were added.

The initial restore was blocked because the package was not cached and the sandbox could not reach api.nuget.org. The package subsequently became available in the local cache; restore and build then succeeded. Commands used process-local APPDATA/package-cache settings and NuGetAudit=false for offline builds, without changing NuGet configuration in the repository.

The online transitive vulnerability audit was attempted and reported forbidden socket access to api.nuget.org:443. **The online audit remains incomplete; this report does not certify a vulnerability-free dependency graph.**

## Implemented endpoints

| Endpoint | Authorization | Behavior |
| --- | --- | --- |
| POST /api/auth/register | Anonymous, HTTPS, rate limited | Required FullName/Email/Password/ConfirmPassword; Identity validation, normalized unique email, User role only; returns 201 and safe user projection. |
| POST /api/auth/login | Anonymous, HTTPS, rate limited | Email/password authentication with Identity verification and lockout; generic failure response; returns access/refresh tokens and safe user projection. |
| POST /api/auth/refresh | Anonymous, refresh-token possession, HTTPS, rate limited | Validates stored hash, expiry, revocation, family state and account lockout; consumes predecessor and inserts replacement in one transaction. |
| POST /api/auth/logout | Anonymous, refresh-token possession, HTTPS, rate limited | Closes only the submitted token's session family; returns 204 for known or unknown well-formed tokens, including repeated logout. |
| POST /api/auth/logout-all | Valid JWT, HTTPS, rate limited | Uses validated subject ID to revoke all refresh sessions for that user only; returns 204. |
| GET /api/auth/me | Valid JWT, HTTPS, rate limited | Returns only Id, FullName, Email, BaseCurrency, and roles. |

Registration does not automatically log in; callers explicitly use login afterward. Anonymous refresh is intentional so expired access tokens can be renewed. Logout also accepts an old rotated token to close its own session family without affecting another family. Invalid request shape/length receives normal safe 400 validation; unknown well-formed token validity is not disclosed by logout.

No finance controllers, administrator-registration endpoint, MVC app, Android implementation, or Hangfire jobs were added. The administrator-only endpoint used by tests is compiled exclusively into the test executable and is not part of the API.

## Identity and registration

- Uses the existing ApplicationUser : IdentityUser<int> and IdentityRole<int>.
- UserManager performs account creation, password hashing/verification, email normalization, role assignment, failed-attempt updates, and lockout checks.
- Email is trimmed before normalization and lookup. The existing required, case-insensitive unique normalized-email database index remains the final concurrent uniqueness guard.
- Passwords require 12–128 characters at the request boundary; Identity requires uppercase, lowercase, a digit, a nonalphanumeric character, and four distinct characters.
- New accounts have lockout enabled. Five failed attempts cause a 15-minute Identity lockout.
- Unknown-email and locked-out login paths perform a dummy Identity password-hash verification to reduce obvious timing differences. Login errors do not disclose account existence or lockout status.
- Registration uses a transaction and a normalized-email-derived logical lock, so user creation and User-role assignment succeed or roll back together. Unique-key conflicts receive a safe duplicate-email result.
- Existing migration-owned HasData role definitions remain User and Administrator. EF migration history makes their application one-time/idempotent. No new startup role seeding, schema application, or credential creation occurs.
- Registration checks that User exists and returns a safe 503 if role/schema setup is incomplete. It does not silently repair the database.
- ApplicationUser has no inactive-account flag, so no undocumented status column or schema change was introduced. Identity lockout is enforced.
- No password hashes, security stamps, concurrency stamps, or persisted token hashes are returned in DTOs.

## JWT design and configuration

JWTs use HMAC-SHA256, a mandatory externally configured signing key, issuer/audience/signature/lifetime validation, required signatures/expiration, algorithm restriction, and zero clock skew. Access lifetime is fixed at 15 minutes.

Claims contain:
- sub: invariant positive integer user ID.
- role: current Identity roles.
- jti: a new unique token identifier.
- iat, nbf, exp, issuer, and audience.

Inbound claim remapping is disabled; authenticated ID extraction uses validated sub. A token with an invalid subject is rejected. JWT middleware also rejects missing/locked accounts and tokens whose role claims no longer match current Identity membership.

Committed appsettings.json contains only nonsensitive defaults:

    Jwt:Issuer = PersonalFinance.Api
    Jwt:Audience = PersonalFinance.Clients

Set Jwt__SigningKeyBase64 through environment configuration. It must decode to at least 32 cryptographically random bytes; 64 bytes are recommended. Missing/invalid keys fail startup validation. No signing-key value, production credential, or default administrator account was committed.

The API does not fetch remote signing metadata or accept unsigned JWTs. JwtBearer uses the configured signing key and validates HTTPS metadata settings.

Refresh logout/replay revokes refresh sessions. Already issued access JWTs are not stored in a revocation table and can remain usable until their 15-minute expiration unless account lockout or role changes invalidate them sooner. This matches the approved refresh-revocation design and is explicitly documented for clients.

## Refresh tokens, atomicity, and concurrency

Refresh tokens contain 64 CSPRNG bytes (512 bits), encoded as 86-character unpadded base64url strings. Only uppercase SHA-256 hashes are stored. Each login creates a new family; rotated replacements retain that family and expire seven days after their issuance. Revoked predecessors are retained indefinitely by this implementation for reuse detection; no cleanup/deletion job was added.

SqlAuthSessionStore uses existing schema fields and SQL Server primitives:

1. Begin an EF database transaction.
2. Acquire sys.sp_getapplock with an exclusive, transaction-owned per-user resource.
3. Re-read the token after acquiring the lock.
4. Check revocation, expiry, terminal-family revocation, and account availability.
5. Consume the predecessor with a conditional UPDATE requiring RevokedAtUtc IS NULL and ExpiresAtUtc > now; set its revocation time and replacement hash.
6. Insert the replacement hash in the same transaction.
7. Commit before returning tokens. Exceptions roll back the coupled writes.

Login session creation, rotation, logout, and logout-all share the same per-user logical lock across independent contexts/API processes. Registration has its own normalized-email resource. The extra conditional UPDATE prevents double consumption even in addition to the lock.

Reusing any revoked token returns 401 and revokes active descendants in that family. The revocation transaction commits despite the rejected response. A revoked terminal token acts as a closed-family marker. Ordinary logout and logout-all use the same lock and revoke only their intended scopes.

Strict replay behavior is intentional: two concurrent refresh attempts cannot both consume the same token. The loser triggers replay handling, which may invalidate the replacement returned to the winner. Clients must serialize refresh attempts and retain one token per session rather than parallelize/retry old tokens after rotation.

**No migration is needed:** the existing unique hash index, family identifier, expiry/revocation metadata, and replacement hash support this conditional-update/transaction strategy. No RefreshToken rowversion or new table is necessary. Live SQL Server lock/rollback behavior still requires the opt-in integration tests below.

## Authorization, transport, and error handling

- Thin controllers delegate to authentication and refresh-token service interfaces.
- Authenticated endpoints use Authorize. Named User/Administrator policies require the corresponding Identity role.
- Administrator-only operations should use Authorize(Roles = ApplicationRoles.Administrator), as exercised by the test-only authorization controller. No unrelated administrator business operation was invented.
- ICurrentUser parses the validated subject and exposes Owns(resourceOwnerId). Administrator membership is deliberately not an ownership bypass. Future financial services must load the owner from the database and compare it, never trust a client-supplied UserId.
- Registration/login/refresh DTOs disallow unmapped JSON members; role/user-ID spoofing in registration is rejected before service invocation. Logout-all always derives its owner from claims, not its request body.
- Authentication routes reject non-HTTPS requests rather than redirecting credential submissions; all authentication query strings are rejected. Passwords/refresh tokens use JSON bodies; access JWTs use Authorization headers.
- Auth responses, including failures, set no-store/no-cache headers. Auth bodies are limited to 16 KiB.
- Authentication endpoints have a fixed-window limit of 20 requests per minute per remote IP, with no queue. Counters are per API process; multi-instance/distributed deployment would require a coordinated limiter. No untrusted forwarded-header scheme or permissive CORS was added.
- Middleware order includes safe exception handling, transport checks, HTTPS redirection, routing, rate limiting, authentication, then authorization. HSTS is enabled outside Development.
- Safe exception responses omit stack traces and database/provider details. The application exception logger records only exception type. Hosting request-query logging is filtered to Warning; request bodies/tokens/passwords are not logged by application code. EF sensitive-data logging is not enabled.
- OpenAPI remains available in Development and now includes typed success-response metadata for auth operations.

The future MVC portal must keep API credentials server-side behind its secure HttpOnly browser session, without putting refresh tokens in browser cookies/scripts. Android must use Keystore-backed protected credential storage and serialize refresh/sync attempts. Neither client authentication implementation was added here.

## Automated test results

| Suite/action | Actual result | Scope |
| --- | --- | --- |
| dotnet build PersonalFinancePlatform.slnx | PASS; 0 warnings, 0 errors | All three production projects |
| AuthenticationTests console suite | 27 passed, 0 failed | Real UserManager/password/lockout components, isolated in-memory Identity/session stores |
| ApiSecurityTests console suite | 23 passed, 0 failed | Real MVC, JwtBearer, routing, authorization and rate-limit middleware; in-memory persistence, no listening socket |
| Existing SchemaValidation suite | 44 passed, 0 failed | Schema, constraints, roles, policies, configuration and migration metadata |
| Real-process HTTP startup smoke | PASS | OpenAPI returned 200; HTTP /api/auth/me returned 400 because HTTPS is required; no credentials or database-writing requests |
| Real-process HTTPS startup smoke | NOT SUCCESSFUL in agent environment | Normal developer certificate unavailable; temporary test PFX import also failed with CryptographicException |
| SQL integration mode | SKIP | No isolated SQL test connection configured; no SQL connection opened |
| EF pending-model check | PASS | No model changes since InitialFinanceSchema |
| Fresh generated SQL versus saved script | PASS | Exact text match |
| Online vulnerability audit | INCOMPLETE | NuGet network access blocked |

Total: **94 passing automated checks** across the three suites. These are console executables; their actual command is dotnet run, not dotnet test. The real-process HTTP smoke is reported separately, not counted as a SQL or TLS success.

### Required authentication test traceability

| Requested scenario | Coverage |
| --- | --- |
| Successful registration | Service and HTTP fixture registration through actual MVC/UserManager |
| Duplicate email | Service normalized-email duplicate rejection |
| Invalid registration | Service and HTTP validation |
| Password complexity | Real Identity validator, service and HTTP |
| Successful login | Service and HTTP login |
| Invalid password | Generic credentials rejection compared with unknown email |
| Lockout | Five failures and correct-password rejection; failed-count reset test |
| Valid JWT | Actual JwtBearer authentication of /me |
| Expired JWT | Actual middleware rejection with zero skew |
| Invalid signature | Actual middleware rejection |
| Refresh rotation | Service link/hash checks and HTTP rotation |
| Expired refresh | Service test using controlled clock |
| Revoked refresh | Service rejection after logout |
| Replay | Service and HTTP predecessor reuse |
| Family revocation | Descendants invalidated; unrelated family preserved |
| Concurrent refresh | Service orchestration test with serialized in-memory store; independent-context SQL scenario supplied but not executed |
| Logout | Idempotence, family isolation, old-token logout |
| Logout-all | Own-session scope, other-user preservation, authenticated HTTP boundary |
| Unauthorized /me | Actual middleware 401 |
| Authorized /me | Actual middleware/controller projection and no-store |
| User authorization | Named User policy exercised |
| Administrator restrictions | User gets 403; Administrator test role gets 200; anonymous gets 401 |
| Ownership spoof prevention | Strict registration DTO, claim-derived logout-all, Owns helper with no administrator bypass |
| Sensitive response fields | Exact current-user projection and HTTP payload field checks |

Extra checks cover unknown/unsigned/wrong-issuer/wrong-audience tokens, account lockout and stale roles during JWT validation, missing signing configuration, query-string rejection, HTTPS requirements, and rate limiting.

### Opt-in SQL integration tests

SqlIntegration.cs contains four scenarios using production SqlAuthSessionStore and independent EF scopes:
1. Concurrent refresh consumption and replay closure.
2. Family revocation without revoking another session.
3. Rollback of predecessor consumption plus replacement insertion on injected failure.
4. Logout-all racing refresh.

The harness refuses PersonalFinanceDb and requires an already-provisioned database whose name starts with PersonalFinanceAuthTests_. It does not create/drop databases, apply migrations, create schema, or clean up existing records. Test users/records are inserted only when an operator explicitly runs SQL mode with a valid isolated connection; records are retained.

These SQL scenarios were **not executed**. The SQL-mode command was invoked with its environment connection unset and correctly printed SKIP.

## Environment/startup limitations

Initial real-host startup hit Windows EventLog access restrictions. A process-only logging filter avoided that restriction during smoke checking, without changing committed logging configuration.

HTTPS startup under this agent identity could not load the default developer certificate. A temporary self-signed test certificate was generated without installing trust, but the child process could not import its PFX in this Windows profile. The temporary PFX was deleted. This is not evidence that the user's normal Visual Studio HTTPS profile is broken.

A separate real-process HTTP-only smoke verified host startup and HTTPS rejection without weakening application code or submitting credentials over HTTP. Temporary signing keys were generated in process memory and were not printed or persisted to committed configuration. The smoke child process was stopped after checking OpenAPI and anonymous /me. No authentication operation that writes a database was executed against the real application.

## Manual verification instructions

Run under the normal Windows account that can access LocalDB and the development HTTPS certificate. Keep the existing applied database; do not run database update or reapply InitialFinanceSchema.

### Configure a development signing key

In the same PowerShell session that will start the API:

    $jwtKeyBytes = New-Object byte[] 64
    $jwtRng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $jwtRng.GetBytes($jwtKeyBytes)
    $jwtRng.Dispose()
    $env:Jwt__SigningKeyBase64 = [Convert]::ToBase64String($jwtKeyBytes)
    dotnet run --project PersonalFinance.Api --launch-profile https

Do not print or commit the key. Configure a stable secret outside the repository for repeated development sessions; rotating the signing key invalidates older access JWTs. The example generates a temporary development key, not a production provisioning procedure. Production issuer/audience/key values must be provided securely through deployment configuration.

If the normal development certificate is missing, configure/trust it under your own Windows account using the standard dotnet dev-certs workflow. Do not disable certificate validation for real API use.

### Exercise endpoints over HTTPS

Use https://localhost:7027 and a dedicated ordinary test account with a strong password you choose. Manual registration/login intentionally writes that test user's Identity/refresh records; no such development write was performed by this agent.

1. Register with FullName, Email, Password, ConfirmPassword. Expect 201 and only safe user fields. Duplicate the email with different casing; expect rejection.
2. Login with email/password in a JSON body. Store the returned accessToken/refreshToken securely in the API client; do not paste them into URLs, logs, screenshots, or committed files.
3. GET /api/auth/me with Authorization: Bearer <accessToken>. Expect the matching user and User role. Without the header, expect 401.
4. POST /api/auth/refresh with the refreshToken JSON field. Replace both client tokens atomically with the response.
5. In a deliberately separate replay test session, reuse a consumed refresh token. Expect 401 and subsequent rejection of that family's latest token. Other login sessions should remain renewable.
6. POST /api/auth/logout with that session's refresh token. Expect 204 on repeated submissions. Other sessions remain unaffected.
7. POST /api/auth/logout-all with a valid access JWT. Expect 204; all refresh sessions for that user should stop renewing.
8. Five incorrect-password attempts should cause Identity lockout. Wait the configured period or use a separately approved administrative test procedure; no public lockout-bypass/admin endpoint was added.
9. Review /openapi/v1.json for the six endpoints and request/response DTOs. There is no new Swagger UI package.
10. Do not use the in-memory test-only administrator controller URL against the real API; it is not deployed.

### Rerun automated checks

    dotnet run --project tests/PersonalFinance.AuthenticationTests
    dotnet run --project tests/PersonalFinance.ApiSecurityTests
    dotnet run --project tests/PersonalFinance.SchemaValidation

For separately approved SQL integration, provision the disposable schema outside this task, set FINANCE_AUTH_SQL_TEST_CONNECTION securely to its PersonalFinanceAuthTests_* database, then run:

    dotnet run --project tests/PersonalFinance.AuthenticationTests -- --sql

No SQL integration command should target PersonalFinanceDb. No schema application occurs in this harness.

Optional startup checks:

    dotnet run --project tests/PersonalFinance.ApiSecurityTests -- --startup-smoke
    dotnet run --project tests/PersonalFinance.ApiSecurityTests -- --startup-smoke-http

The first uses a temporary pinned TLS certificate; its certificate/profile limitation in this agent session is described above. The HTTP check sends no credentials and expects authentication routes to refuse HTTP.

When network access permits, rerun:

    dotnet list PersonalFinancePlatform.slnx package --vulnerable --include-transitive

## Created files

- PersonalFinance.Api/Authentication/JwtSettings.cs
- PersonalFinance.Api/Authentication/JwtTokenIssuer.cs
- PersonalFinance.Api/Authentication/CurrentUser.cs
- PersonalFinance.Api/Authentication/AuthenticationConfiguration.cs
- PersonalFinance.Api/Authentication/AuthTransportMiddleware.cs
- PersonalFinance.Api/Authentication/SafeExceptionHandler.cs
- PersonalFinance.Api/Controllers/AuthController.cs
- PersonalFinance.Infrastructure/Authentication/Abstractions.cs
- PersonalFinance.Infrastructure/Authentication/AuthRules.cs
- PersonalFinance.Infrastructure/Authentication/AuthenticationService.cs
- PersonalFinance.Infrastructure/Authentication/DummyPasswordVerifier.cs
- PersonalFinance.Infrastructure/Authentication/RefreshTokenService.cs
- PersonalFinance.Infrastructure/Authentication/SqlAuthSessionStore.cs
- PersonalFinance.Infrastructure/Authentication/Contracts/AuthRequests.cs
- PersonalFinance.Infrastructure/Authentication/Contracts/AuthResponses.cs
- tests/PersonalFinance.AuthenticationTests/PersonalFinance.AuthenticationTests.csproj
- tests/PersonalFinance.AuthenticationTests/Program.cs
- tests/PersonalFinance.AuthenticationTests/TestDoubles.cs
- tests/PersonalFinance.AuthenticationTests/SqlIntegration.cs
- tests/PersonalFinance.ApiSecurityTests/PersonalFinance.ApiSecurityTests.csproj
- tests/PersonalFinance.ApiSecurityTests/ApiFixture.cs
- tests/PersonalFinance.ApiSecurityTests/Program.cs
- tests/PersonalFinance.ApiSecurityTests/TestAuthorizationController.cs
- tests/PersonalFinance.ApiSecurityTests/StartupSmoke.cs
- docs/Phase5_Implementation_Report.md

## Modified files

- PersonalFinance.Api/PersonalFinance.Api.csproj — JwtBearer 10.0.13 reference.
- PersonalFinance.Api/Program.cs — authentication/security service registration, safe errors/log filtering, transport and middleware ordering.
- PersonalFinance.Api/appsettings.json — nonsensitive issuer/audience defaults only.
- PersonalFinance.Infrastructure/DependencyInjection.cs — Identity password/lockout configuration and authentication services.
- PersonalFinance.Infrastructure/Identity/RefreshToken.cs — documentation comment only; fields/schema unchanged.
- PersonalFinance.Infrastructure/Identity/RefreshTokenHash.cs — documentation comment only; SHA-256 implementation unchanged.

## Preservation and remaining issues

SHA-256 checks confirmed existing migrations, designer, model snapshot, FinanceSchemaGuards, saved SQL, all Domain financial code, development connection settings, launch profiles, DbContext/factory, project references, and previous reports were unchanged. EF reports no model drift; freshly generated inspection SQL exactly matches docs/InitialFinanceSchema.sql.

Remaining verification:
- SQL transaction/concurrency/rollback integration is pending an explicitly configured isolated test database.
- End-to-end HTTPS startup must be checked under the user's working Windows/certificate profile.
- The online transitive vulnerability audit is incomplete because network access is blocked.
- A signing key must be configured before normal API startup; no key was committed or left configured by this task.

**Phase 5 is ready for manual development API testing after configuring the signing key and using the normal working HTTPS/LocalDB environment.** It has not been certified by live SQL integration or a complete online audit. No migration is required, no database was recreated or modified, no administrator credentials were created, and no migrations were applied.

