# OWLI AUTH-5F — approved database preflight

Date: 2026-11-15. Scope: database identity, Development configuration, migration history, authentication schema metadata and effective SQL permissions only.

## Identity and configuration

Connected explicitly to `(localdb)\MSSQLLocalDB`, database `PersonalFinanceDb`, using Windows integrated authentication. SQL Server reported IsLocalDB=1, physical server `<original-laptop>\LOCALDB#<instance>`, and database GUID `<original database GUID>`, exactly matching the approved target. The metadata batch repeated a fail-closed database-name, LocalDB and GUID guard before reading other metadata. Database is READ_WRITE.

Created `PersonalFinance.Api/appsettings.Development.json` with only `ConnectionStrings:DefaultConnection` targeting the approved instance/database. Git confirms that exact file is ignored. ASP.NET Core loads this overlay for Development. Integrated security, encryption and local certificate trust are explicit; no credentials were stored. Higher-priority user secrets/environment/command-line settings were not inspected, so the effective configuration of a future API process remains unverified. No API process was started.

The initial sandboxed SQL connection failed during provider/security negotiation. The identical identity query succeeded outside the sandbox, as did the guarded metadata batch.

## Findings

- Migration history contains only `20261031023901_InitialFinanceSchema` (EF 10.0.13).
- Repository migrations absent from history: `20261031115500_AddCategoryNameUniqueness`, `20261103141326_AddSavingsGoalContributionLedgerAndRowVersions`, and `20261107054048_AddBillPaymentReceiptsAndGuards`. No migration was applied or changed. The unsafe bill-payment migration remains untouched.
- All seven Identity tables and `RefreshTokens` exist. Inspected column names/types/nullability, identity columns, indexes, foreign keys, checks and trigger presence/status; no account or token records were queried.
- Identity uses integer keys. Normalized email is required, uses `Latin1_General_100_CI_AS`, and has an unfiltered unique EmailIndex. UserNameIndex and RoleNameIndex are unique filtered indexes. Composite login/token key string columns are nvarchar(128). User checks are enabled/trusted; the base-currency lock trigger is enabled. Trigger body/behavior was not tested.
- RefreshTokens has all nine expected columns, including TokenFamilyId, revocation and replacement hash. Hash columns are char(64), `Latin1_General_100_BIN2`; TokenHash has a unique index; (UserId, TokenFamilyId) is indexed. Its user foreign key is enabled/trusted with NO_ACTION delete behavior. All three hash/expiry checks are enabled/trusted. These inspected features match the current authentication mapping.
- Current connection principal has CONNECT and VIEW DEFINITION; SELECT/INSERT/UPDATE/DELETE permission checks return 1 for all Identity tables and RefreshTokens. EXECUTE permission on sys.sp_getapplock returns 1. No procedure was executed and no permissions were changed. Current principal also reports sysadmin=1 and db_owner=1, so these results establish broad effective access, not least-privilege readiness or access under a different future runtime identity.

## Remaining blockers and boundaries

1. Migration history is behind repository source. Compatibility of broader finance functionality is not established by this auth-only metadata inspection. Any migration remediation requires separate review/authorization; do not apply pending migrations as part of AUTH-5F.
2. Role seed contents, account availability/state/passwords, and token contents remain unverified because record inspection is outside this authorization.
3. Effective API configuration (including signing key and HTTPS setup), actual runtime identity and end-to-end authentication behavior remain unverified. No API startup, authentication requests or real authentication testing occurred. Those activities need separate authorization.

No application data or schema writes, account creation/reset, protected-checkout access, commits or pushes occurred. The only persistent task changes are the ignored Development configuration and this report. Existing user changes were preserved.
