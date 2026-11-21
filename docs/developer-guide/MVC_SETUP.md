# Administrator Website (MVC) Setup

Project: `PersonalFinance.AdminMvc/PersonalFinance.AdminMvc.csproj` (`net10.0`). It has **no** reference to the
backend projects and **no** database access. Everything goes through the Web API over HTTPS.

## Configuration

| Key | Required | Value for local development |
| --- | --- | --- |
| `BackendApi:BaseUrl` (env var `BackendApi__BaseUrl`) | yes; validated at startup | `https://localhost:7027` (absolute HTTPS origin, no path, query or user info) |
| `ASPNETCORE_ENVIRONMENT` | yes | `Development` (set by the launch profile) |
| `AllowedHosts` | production only | real admin hostname |

The MVC project has no user-secrets ID and no committed `appsettings*.json`. Set the URL as an environment variable.
Do not create `PersonalFinance.AdminMvc/appsettings.Development.json`, because it is **not** git-ignored.
The MVC site never needs SQL credentials or the JWT signing key.

Launch profile URLs: `https://localhost:62978` and `http://localhost:62979`.

## Build and run (API must already be running on 7027 with a trusted dev certificate)

```powershell
dotnet build PersonalFinance.AdminMvc -c Debug
$env:BackendApi__BaseUrl = "https://localhost:7027"
dotnet run --project PersonalFinance.AdminMvc --launch-profile https
# open https://localhost:62978
```

## Authentication flow

1. The login form posts email + password (anti-forgery protected) and the MVC server calls `POST /api/auth/login`.
2. The account must hold the **Administrator** role; anyone else is refused. Create one with the provisioning tool
   (BACKEND_SETUP.md).
3. Access and refresh tokens stay **server-side** in an in-memory session store (`Authentication/AdminSessionStore.cs`).
   The browser gets only the `__Host-OWLI.Admin` cookie (8-hour, non-sliding) and the `__Host-OWLI.Antiforgery` cookie.
   The site refreshes via `POST /api/auth/refresh` and re-checks the Administrator role via `GET /api/auth/me`.
   Logout is an anti-forgery-protected POST that calls `POST /api/auth/logout`.
4. MVC sessions are lost when the MVC process restarts (in-memory store), so sign in again after a restart.

## Functionality and backend dependencies

| Page | API endpoints | Notes |
| --- | --- | --- |
| Dashboard | none (identity from the login session) | Presentation only. No global statistics, because no admin-wide API exists |
| Default categories | `GET api/categories`, `POST/PUT api/categories/defaults[/{id}]` | Create, rename/recolor, deactivate/reactivate shared defaults |
| Budgets | `GET/POST/PUT/DELETE api/budgets` | Manages the **signed-in administrator's own** monthly budgets. The API has no endpoint for other users' budgets |
| Financial reports | `GET api/reports/monthly` | The **signed-in administrator's own** monthly report (totals, categories, budget status) |

## Known limitations

- No platform-wide administration: no user management, no cross-user budgets or reports, no monitoring or audit views.
  These need new Administrator API endpoints.
- Not runtime-verified in the 2026-11-21 verification. Verified offline: build, `PersonalFinance.AdminMvc.UiTests` (auth/anti-forgery
  metadata, Reports handling) and `deployment/checks/ReportsContract` (backend DTO to MVC client contract).
  A browser login against a real API and database has not been re-verified.
