# OWLI portable runtime and SQL testing

Roadmap milestone R0. Works on macOS, Linux and Windows against any approved SQL Server
(the local container, a CI service container or Windows LocalDB). Never use these commands
against `PersonalFinanceDb` or operational `OWLI_Hangfire`.

## Persistent private settings

Use [the current development setup](../DEVELOPER_SETUP.md). All three executable
projects have independent user-secret stores loaded by Development launch profiles.
Ordinary API/MVC startup needs no exported configuration. Old `runtime.env` fixtures
and private launchers remain local historical tooling, not the supported onboarding
path. Do not enable a worker by setting shared/global environment variables.

The optional `OWLI_SQL_TEST_SERVER` variable below is for explicitly authorized
SQL integration runs only. Keep it absent for ordinary/offline verification.

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

Existing validated runtime catalogs are reused; startup never creates, resets or
migrates them. Creating/applying migrations to a fresh catalog requires separate
explicit authorization after verifying the local server and exact disposable
target. Preserve operational finance/Hangfire storage and all existing records.

## Run the platform

```bash
dotnet run --project PersonalFinance.Api       # https://localhost:7027
dotnet run --project PersonalFinance.AdminMvc  # https://localhost:62978
```

Android debug builds call `https://localhost:7027/`; on an emulator run
`adb reverse tcp:7027 tcp:7027` first.

## Administrator on a runtime catalog

`tools/PersonalFinance.AdminProvisioning` accepts Windows LocalDB `PersonalFinanceDb` (unchanged)
or a disposable `OWLI_Runtime_*` catalog on any server. Run it interactively from the solution
root with `DOTNET_ENVIRONMENT=Development` and the API user-secret settings configured; the confirmation
phrase names the target catalog. See [admin provisioning](../Development_Admin_Provisioning.md).
