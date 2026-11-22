# MVC Tier 0 — Architecture and Verification Record

Status: implemented in the isolated worktree `PersonalFinancePlatform-MVC-Tier0` on branch
`feature/mvc-tier0-foundation`, based on `integration/owli-platform` commit `0731268`. **Uncommitted by
design** — no commit was made in Tier 0.

## Decisions

1. **One project, one authentication scheme, one session store.** `PersonalFinance.AdminMvc` hosts both the
   administrator portal and the member app. The cookie scheme stays `AdminCookie`
   (`__Host-OWLI.Admin`) and the store stays the in-memory `AdminSessionStore`. Renaming these is deferred:
   it would invalidate the in-memory store and touch the UI tests for no functional gain.
2. **Member surface is an MVC area.** `Areas/Member/...`, URL `/Member/{controller}/{action}`, area name
   single-sourced in `Areas/Member/MemberArea.cs`. The area route is registered **before** the default route
   with `{area:Exists}` (see `Program.cs`):
   ```csharp
   app.MapControllerRoute(name: "member",
       pattern: "{area:Exists}/{controller=Home}/{action=Index}/{id?}");
   app.MapControllerRoute("default", "{controller=Entry}/{action=Index}/{id?}");
   ```
   .NET 10 has no `IEndpointRouteBuilder.MapAreaRoute`; only `IRouteBuilder.MapAreaRoute`.
3. **Policies.** `User` = any authenticated member; `Administrator` = authenticated + `Administrator` role;
   fallback policy = `User`, so the site fails closed and only `[AllowAnonymous]` endpoints are public.
   Single-sourced in `Authentication/AuthorizationConfiguration.cs`.
4. **Role claims come from the API.** `AuthenticationCoordinator.Principal` emits one `ClaimTypes.Role` per
   entry of `CurrentUserResponse.Roles`; no role is hard-coded. `ContractValidation.Authenticated` accepts
   any user with at least one role.
5. **Registration is extracted, not rewritten.** `AddOwliAuthentication()` is a verbatim move of the cookie
   and DI registration out of `Program.cs`; `AddOwliAuthorization()` replaces the inline policy block.
   Both are asserted by tests rather than assumed.
6. **Reuse the existing API clients unchanged.** `IAuthenticationApiClient`, `ICategoryApiClient`,
   `BudgetApiClient` and `ReportsApiClient` are role-agnostic and bearer-based. No shared base class was
   introduced in Tier 0 (YAGNI).
7. **Landing decision is single-sourced.** `Controllers/OwliLanding.cs` is a pure `readonly record struct`
   returning `(ActionName, ControllerName, AreaName)`; `EntryController` and `AccountController` both call
   it. Anonymous → `Account/Login`; member → `Member/Home`; administrator → `Dashboard`.
8. **Member shell.** `Areas/Member/Views/Shared/_MemberLayout.cshtml` + `wwwroot/css/member.css`, reusing
   `site.css` tokens, `owli-ui.js`, `_OwlMark.cshtml` and `_NavIcon.cshtml`. Colours mirror
   `android/app/src/main/java/com/elect3/mobdev/personalfinance/ui/theme/Theme.kt`.
9. **Admin surface unchanged.** Every admin controller, view and route kept its path. The only edits are the
   `[Authorize(Policy = ...)]` value (now the shared constant, same string) and `AccountController`'s
   authenticated short-circuit, which now routes through `OwliLanding` instead of hard-coding the dashboard.

## Verification levels

| Level | Meaning | Commands | Result |
| --- | --- | --- | --- |
| **L1 compilation** | Code and Razor views build | `dotnet build PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj -c Release -p:NuGetAudit=false` | `0 Warning(s) 0 Error(s)` |
| **L2 offline tests** | Unit/reflection checks, no API, database, network, browser or migration | `dotnet run --project tests/PersonalFinance.AdminMvc.UiTests/PersonalFinance.AdminMvc.UiTests.csproj -c Release` | 7 `PASS:` lines, exit `0` |
| **L3 runtime** | Real browser, API and database | not performed | — |

L2 check classes, in run order:

1. `AuthorizationChecks` — evaluates the `User`, `Administrator` and fallback policies through
   `IAuthorizationService` against anonymous, member and administrator principals, and reflects over the
   four class-level admin controllers plus `AccountController.Index` to confirm they still require
   `Administrator`.
2. `AuthenticationPreservationChecks` — resolves `CookieAuthenticationOptions` for `AdminCookie` and
   asserts cookie name, `SecurePolicy.Always`, `HttpOnly`, `SameSite.Lax`, path, login/access-denied paths,
   sliding expiration off, 8-hour expiry, `SessionStore is AdminSessionStore`, `EventsType`, the default
   authentication scheme and the two DI registrations.
3. `RoleClaimsChecks` — a member principal carries exactly `User`, an administrator carries exactly
   `Administrator`, and `ContractValidation.Authenticated` accepts any non-empty role set.
4. `OwliLandingChecks` — the three landing decisions, plus `EntryController` staying in the root namespace
   with `[AllowAnonymous]` on `Index`.
5. `MemberAreaChecks` — `HomeController` is `[Area("Member")]` with the `User` policy and renders a view.
6. The two pre-existing checks (category filtering/pagination/antiforgery metadata, and reports handling).

## What L1/L2 do not prove

- **Route wiring.** `{area:Exists}` ordering and the `/` → `EntryController` mapping compile but are not
  exercised. They need the L3 browser check.
- **View rendering.** The Razor views compile; whether `_OwlMark`/`_NavIcon` resolve from the area's shared
  folder and whether the member shell looks right is unverified.
- **Login end to end.** No API call, no cookie issuance, no redirect was executed.

## Confirmed Tier 1 requirements (verified during Tier 0.6)

1. **Member logout control — MISSING, must be built.** The only logout endpoint is
   `POST /Account/Logout` (`Controllers/AccountController.cs:88`, `[ValidateAntiForgeryToken, LocalLogout]`,
   no role restriction, revokes the refresh family then signs out the cookie). It is rendered **only** in the
   admin shell (`Views/Shared/_Layout.cshtml:54` and `:79`) and the admin-only `Views/Account/Index.cshtml:15`.
   `Areas/Member/Views/Shared/_MemberLayout.cshtml` has no form and no logout link, so a signed-in regular
   `User` can never obtain an antiforgery token and cannot log out through the UI. **Implementation rule:**
   reuse the existing `POST /Account/Logout` endpoint and an antiforgery-protected form with the existing
   `data-confirm-logout` dialog; do not add a new logout route or a GET logout.
2. **Shared login experience.** `/Account/Login` still reads as an administrator portal (heading
   "Administrator sign-in", "Sign in to manage default categories", "Administrator portal" blurb) while
   accepting any role. Needs shared OWLI branding, copy and role-based redirection.
3. **`docs/developer-guide/MVC_SETUP.md` is wrong about the launch profile.** The MVC profile is named
   `PersonalFinance.AdminMvc`; `--launch-profile https` silently falls back to `http://localhost:5000`,
   which the app rejects with 400.
4. **Member dashboard data source.** No summary/aggregation endpoint exists; Android computes reports on
   device. Decide the web data source before building the member dashboard.

## Tier 1 scope (not started)

Member login/registration UI and logout; member dashboard and its data-source decision (no summary endpoint
exists today); member transactions (list/add/edit/delete); member budgets (reuse `BudgetApiClient`);
member reports (reuse `ReportsApiClient`); member account/profile/password; currency converter; administrator
cross-user API endpoints and pages; savings goals, recurring bills and notifications once migrations 3–4 are
approved.

## Open questions for the owner

1. Should the `User` policy also require the `User` role? It currently means "any authenticated member", so
   administrators can reach their own member data. Confirm the provisioning tool's role grant first.
2. The `/Account/Login` page still says "Administrator portal". After Tier 0 a non-administrator can obtain
   a session there; Tier 1 must rewrite that copy.
3. Member dashboard data source: no platform or account summary endpoint exists. Android computes reports
   on device, which is not reusable for the web.
