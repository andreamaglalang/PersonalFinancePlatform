# Known Issues, Blockers and Unfinished Work

## Blockers (need an owner decision)

1. **Migration 3 not approved.** Every `/api/savings-goals` endpoint returns 500 on a migrations-1–2 database.
2. **Migration 4 "UNSAFE TO APPLY".** Recurring bills, bill payments and bill reminders cannot run. The review
   (`docs/elect3-demo/MIGRATION4_REVIEW.md`) also lists:
   - **UTC "today" bug:** `BillPaymentService` treats the UTC date as today. In UTC+8, a bill due today cannot be paid
     between 00:00 and 07:59 local time (returns 409), and payments made then are dated the previous day. A later
     migration plus a service change is proposed. Do not edit migration 4 itself.
   - Never executed against SQL Server. The bill-payment SQL harnesses are internal and not in this repository.
3. **Hangfire schema not installed**, so scheduled jobs cannot run. There is no approved schema script.
4. **Applied-migration history of the original database is unverified** in this handoff (LocalDB was not started).

## Integration problems

5. **Android debug certificate is machine-specific.** Replace it on the new PC (ANDROID_SETUP.md).
6. **Android Goals and Bills tabs are placeholders.** The Bills message ("needs server support that is not ready yet")
   is outdated about the API (the endpoint exists) but correct in practice (schema blocked).
7. **Android Reports are computed on the device**, so they can differ from `/api/reports/monthly` if unsynced
   (`Pending`) records exist.
8. **MVC is single-account.** Budgets and reports are the signed-in administrator's own data. There are no admin-wide endpoints.
9. **Root `README.md` is outdated.** It says no MVC or Android project exists and that recurring-bill endpoints aren't
   exposed. Trust `docs/hermes-handoff/`.
10. **Port 7027** is shared by every API run and by Android sync tests, so run one API at a time. Restarting the API ends
    phone sessions (Android tokens are in memory) and interrupts an in-progress sync. MVC sessions are lost on MVC restart.
11. **Android launcher icon** is not customized (system default).
12. **No CORS, no forwarded-headers middleware.** That is fine locally, but must be designed before any browser-SPA or
    proxy deployment (DEPLOYMENT.md).
13. `PersonalFinance.AdminMvc/appsettings.Development.json` is **not** git-ignored. Use environment variables.

## Preserved but intentionally not integrated

- Branch `wip/android-worktree-snapshot-20261121`:
  - commit `d23c424`: hard-codes the Plan → Budgets tab count to `0` (**a regression**, rejected) and removes the
    Login moon (rejected: the existing design is kept). The integration branch keeps the working `budgetCount`
    and the moon.
  - commit `446d3c3`: a **stale** Nov-19 copy of the recurring-bill/report/Hangfire backend. The integrated backend is
    newer (transactional monthly summary, `/api/dev` production guard, FX, notifications). **Never merge.**
- Internal SQL-safety harnesses, database-verification tools and machine-pinned migration scripts are kept **private**
  on the original PC. They are pinned to the original machine and not needed for development.

## Repository exposure history

- On 2026-11-21 a branch `wip/main-worktree-20261121`, containing internal SQL-safety tooling with a machine name and
  local paths (no credentials), was public for a short time and then deleted. **Deleting a branch does not guarantee
  removal** from GitHub caches, forks, the events API or clones made meanwhile.
- `feature/backend-hangfire-demo` (commit `cef531c`) and earlier history still contain machine-pinned migration
  scripts and docs with the original laptop name, database GUID and local paths (no credentials). They were removed or
  redacted on the integration branch tip, but history cannot be cleaned without a force-push, which is not allowed.
