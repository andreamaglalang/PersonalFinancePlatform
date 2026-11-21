# OWLI backend demonstration prerequisites

Branch `feature/backend-hangfire-demo` (`e454262`). Database: migrations 1 and 2 applied; 3 and 4 not applied; no Hangfire schema.

## Notifications (`GET api/notifications`) and the job trigger (`POST api/dev/jobs/{job}/run`)
1. The API must run from this branch (the Android checkout's build has neither endpoint).
2. `ASPNETCORE_ENVIRONMENT=Development`. In any other environment `/api/dev/*` returns 404 before authentication.
3. Hangfire stays disabled (`Hangfire__Enabled=false`); the trigger runs the job code directly and needs no Hangfire tables.
4. An Administrator account for the trigger (the database has 2 users in the Administrator role) and a member account for notifications. Administrators only see their own notifications.
5. Data for something to happen:
   - budget alerts: a budget for the current month whose spending has reached its alert threshold (e.g. limit 2.00 PHP at 80% plus a 1.00 PHP expense in the same category);
   - monthly summaries: at least one transaction or contribution dated in the previous month (October for a November demo). None is expected today, so the first run would report `created: 0`.
6. Each trigger run writes rows (notifications, summaries). A second run creates nothing new (deduplication).
7. Coordinate the time slot: the API uses port 7027, which the Android synchronization tests also use.

## API start commands (approval required; do not run while another API holds port 7027)
- Phone / Android: existing build, no launch profile, process-only settings `ASPNETCORE_ENVIRONMENT=Development`, `OWLI_DEV_CERT_THUMBPRINT=<thumbprint of the certificate bundled in the Android debug app>`, `Hangfire__Enabled=false`; then `adb reverse tcp:7027 tcp:7027` and the in-app HTTPS probe.
- Laptop tools / MVC only: `dotnet run --project PersonalFinance.Api --launch-profile https` with `ASPNETCORE_ENVIRONMENT=Development`.

## Demo script
`Invoke-OwliBackendDemo.ps1` prints the plan by default and sends nothing. With `-Execute` it runs one step, asks for confirmation, uses a hidden password prompt, never prints tokens, requires an exact phrase before any write, and signs out afterwards. It relies on the machine trusting the API certificate (no certificate bypass).

## Frankfurter FX
Internet access to `https://api.frankfurter.dev/v2/` (`currencies`, `rate/{from}/{to}?date=`). Only non-base-currency transactions call the provider; the Android form always uses the base currency, so use the script's `Fx` step. A rate is cached in `ExchangeRates` and reused within the configured age.

## Android synchronization compatibility (this branch vs the API Android currently uses)
- Transactions, categories, budgets, reports, auth and their DTOs: identical source to the Android checkout's backend (files copied byte for byte); `FinanceTransportMiddleware` only adds `/api/notifications` and `/api/dev` rules; `Program.cs` certificate mode identical.
- Offline sync relies on `POST api/transactions` ClientId idempotency (unchanged) and `GET api/categories` paging and `totalCount` (unchanged).
- Switching the running API from the Android checkout build to this branch is behaviour-compatible for Android, but restarting the API ends in-memory phone sessions and interrupts any sync in progress: only switch between Android test runs.
- No API change affects Room: the server contract is unchanged.
