# Independent worker architecture and local storage preparation

This records the owner-approved target and Phase 0 preparation, 26 November 2026.
**Update (Phase 3, R2):** `PersonalFinance.Worker` is implemented; see
[Implementation and verification](#implementation-and-verification-r2) below.
Operational activation remains disabled until a controlled cutover is approved. Approved
requirements are in [PRODUCT.md](../../PRODUCT.md); current client contracts are
in [backend integration](../backend-integration.md). Preserve the historical
[handoff database guidance](../handoff/DATABASE.md); it is not authorization to
migrate the existing finance database.

## Approved target

Create `PersonalFinance.Worker` only in a later approved implementation task: a
separate .NET 10 console executable with Generic Host, included in the existing
solution, independently configured/stopped/restarted. It must not be an API,
require Kestrel/public HTTP/Cloudflare/dashboard, or depend on API process lifetime.
Generic Host plus Hangfire hosting supplies lifetime; no extra scheduler loop
or dummy keepalive service is required.

Dependency directions: API → Infrastructure/Domain; Worker → Infrastructure and
Domain as needed; Infrastructure → Domain; MVC/Android → Web API. No executable
API/Worker cross-reference or new class library solely for this separation.
Split ordinary shared DI from explicitly worker-owned Hangfire server/schedule
registration, while reusing shared SQL/financial services and job implementations.
Domain remains independent of hosting. Reuse Hangfire.Core/SqlServer/NetCore
1.8.25 and Newtonsoft.Json 13.0.4; verify the Hosting package and transitive
`Microsoft.AspNetCore.App` runtime requirement during implementation, without
unrelated upgrades.

The selected `FinanceJobs` family remains the initial implementation candidate,
subject to later SQL/runtime correctness tests:

| Stable recurring ID | Manila schedule |
| --- | --- |
| `owli-bill-reminders` | Daily 07:00 |
| `owli-budget-alerts` | Hourly |
| `owli-monthly-summaries` | Day 1, 00:30, previous-month summary |

Preserve `Asia/Manila`, job type/assembly identities, signatures, serializer
conventions and compatible persisted definitions. Never register the alternative
family alongside it. Review existing server/queue/recurring state before a
controlled cutover; do not delete history. The API must have no processing
server or recurring startup registration regardless of worker flags. Client-only
Hangfire registration is not needed unless a separate approved use requires it.

## Confirmed source and remaining implementation questions

At application baseline `6687eb95ab3741a747da36d334aa3420c4ed7c23`, API startup
calls `AddInfrastructure`, which calls `AddFinanceJobs`; an enabled flag adds the
server and schedule registration in the API host. No worker project exists.
Some other financial services require HTTP `ICurrentUser`; register actual worker
dependencies rather than fabricate a logged-in principal. `DevJobsController`
also directly runs financial jobs: moving only `AddHangfireServer` is insufficient.
Retire that path or agree an alternative in the implementation task.

The selected family leaves existing summaries unchanged; the alternative
recomputes them and uses different IDs/schedules/notification keys. Clarify the
intended summary policy and how both clients consume persisted summary snapshots.
Existing Windows LocalDB-bound live tests need a portable approved disposable
target strategy. Unique keys, financial transactions/locks and receipt/ledger
constraints—not retry attributes alone—must enforce safe financial effects.
See [architecture knowledge](../karpathy-wiki/wiki/owli/architecture.md).

## Dedicated local storage evidence

The approved existing local SQL container, loopback binding and persistent
volume were verified before mutation. SQL Server connectivity and instance
identity matched that target; no container or volume was recreated/restarted.
`PersonalFinanceDb` was online. `OWLI_Hangfire` did not exist and was created on
that same instance. No finance migrations or financial writes were performed.

Provisioning used only the installed Hangfire.SqlServer 1.8.25 package's
`tools/install.sql`, verified against the release's
[DefaultInstall.sql](https://github.com/HangfireIO/Hangfire/blob/v1.8.25/src/Hangfire.SqlServer/DefaultInstall.sql).
Package repository commit: `1d4778c23410b365b5f9ce35458202efe0b0f3ea`.
Installer SHA256: `0074feaaee463173e427c730bf82fe44008fe072e6ef1eb04d8e2ac3ffb8b1b6`.
The package script matches release content after BOM/newline normalization.
Catalog/instance/empty-table guards ran before installation. Session-only
`QUOTED_IDENTIFIER` and filtered-index SET requirements were enabled; no server
or database option was changed. An initial installer transaction failed under
sqlcmd's default quoted-identifier setting and left zero user tables; installation
then completed with the required session settings. No populated schema was upgraded.

Observed after provisioning: schema version 9, all 11 expected HangFire tables,
zero target server registrations, zero jobs, zero recurring definitions and zero
queued jobs. No processing server was started or recurring API called. Financial
schema/constraint metadata, migration-history fingerprint and row-count snapshot
matched before/after; container ID/start time/volume mapping also matched.
These checks do not prove row-by-row financial equality or absence of unrelated
concurrent processes elsewhere. No private job payloads were queried.

Runtime policy remains `PrepareSchemaIfNecessary=false`; do not use the runtime
storage constructor to provision/upgrade tables. Future preparation must inspect
existing schema/state first. A populated/incompatible schema or unexpected
processors is a stop condition for mutation, not permission to rerun this script.
Both catalogs authenticate through the verified mapped local endpoint. This
proves connectivity/schema preparation, not worker lifecycle or job execution.

## Disabled configuration preparation

The tracked [JSON template](appsettings.Worker.template.json) contains only SQL
connection placeholders, safe logging defaults and `Hangfire.Enabled=false`.
The actual local file is `.local/worker/appsettings.Worker.Local.json`, ignored
and restricted to owner-only access (file 600, directory 700). One private file
reuses the existing local SQL credential for two explicit catalog connections;
no credentials were regenerated and no new SQL principal was provisioned.
Connection values, actual host/port, credentials and private diagnostics are
not project documentation. Container metadata remains authoritative for the
local target; do not copy someone else's local settings.

No API/MVC/Android settings or shared/global environment variables changed.
Nothing auto-loads the future-worker file today. A future worker must explicitly
load its own configuration, validate same instance/separate catalogs, remain
clearly disabled or fail safely when invalid, and only activate within its own
process after implementation approval. It must not require API JWT/HTTPS/tunnel
credentials. Do not dot-source a shared activation script or set a global flag.

Future runtime acceptance must prove: API remains processor-free; independent
start/stop/restart and cancellation; correct three schedules and timezone across
supported hosts; schema preparation stays disabled; SQL retry/concurrent-write
safety; finance results and notifications accessible to the right user; no
cross-client regressions; useful sanitized logs and explicit failures. These
are NOT TESTED by this preparation.

## Implementation and verification (R2)

- **Composition:** `AddFinanceCore` (Infrastructure) registers only SQL persistence, clock, write scope,
  budget usage and `FinanceJobs`, with no HTTP or `ICurrentUser`. The API calls `AddInfrastructure`
  (core plus member services) and has no Hangfire hosting. `PersonalFinance.Worker/WorkerSetup.cs`
  alone adds Hangfire storage (`PrepareSchemaIfNecessary=false`, same serializer settings), the
  server and startup `AddOrUpdate` of the three IDs. `DevJobsController` and the competing job family
  (`FinanceAutomationJobs`, `HangfireJobRegistration`) are removed. Operational `OWLI_Hangfire` was
  checked read-only first: 0 servers, jobs, recurring entries or queued items, so nothing persisted
  referenced the removed family.
- **Storage guard:** `OWLI_Hangfire` (operational) or `OWLI_HangfireTests_*` (disposable). A
  disposable Hangfire catalog must be paired with a disposable finance catalog (`OWLI_Runtime_*` or
  `PersonalFinanceSqlTests_*`), on the same instance and in a different catalog.
- **Configuration:** optional `OWLI_WORKER_SETTINGS` JSON file, then environment variables, then
  command line. When `Hangfire:Enabled` is not `true`, the worker exits 0 without opening a connection.
  An invalid pairing exits 2. The service graph is validated on build.
- **Run:** `OWLI_WORKER_SETTINGS=<file> dotnet run --project PersonalFinance.Worker`. Stop it with SIGTERM
  or Ctrl+C in a terminal. Background jobs started from a non-interactive shell ignore SIGINT, so
  supervisors should send SIGTERM.

Evidence, 26 November 2026, on a local SQL container with synthetic data:

| Check | Result |
| --- | --- |
| `HangfireTests` offline (IDs, Manila crons, job identity, retry/concurrency attributes, guard, worker DI without `ICurrentUser`) | PASS 7/7 |
| `HangfireTests` live on disposable catalogs (registration, forced-failure retry then success, processing, repeated/concurrent triggers without duplicates, stop deregisters, no processing while stopped, restart drains backlog) | PASS 6/6 live (13/13 including the offline checks) |
| FinanceTests acceptance A (API registers no Hangfire services with `Enabled` false/true; API source has no Hangfire/job controller) | PASS |
| Runtime A: real API with `Hangfire__Enabled=true` and storage configured | 0 servers, 0 recurring, 0 jobs; former `/api/dev/jobs/*` returns 404 |
| Runtime B: worker executable beside the API | 1 server `owli-worker:*`, 3 recurring jobs with `0 7 * * *`, `0 * * * *`, `30 0 1 * *` in `Asia/Manila`; SIGTERM stops it in about 2 s and deregisters; restart re-registers the same 3 |
| Worker with the private operational settings (`Enabled=false`) | exits 0, no connection |

NOT TESTED: controlled cutover to operational `OWLI_Hangfire` with `PersonalFinanceDb` (not approved),
and wall-clock schedule firing (jobs were triggered).
