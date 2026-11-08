# Phase 5 Authorization Verification

Review date: October 31, 2026  
Result: **PASS with test-coverage warnings. Authorization is ready as the foundation for subsequent financial API development.**

This was a read-only application/database review. The only repository file created is this report. No application or test source was edited, no public endpoint added, no administrator account created, no existing account membership changed, and no database connection or migration operation was executed.

## Verification scope and evidence levels

The user reports successful manual SQL-backed verification of registration/persistence, login/JWT generation, authenticated and unauthenticated /me, refresh rotation, replay rejection, family revocation, logout, and logout-all across two sessions. Those results are accepted as **user-reported manual results**, not rerun or independently observed in this review.

This review independently performed:

- Source inspection of Identity configuration/seeding, registration, JWT issuance/validation, policies, controllers, ownership helpers, and test isolation.
- Existing authentication service and actual HTTP middleware tests using isolated in-memory persistence.
- Existing schema/model checks, including Identity role seed metadata.
- A temporary, unpublished probe of the production named policies and malformed-subject handling, using synthetic ClaimsPrincipal objects.

No SQL integration tests or administrator-role SQL checks were executed. Passing in-memory checks are not reported as actual SQL Server integration results.

## Findings against the requested checks

| Check | Result | Evidence |
| --- | --- | --- |
| 1. Identity roles and seeding | PASS | Identity uses IdentityRole<int>, EF stores, and the two approved role definitions: User and Administrator. Migration/model seeds contain no user accounts. |
| 2. Registration grants User only | PASS | AuthenticationService.RegisterAsync explicitly calls AddToRoleAsync(user, ApplicationRoles.User), with no client-selected role. Existing service tests verify exactly one returned User role. |
| 3. Public Administrator self-registration prevented | PASS | RegisterRequest has no role/UserId property, disallows unmapped JSON members, and registration assigns only User. Service/HTTP tests reject attempted overposting and verify no public Administrator membership. |
| 4. JWT role claims and policies | PASS | Roles come from UserManager.GetRolesAsync, are issued as role claims, and RoleClaimType is role. Both policies require authenticated users and their exact role. |
| 5. User cannot satisfy Administrator policy | PASS | Existing actual-middleware test returns 403 for a valid User JWT at the test-only administrator operation; direct production named-policy evaluation also denies User. |
| 6. Valid administrator claims accepted | PASS, automated scope | Existing actual JwtBearer/MVC test accepts a signed token whose test-store membership includes Administrator. The temporary probe directly confirms the named Administrator policy accepts an authenticated Administrator-only principal. No real administrator account was created or exercised. |
| 7. Ownership derives from validated identity | PASS | CurrentUser reads authenticated sub, parses a positive integer, and compares it with a server-supplied owner ID. It does not read body/query UserId and grants no Administrator bypass. Controllers derive /me and logout-all targets from it. |
| 8. Existing automated authorization tests | PASS | 27 service checks and 23 API-security checks passed. An additional 44 existing schema/support checks passed. |
| 9. Missing/insufficient tests identified | WARNING | Permanent named-policy matrix, role-removal/forged-role regressions, malformed JWT subjects, test-double role mapping, SQL role persistence, and future financial cross-user endpoint tests need additional coverage. Detailed below. |

No production authorization defect or blocking FAIL was identified.

## Identity role configuration and registration

Relevant source:

- PersonalFinance.Infrastructure/Identity/ApplicationRoles.cs:5–6 defines User and Administrator.
- PersonalFinance.Infrastructure/DependencyInjection.cs registers AddIdentityCore<ApplicationUser>, AddRoles<IdentityRole<int>>, and EF stores.
- PersonalFinance.Infrastructure/Persistence/Configurations/IdentityConfigurations.cs:33–40 seeds only the two approved role definitions.
- PersonalFinance.Infrastructure/Migrations/20261031023901_InitialFinanceSchema.cs:465–470 contains the corresponding role insert.
- tests/PersonalFinance.SchemaValidation/Program.cs:277–282 checks the approved seed names and absence of seeded user accounts.

Role seeding remains migration-owned HasData; migration history controls one-time application. This is not a runtime role-repair service. Registration returns a safe unavailable result if the User role is missing rather than automatically modifying the schema or creating privileged accounts.

AuthenticationService.cs:34 checks that User exists; line 48 explicitly assigns User after account creation. If role assignment fails, the enclosing registration transaction must roll back. There is no branch that assigns Administrator during public registration.

Contracts/AuthRequests.cs:6–17 defines only FullName, Email, Password, and ConfirmPassword for registration, with JsonUnmappedMemberHandling.Disallow. Sending role, roles, or UserId does not select a privileged registration role. Existing tests exercise role and UserId overposting; exhaustive field-name variants remain optional regression coverage.

The actual current contents of AspNetRoles/AspNetUserRoles were not queried. Seed metadata verification does not establish the current contents of a live database. No existing user membership was modified in this review.

## JWT roles and authorization behavior

Relevant source:

- PersonalFinance.Infrastructure/Authentication/RefreshTokenService.cs:83–89 retrieves roles through UserManager.GetRolesAsync before issuing login/refresh tokens.
- PersonalFinance.Api/Authentication/JwtTokenIssuer.cs:21–25 emits sub, jti, issuance time, and role claims.
- PersonalFinance.Api/Authentication/JwtSettings.cs:24–32 validates issuer, audience, signature and lifetime; restricts HS256; requires signed/expiring JWTs; sets zero skew and RoleClaimType=role.
- PersonalFinance.Api/Authentication/AuthenticationConfiguration.cs:32 disables inbound claim remapping.
- AuthenticationConfiguration.cs:39–55 rejects invalid subjects, missing/locked accounts, and tokens whose role set differs from current Identity membership.
- AuthenticationConfiguration.cs:60–62 registers the named User and Administrator policies.
- PersonalFinance.Api/Program.cs:32–36 runs authentication before authorization and controller mapping.

Named policies use:

    User: RequireAuthenticatedUser().RequireRole("User")
    Administrator: RequireAuthenticatedUser().RequireRole("Administrator")

User and Administrator are independent roles; Administrator does not implicitly satisfy the User policy. A principal with both memberships satisfies both policies. This separation is deliberate and was confirmed by the temporary matrix.

The policy evaluator consumes an already authenticated principal. JWT signature/account/role validation occurs earlier in JwtBearer middleware; a direct policy-unit test is not itself a JWT cryptographic or SQL membership test.

### Existing middleware evidence

tests/PersonalFinance.ApiSecurityTests/Program.cs:

- Lines 56–64: role claim, stable subject, issuer/audience, jti, and lifetime.
- Lines 65–87: expired, invalid-signature, wrong issuer/audience, and unsigned JWT rejection.
- Lines 88–99: locked account and stale role-set rejection.
- Lines 100–105: User policy allows User; valid User receives 403 at administrator-only operation.
- Lines 106–111: valid signed claims matching in-memory Administrator membership receive 200.
- Lines 112–116: anonymous administrator operation receives 401.

The existing test-only administrator action uses Authorize(Roles = ApplicationRoles.Administrator), not the named Administrator policy. Both require the same approved role, but this distinction motivated direct named-policy verification below.

## Direct named-policy probe

A temporary console probe outside the repository called the actual AddApiAuthentication registration and IAuthorizationService.AuthorizeAsync. It did not register controllers, start a server, load production secrets, issue JWTs, create accounts, or contact a role/database store.

Observed policy matrix:

| Synthetic principal | User policy | Administrator policy |
| --- | --- | --- |
| Anonymous, no roles | Deny | Deny |
| Anonymous carrying Administrator claim | Deny | Deny |
| Authenticated, no role | Deny | Deny |
| Authenticated User | Allow | Deny |
| Authenticated Administrator only | Deny | Allow |
| Authenticated User and Administrator | Allow | Allow |
| Authenticated lowercase administrator | Deny | Deny |
| Authenticated unapproved role name | Deny | Deny |

All 16 policy assertions passed.

Seven additional assertions confirmed CurrentUser rejects missing, empty, zero, negative, nonnumeric, overflowing, and leading-whitespace subjects.

**Supplemental result: 23 assertions passed.** These are temporary read-only verification assertions, not newly committed regression tests. They do not replace an actual signed-JWT middleware test or SQL-backed Identity membership test.

## Ownership verification

Relevant source:

- PersonalFinance.Api/Authentication/CurrentUser.cs:9 reads identity exclusively from the current principal.
- Lines 13–16 require an authenticated identity and positive integer sub.
- Line 11 compares that ID with resourceOwnerId.
- PersonalFinance.Api/Controllers/AuthController.cs:42–57 uses that derived ID for logout-all and /me.

The helper does not accept a request DTO, query string, header UserId, or administrator privilege as proof of ownership. Its contract assumes the owner argument comes from trusted server-side resource data.

Existing tests verify:

- Authenticated subject owns its own ID and cannot own another ID, even with Administrator membership.
- An unauthenticated principal with a sub is rejected.
- Register overposting is rejected at the HTTP boundary.
- Logout-all uses the validated caller despite a spoofed UserId in its request body.
- Service-level logout-all preserves another user's sessions.

CurrentUser does not independently verify JWT cryptography; it consumes HttpContext.User established by authentication middleware. Do not construct that principal from unvalidated client input in future features.

Future financial APIs must apply Authorize and perform owner-filtered loading/querying and relationship checks. The helper does not automatically add EF query filters or enforce ownership on a new controller. Administrator policy membership must not become permission to read another user's private transactions.

## Test-only endpoints and production surface

The user reports six authentication operations in OpenAPI, matching the six HTTP action attributes in PersonalFinance.Api/Controllers/AuthController.cs.

The existing TestAuthorizationController is in tests/PersonalFinance.ApiSecurityTests, outside the API project. ApiFixture.cs:47 explicitly adds it only to the in-memory test MVC application. The production project does not reference that test executable and Program.cs does not add its application part.

The tests build an in-memory RequestDelegate pipeline; they do not start Kestrel or bind a public port. No new route or test-only public endpoint was added in this review.

No production Administrator-only business operation exists yet. That absence is expected for the six-endpoint authentication phase and is not an authorization defect. It also means no real SQL-backed administrator-only HTTP operation has been demonstrated by this review.

## Executed tests and integration distinction

| Command/check | Result | Persistence/network scope |
| --- | --- | --- |
| dotnet run --project tests/PersonalFinance.AuthenticationTests --no-restore | 27 passed, 0 failed | Real Identity services with in-memory user/role/session stores |
| dotnet run --project tests/PersonalFinance.ApiSecurityTests --no-restore | 23 passed, 0 failed | Actual JwtBearer, MVC, authorization/rate-limit middleware with in-memory persistence; no listener |
| dotnet run --project tests/PersonalFinance.SchemaValidation --no-restore | 44 passed, 0 failed | Offline EF model/migration metadata, including role seeds |
| Temporary direct policy/subject probe | 23 passed | Synthetic principals and actual named-policy registration; no accounts/JWT issuance/database |
| Actual SQL Server authorization integration | Not executed | No connection to PersonalFinanceDb or another SQL database |
| Real administrator account/role changes | Not performed | Prohibited by this review's safeguards |

Totals: **94 existing checks passed**, including **50 authentication/API-security checks**, plus **23 supplemental temporary assertions**. The 44 schema/support checks are not all authorization tests and are not presented as such.

The existing test fixtures temporarily simulate Administrator membership in test-memory objects. They do not create a real administrator account or modify any existing SQL user membership.

Commands used process-local cache/APPDATA settings and disabled NuGet network auditing for offline repeatability. No package references or project configuration were changed. No startup-smoke mode or SQL-test mode was invoked.

## Missing or insufficient test coverage

These are test recommendations only. No tests or application code were changed.

### W-01 Permanent named Administrator-policy matrix is missing

Evidence:
- AuthenticationConfiguration.cs:60–62 defines the named policies.
- tests/PersonalFinance.ApiSecurityTests/TestAuthorizationController.cs:12 uses named User.
- Lines 15–16 use the Administrator role attribute rather than named Administrator.

Current coverage proves the role attribute works and this review's temporary probe proves the named policy works now. A future typo/regression in the named Administrator policy would not be directly caught by the committed HTTP test.

Recommended correction: add permanent IAuthorizationService tests for User, Administrator-only, both roles, no role, anonymous, and wrong/unapproved role names. No additional public test endpoint is necessary.

### W-02 Administrator privilege removal and mismatched elevated JWT claims need explicit regressions

Evidence:
- AuthenticationConfiguration.cs:53–55 uses current-role SetEquals.
- tests/PersonalFinance.ApiSecurityTests/Program.cs:94–99 tests adding Administrator to the store while reusing an older User-only token.

The existing stale-role test covers role addition, not removal of Administrator from a previously privileged token. It also does not explicitly test a correctly signed test JWT claiming Administrator while the test Identity store contains only User.

Recommended correction: add isolated in-memory middleware tests expecting 401 for stale privileged claims after removal and for elevated claim/store mismatch. A newly issued ordinary User token should then receive 403 for an administrator-only operation. Use test objects and temporary signing keys, not production accounts.

### W-03 Test-double role mapping can mask unexpected-role behavior

Evidence:
- tests/PersonalFinance.AuthenticationTests/TestDoubles.cs:51–52 maps every non-USER membership string to Administrator.
- tests/PersonalFinance.ApiSecurityTests/ApiFixture.cs:115 repeats that mapping when issuing test JWTs.

This is a test-fixture limitation, not production behavior: production uses real Identity role names from GetRolesAsync. Unknown role scenarios could be misrepresented as Administrator in these fixtures.

Recommended correction: use an explicit approved-role mapping and reject or faithfully preserve unexpected roles rather than mapping them to Administrator. Add unknown/wrong-case role regression cases. The temporary direct probe already verified the production named policies deny those role names today.

### W-04 Subject and multi-user HTTP ownership edge cases need durable end-to-end tests

Evidence:
- CurrentUser.cs:13–16 rejects invalid subjects.
- tests/PersonalFinance.ApiSecurityTests/Program.cs:117–125 covers positive/anonymous helper use.
- Lines 158–164 exercise logout-all with a spoofed owner body but only one HTTP fixture account.
- AuthenticationTests separately covers logout-all preservation of another user's sessions.

Recommended correction: retain the malformed-subject cases from this temporary review as permanent tests and add signed-JWT middleware variants. Add an HTTP test with two in-memory users and independent sessions to prove a spoofed body cannot revoke the second user's sessions, combining the existing boundary and service assertions.

### W-05 Actual SQL role persistence and reload behavior are not verified here

Evidence:
- ApiFixture.cs:52–54 substitutes fake Identity/role/session stores.
- SchemaValidation checks seed metadata, not live tables.
- Existing SqlIntegration.cs scenarios concentrate on refresh transactions and do not provide role-specific SQL authorization coverage.

The user's successful manual SQL-backed authentication testing is valuable but does not, as described, establish a live administrator-only role matrix or independently verified current AspNetUserRoles contents.

Recommended correction: in separately approved isolated infrastructure, verify registration persists only User membership, role normalization/joins behave correctly, and subsequent requests reload changed roles. Any privileged test fixture/account must be separately approved and isolated; do not create administrator accounts or alter PersonalFinanceDb to complete this read-only review.

### W-06 Financial endpoint ownership regression tests necessarily remain future work

Evidence:
- CurrentUser.Owns is an explicit helper, not an automatic data-access restriction.
- Current production controller surface contains only authentication operations.
- Named policies are not a global fallback policy for every future endpoint.

Recommended correction: when financial routes are implemented, test cross-user reads, lists, edits, deletes, and related-resource IDs with two ordinary users; ensure Administrator has no private-data bypass; assert protected endpoint metadata. Use the approved special handling for system-default categories. These tests belong with those features and are not required to invent financial controllers during this review.

## Safeguard verification

SHA-256 comparison of 77 pre-existing non-generated source/configuration/report/SQL files found no changes. The original application, test source, project references, migrations, saved SQL, and earlier reports were preserved. Build-generated outputs and the temporary probe are not application-source modifications.

No real user, administrator account, existing role assignment, or database record was created/changed. No database query, migration application, schema change, or SQL integration test was executed. No secrets, signing keys, passwords, or token values are included in this report or test-result output.

## Readiness decision

**Phase 5 authorization is ready for subsequent financial API development.** Registration privilege boundaries, JWT-role mapping, named policies, administrator restrictions, and claim-derived ownership are verified by source inspection and passing automated checks. There is no blocking authorization failure identified.

Readiness is for continued development, not a claim that all future financial endpoints are already protected or that live SQL-backed administrator authorization is certified. Add the recommended regression coverage, and implement/test explicit authentication plus server-side ownership at each financial boundary as those APIs are introduced.

No changes to application code, memberships, public endpoints, migrations, or PersonalFinanceDb are needed or authorized by this verification.

