# Test OWLI notifications

Use a dedicated disposable finance/Hangfire pair and two synthetic members, A and B.
Keep real accounts, operational catalogs and existing records intact. Use a new goal/bill name
for each run: deduplication history deliberately survives read actions and goal reopening.
This guide uses **Manila today = 28 November 2026**. On a later day, move bill dates relative
to today and use that day's contribution date; do not change the system clock or job schedules.

## Start once, then test both interfaces

1. Check out the notification PR branch (or `dev` after reviewed integration). Follow
   [developer setup](DEVELOPER_SETUP.md) for existing private user secrets, approved test SQL
   catalogs and trusted HTTPS. No migrations or new configuration are needed for this enhancement.
2. In separate terminals at the repository root, run `dotnet run --project PersonalFinance.Api`
   and `dotnet run --project PersonalFinance.AdminMvc`. Normal local endpoints are
   `https://localhost:7027` and `https://localhost:62978`; MVC's private backend setting must
   target that same API. Keep the test Worker **stopped** for the event rows below.
3. Open `android/` in Android Studio. Select Java 17, the installed SDK and the **debug** build
   variant. Run `app` on a dedicated emulator, preserving its application data. Set
   `adb -s <serial> reverse tcp:7027 tcp:7027` after starting/reconnecting the emulator.
   Debug uses `https://localhost:7027/`. One time per developer certificate/device, install
   **only the public localhost development certificate** as a user CA on that test device,
   following developer setup. The localhost debug policy accepts user CAs; release uses normal
   system trust. Do not disable TLS/hostname validation or package certificates/private keys.
4. Sign member A into Android and MVC (`/Account/Login`). Open Home/Dashboard and Inbox.
   Record the unread count before each action. Use B in a separate browser session for isolation.
   A normal Android Studio launch works independently of instrumentation; no test arguments are
   needed for everyday use.

## Exact event actions — worker stopped

| Event | Actions in Android Plan or MVC Plan | Expected inbox result immediately after success |
|---|---|---|
| 25%, 50%, 75% | Create **Emergency Fund QA**, target PHP **10,000**. Add PHP **2,500** dated **2026-11-28**, then add **2,500** twice more. | One new notice each time: “Great start! You've saved 25% of your Emergency Fund QA goal.”, “You're halfway there! You've reached 50% of your Emergency Fund QA savings goal.”, then “Almost there! You've saved 75% of your Emergency Fund QA target.” |
| Completion | Add the final PHP **2,500** to that goal. | One **Goal achieved**: “Congratulations! You've reached your PHP 10,000.00 Emergency Fund QA goal.” |
| Several thresholds / overfunding | New **Travel QA**, PHP **1,000** target: add **800** once, then **500**. | First only 75%; then only completion. No 25/50 backlog. |
| Edits, targets, reopening | New **Edit QA**, target **1,000**: add **100**, edit that contribution to **600**, rename goal, edit its note, reduce to **550**, then change target to **500**. Raise target to **2,000**, then lower to **500** again. | Only 50% at the upward edit and one completion at the first target reduction. Metadata/downward edits are silent. Recompletion does not repeat. Deleting a contribution/reopening also never repeats a previously notified threshold. |
| Payment recorded | Create **Internet QA**, Expense category, PHP **1,299**, Monthly, first due **2026-11-28**. Record that occurrence paid. | **Bill payment recorded**: “Your Internet QA bill payment of PHP 1,299.00 for Nov 28, 2026 has been recorded.” It records a payment; OWLI does not transfer funds. Retrying the same accepted request/receipt adds nothing. |
| Password change | In Account, try a wrong current password once; then change A's password using the correct current value and a new value meeting the displayed policy. | Failure adds nothing. Success creates **Password updated**: “Your OWLI account password was successfully changed.” Sign in again on both clients with the new password; existing sessions are revoked. Do not test this on a real account. |

Android refreshes its account-owned inbox/count after confirmed goal, contribution and payment
changes. MVC's next redirected page already has the new count. Open the other client's inbox
or refresh it: the same message must appear. No application restart or worker wait is needed.
For older goals with progress but no notification history, renames, note edits and noncrossing
contributions must not announce an old milestone.

## Scheduled actions — start the independent test worker

Prepare the upcoming/overdue bills, the PHP 800 threshold expense and the October activity
below **before** starting the worker. Add the extra PHP 250 only after the warning appears.
Then start only the verified disposable profile:
`dotnet run --project PersonalFinance.Worker --launch-profile DevelopmentTest`.
The profile rejects operational catalogs, missing prepared storage, another registered worker
and permissive SQL certificate settings. It does not provision storage or apply migrations.

| Case | Exact synthetic setup for 28 November 2026 | Normal run / expected result |
|---|---|---|
| Upcoming | **Upcoming QA**, PHP **1,500**, Monthly, first due **2026-11-30**, reminder **3** days | `owli-bill-reminders`, daily **07:00 Asia/Manila**: “Upcoming QA (PHP 1,500.00) is due in 2 days, on Nov 30, 2026.” |
| Overdue | **Electricity QA**, PHP **1,500**, Monthly, first due **2026-11-27**, leave unpaid and active | Same reminder job: “Your Electricity QA bill of PHP 1,500.00 was due on Nov 27, 2026.” Paying or deactivating before the locked eligibility check prevents an overdue notice for that candidate. |
| Budget threshold | New Expense category **Budget QA**, November **2026**, limit **1,000**, alert **80%**. Record PHP **800** expense on **2026-11-28**. | `owli-budget-alerts`, hourly: “Budget QA budget reached 80%: PHP 800.00 of PHP 1,000.00.” |
| Budget exceeded | After the threshold notice, record another PHP **250** in that category on **2026-11-28**. | Next budget job: “Budget QA budget exceeded: PHP 1,050.00 of PHP 1,000.00.” This is a new transition, not a duplicate warning. |
| Monthly summary | Record a PHP **40** expense dated **2026-10-10** before October's summary exists for A. | `owli-monthly-summaries`, day 1 **00:30 Asia/Manila**: “Your October 2026 summary is ready: income 0.00, expenses 40.00.” Totals include all that synthetic member's October activity; stored summaries remain unchanged. |

For immediate QA, trigger the existing recurring IDs through an approved **test-only Hangfire
client**, as in `tests/PersonalFinance.HangfireTests/Program.cs`, after starting the worker.
Do not change cron schedules, add a public job endpoint or start a server in the API. A private
console referencing `PersonalFinance.Worker.csproj` can use the following body (the worker's
existing user-secrets store supplies the two verified disposable connections):

```csharp
using Hangfire;
using Hangfire.SqlServer;
using Microsoft.Extensions.Configuration;
using PersonalFinance.Worker;

var config = new ConfigurationBuilder().AddUserSecrets(typeof(DevelopmentStorageValidation).Assembly, optional: false).Build();
DevelopmentStorageValidation.ValidateConfiguration(config); // rejects operational/test mixing and SQL TLS bypass
var storage = new SqlServerStorage(config.GetConnectionString("Hangfire"),
    new SqlServerStorageOptions { PrepareSchemaIfNecessary = false });
var jobs = new RecurringJobManager(storage);
jobs.Trigger("owli-bill-reminders");
jobs.Trigger("owli-budget-alerts");
jobs.Trigger("owli-monthly-summaries");
```

Trigger bills twice: each occurrence has one reminder/overdue notice. Trigger budgets again
without changing spending: no duplicate for the unchanged transition. Trigger the monthly
job again: one stored summary/notice. This client only enqueues; the separate worker must process
it. Stop the test worker gracefully afterwards. To exercise its lifecycle in fresh throwaway
catalogs instead, set the approved private `OWLI_SQL_TEST_SERVER` master connection and run
`dotnet run --project tests/PersonalFinance.HangfireTests`; it creates/drops only its own fixtures.

## Shared state, offline feedback and presentation

- Create a milestone on Android; open MVC Inbox and mark that specific notice read. Refresh
  Android Inbox: it is read and the bell total falls. Create a milestone on MVC; open Android
  Inbox and mark it read; refresh MVC and check its total. Mark all as read and confirm zero
  unread through both interfaces. B sees none of A's notices and cannot mark A's IDs.
- Test Unread only and pagination with more than 20 notices on Android (MVC uses its existing
  page size). Mark a row read under Unread, then load more: the refreshed pages must not skip
  the next unread item. A count covers the whole inbox, including unloaded pages.
- On a dedicated Android emulator, go to Activity, disconnect connectivity, save one PHP **10**
  expense dated **2026-11-28** in a cached category, then reconnect. A pending row becomes one
  confirmed row and “You're all caught up” / “Your 1 offline transaction has been synchronized.” feedback appears
  for about eight seconds. Another empty retry must not repeat it. It creates no permanent
  inbox record. ADB reverse can keep loopback reachable when Wi-Fi is disabled; a controlled
  test-network outage must also interrupt that path. Restore connectivity after testing.
- Check all eight type labels/icons in light/dark, unread/read text, empty account, failed
  refresh/retry and next/previous/load-more states. Colours alone must not communicate unread
  status. Physical hardware and spoken screen-reader output require separate release checks.

Fresh results and limits are recorded in [E2E acceptance](audits/OWLI_E2E_Acceptance.md#notification-enhancement-continuation--28-november-2026).
