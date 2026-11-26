# OWLI portable runtime and SQL testing

Roadmap milestone R0. Works on macOS, Linux and Windows against any approved SQL Server
(the local container, a CI service container or Windows LocalDB). Never use these commands
against `PersonalFinanceDb` or operational `OWLI_Hangfire`.

## Private settings

Keep runtime settings in `.local/runtime/runtime.env` (git-ignored, owner-only `600`). Derive
the SQL connection from your own local configuration; never commit or share it.

| Variable | Purpose |
| --- | --- |
| `ConnectionStrings__DefaultConnection` | Disposable runtime catalog `OWLI_Runtime_<yyyymmdd>` |
| `Jwt__SigningKeyBase64` | Random 64 bytes, base64; generate per environment |
| `ASPNETCORE_ENVIRONMENT`, `DOTNET_ENVIRONMENT` | `Development` |
| `Hangfire__Enabled` | `false` for the API |
| `BackendApi__BaseUrl` | `https://localhost:7027` (MVC → API) |
| `OWLI_SQL_TEST_SERVER` | Connection to `master` for the SQL harness |

Load it with `set -a; . .local/runtime/runtime.env; set +a` (bash/zsh).

## SQL integration harness

```bash
dotnet run --project tests/PersonalFinance.SqlIntegrationTests
```

Without `OWLI_SQL_TEST_SERVER`, only offline guard checks run. With it, the harness creates a
uniquely named `PersonalFinanceSqlTests_<guid>` catalog, applies every migration from empty,
runs real SQL checks (idempotency under concurrency, contribution ledger, bill receipts,
precision/FX snapshot, ownership isolation) and drops the catalog. It refuses any server
connection that names a catalog other than `master`. CI runs it in a throwaway SQL Server
service container (`sql` job).

The older `SavingsSqlTests`, `SavingsSqlLiveTests`, `BillPayment*` projects are offline
policy validators pinned to a historical LocalDB fixture; they do not connect to SQL.

## Disposable runtime catalog

```bash
dotnet ef database update --project PersonalFinance.Infrastructure \
  --startup-project PersonalFinance.Api --connection "$ConnectionStrings__DefaultConnection"
```

EF creates the catalog if it is missing and applies all migrations. Use a new dated name
for a clean run instead of resetting an existing catalog.

## Run the platform

```bash
dotnet run --project PersonalFinance.Api --launch-profile https       # https://localhost:7027
dotnet run --project PersonalFinance.AdminMvc --launch-profile https  # https://localhost:62978
```

Android debug builds call `https://localhost:7027/`; on an emulator run
`adb reverse tcp:7027 tcp:7027` first.

## Administrator on a runtime catalog

`tools/PersonalFinance.AdminProvisioning` accepts Windows LocalDB `PersonalFinanceDb` (unchanged)
or a disposable `OWLI_Runtime_*` catalog on any server. Run it interactively from the solution
root with `DOTNET_ENVIRONMENT=Development` and the runtime settings loaded; the confirmation
phrase names the target catalog. See [admin provisioning](../Development_Admin_Provisioning.md).
