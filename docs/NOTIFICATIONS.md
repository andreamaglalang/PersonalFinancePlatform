# OWLI notifications

OWLI has one in-app inbox per member:
- **Storage:** the `Notifications` table in the finance database.
- **API:** `GET /api/notifications`, `POST /api/notifications/{id}/read` and `POST /api/notifications/read-all`.
- **Clients:** the MVC Member inbox and the Android inbox.

There are no push, email, SMS or real-time channels. Each client shows the stored notifications on its next load or refresh, and read state is shared through the same rows.

## Types and triggers

| Type | Label (both clients) | Trigger | Kind | Deduplication key |
|---|---|---|---|---|
| `BillReminder` | Bill reminder | Worker `owli-bill-reminders`, daily 07:00 Asia/Manila: from `ReminderDaysBefore` days before the next due date until the due date ("due today" on the day) | Scheduled | `bill:{id}:due:{date}` |
| `BillOverdue` | Overdue bill | Same job, same run: an active bill whose oldest unpaid occurrence (`NextDueDate`) is before Manila today | Scheduled | `bill:{id}:overdue:{date}` |
| `BudgetAlert` | Budget alert | Worker `owli-budget-alerts`, hourly: a current-month budget newly reaches Warning or Exceeded | Scheduled | `budget:{id}:transition:{guid}:{status}` |
| `MonthlySummary` | Monthly summary | Worker `owli-monthly-summaries`, day 1 at 00:30: the previous month's stored summary | Scheduled | `summary:{yyyy-MM}` |
| `GoalMilestone` | Savings milestone | API, in the same save as a goal or contribution change: 25%, 50% or 75% of the target reached | Event | `goal:{id}:milestone:{25\|50\|75}` |
| `GoalCompleted` | Goal achieved | API, the same save: the target is reached or exceeded | Event | `goal:{id}:completed` |
| `BillPaymentRecorded` | Bill payment recorded | API, the same save as the payment receipt | Event | `bill:{id}:paid:{dueDate}` |
| `PasswordChanged` | Password updated | API, in the same transaction as a successful password change and refresh-token revocation | Event | `security:password:{guid}` |

The unique `(UserId, DeduplicationKey)` index is the final guard against duplicates. Event notifications are written by the API's financial services. They do not depend on the worker, and they commit or roll back together with the change that caused them. Replayed requests (the same ClientId) return the original result without reaching the notification code. Rejected requests never create a notification.

## Policies

### Savings milestones and completion

Progress is computed from the persisted contributions and target, in exact decimals.

After each committed change (contribution add or edit, goal target edit), the highest milestone reached is notified, but only if it is higher than every milestone already notified for that goal. As a result:
- one contribution that crosses several milestones produces a single notification, the highest;
- completion takes priority over milestones;
- overfunding is allowed and simply completes the goal;
- falling below a milestone and crossing it again never repeats it, whether through an edit, a deletion or a raised target. Retries can't repeat one either;
- lowering the target can complete a goal, and the completion is notified in that save;
- **completion is notified once per goal.** If a goal is reopened (a contribution is edited down or deleted) and completed again, no second completion notification is created, to avoid inbox spam;
- cancelled goals never notify.

### Overdue bills

Payments always settle the oldest outstanding occurrence, which is the bill's `NextDueDate`. One overdue notice is created per occurrence, on the first daily run after its Manila due date. Any later occurrences that are also past due are counted in the same message ("2 later occurrences are also past due.") rather than each getting its own notification.

After the oldest occurrence is paid, `NextDueDate` moves on, and the next run notifies the following occurrence if it is still overdue.

Paid and inactive bills, and occurrences due today or later, get no overdue notice: today is "due today", and future dates get reminders.

### Payment confirmations

The confirmation uses the recorded bill amount and currency, for example "Your Internet bill payment of PHP 1,299.00 for Nov 28, 2026 has been recorded." OWLI records payments; the wording never suggests that money was transferred to a provider.

### Security notices

The password-change notice contains no password, token or session data. It is created only after a successful change; failures create nothing. Existing security-stamp rotation and refresh-token revocation are unchanged.

### Offline synchronization

Offline sync has no server inbox record. On Android, the existing offline banner shows:
- **pending entries:** "N waiting to sync";
- **rejected entries:** "N not accepted by the server…";
- **after a sync the server confirmed for at least one entry:** "You're all caught up" (when nothing remains) or "N synced to your account."

The confirmation appears once and clears itself after about 8 seconds. Automatic retries that send nothing never show it.

## Presentation

Both clients show:
- a type label and an icon from the existing icon sets;
- the message;
- the time in Manila;
- unread state, stated in text (an "Unread" chip on MVC; "Unread" in the TalkBack description and a bold label on Android) as well as by colour.

Lists are newest first, paged, with an unread filter, Mark read and Mark all as read. References (`ReferenceType`/`ReferenceId`) are stored but not linked from either client, so a deleted goal or bill never produces a broken link.

## Worker responsibilities

The independent `PersonalFinance.Worker` console runs only the three scheduled jobs, with their existing IDs, schedules, retry and concurrency attributes. Overdue detection was added to the existing daily reminder job; no new recurring job was registered. The API still hosts no Hangfire server.

## Configuration and deployment

There are no schema changes, migrations, settings, dependencies or services. The `Type` column is a free 30-character string, so the new types fit the existing schema. Existing deployment instructions remain applicable unchanged.

## Known limitations

- Delivery is pull-based. A notification appears on the other client after its inbox is opened or refreshed.
- Notifications do not link to the referenced record.
- Overdue notices depend on the worker's daily run. With no worker running, they are created on the next run.
- The password-change notice is created inside the existing authentication transaction. If the database rejected the notification, the password change would roll back with it and report failure, never success.
