# OWLI — System-wide validation and error feedback (27 November 2026)

Branch `fix/system-wide-validation-feedback` from `dev` `be10ff1`.
- **Environment:** disposable `OWLI_Runtime_20261126`, synthetic `@example.test` accounts, Chrome (Puppeteer), Android 16 emulator.
- **Evidence labels:** PASS / FAIL / NOT TESTED.

## Approach

- **One password policy, three copies kept identical.** The API's Identity options are described rule by rule in
  `PasswordPolicy`, with the same copy in MVC and Kotlin. A UI test checks that the MVC and API copies produce the
  same message for every sample. Character classes are ASCII, as Identity counts them.
- **No raw server text.** MVC and Android show an API message only if it matches a reviewed allowlist (`ApiMessages`,
  the same list on both clients) or is the password sentence rebuilt from known parts. Anything else keeps the
  existing safe generic message. A test fails if an allowlisted message no longer exists in the API source.
- **Uncertain writes stay uncertain.** Reasons are shown only for definitive rejections (400/404/409), never for
  timeouts, 5xx or network failures.
- **Unchanged:** password policy, validation rules, authentication, lockout, rate limits, concurrency and
  idempotency.

## Findings

| # | Feature / platform | Scenario | Before | Root cause | After | Status | Regression test | Runtime |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Registration · MVC, Android, API | 12 matching characters, no uppercase or special character | "Check your name, email and password. Passwords must be at least 12 characters and match." | Clients checked length and matching only; every 400 mapped to one sentence; API returned one combined sentence | "Password must contain an uppercase letter and a special character, such as ! or #." | Fixed | Auth, ApiSecurity, UiTests, `RegisterViewModelTest`, `PasswordPolicyTest` | PASS browser + emulator |
| 2 | Registration · all | Several fields wrong at once | Only the first problem (Android) or one summary (MVC) | First-error-only validation | Every field named beside it | Fixed | same | PASS browser + emulator |
| 3 | Registration · MVC, Android | Requirements before submitting | Not shown (MVC); one hint line (Android) | No checklist | Live checklist that updates while typing, with spoken "done / still needed" | Fixed | UiTests render | PASS browser + emulator |
| 4 | Registration · Android | Password lost after an on-device check fails | Password cleared | Cleared on every submit | Kept until it is actually sent | Fixed | device step | PASS emulator |
| 5 | Change password · Android | Invalid entries | "Check the passwords and confirmation." | One generic check | Empty, unmet rules, mismatch and reuse named per field; server reason "Your current password is incorrect." | Fixed | `LiveFeaturesTest`, `LiveFailureMessageTest`, device step | PASS emulator |
| 6 | Change password · MVC, API | Wrong current, weak or reused new password | "Check your current password and new password requirements." | Generic messages in both services | Field messages beside each input | Fixed | Auth tests, UiTests | PASS browser |
| 7 | Change password · Android | Accented capital letter | Counted as uppercase on the device, rejected by the server | `Char.isUpperCase` (Unicode) vs Identity (ASCII) | Same ASCII rules as the server | Fixed | `PasswordPolicyTest` | — |
| 8 | API contracts | Framework defaults | "'ConfirmPassword' and 'Password' do not match.", "The field Password must be a string with a minimum length…" | Default DataAnnotations messages | Plain field messages | Fixed | ApiSecurity HTTP test | PASS |
| 9 | Amounts · API | Zero, negative, three decimals, too large | "Amount must be positive, within decimal(18,2), and have at most two decimal places." | One message for three rules | One message per rule | Fixed | FinanceTests | PASS |
| 10 | MVC forms | Transaction, goal, contribution, bill and budget amounts and fields | "The field Amount must be between 0.01 and 100000000.", "The CategoryId field is required.", one combined amount sentence | Default messages; no two-decimal check | Specific messages (`MoneyInput`); plain binder messages | Fixed | UiTests | PASS browser |
| 11 | MVC forms | Amount problem plus another missing field | Amount problem hidden until the other field was fixed | Rule in `IValidatableObject`, which runs only after all field rules pass | Rule on the field itself (`[MoneyText]`), so problems show together | Fixed (found in the browser run) | UiTests | PASS browser |
| 12 | MVC · categories, budgets, goals, bills, transactions | Duplicate category or budget, deleting a goal with history, stale version, future contribution | "The change conflicts with an existing record or a concurrent change…", "The API rejected those details…" | Clients kept the HTTP status only | The specific reviewed reason, e.g. "A budget already exists for this category and month." | Fixed | UiTests | PASS browser |
| 13 | Android · plan and account screens | Server rejections | Generic text | Error bodies never read | Capped error body read only for the allowlist (`FinanceResult.Failure.message`) | Fixed | `PersistentSessionReliabilityTest`, `ApiMessagesTest` | PASS emulator |
| 14 | Android · plan editor | Invalid goal or bill | "Check the name, amount, date and bill settings." | One generic check | Each problem named, e.g. "Enter a name. Amounts can have no more than two decimal places." | Fixed | device step | PASS emulator |
| 15 | MVC · session | Session revoked or site restarted | Sign-in page with no reason | Cookie challenge carried no reason | "Your session has expired. Please sign in again." | Fixed | — | PASS browser (real restart) |
| 16 | MVC · login | Empty fields | "Enter a valid email address and password." | One message | "Enter your email address. Enter your password." | Fixed | — | PASS browser |
| 17 | Android · password sentence | Several unmet rules | "characters,an uppercase letter" | Android trims leading and trailing spaces in resource strings | Quoted separator | Fixed (found on the device) | device step | PASS emulator |
| — | Login · all | Wrong password | Generic "couldn't sign you in" | — | Unchanged: no account enumeration | By design | existing | PASS |
| — | FX outage · MVC | Provider unavailable, rate review off | Generic "could not confirm the change" | 503 is an uncertain outcome | Unchanged on purpose: nothing is claimed | By design | — | PASS (closeout) |

## Results

| Suite | Result |
| --- | --- |
| .NET: Finance 441, Authentication 34, ApiSecurity 35, CategoryUniqueness 15, SchemaValidation 65, AdminProvisioning 40, MVC UiTests | PASS |
| SqlIntegration 16/16 and Hangfire 13/13 (real SQL Server) | PASS |
| Android unit, debug / release | 672 / 678, 0 failures |
| Android instrumented, full suite | 200 run, 0 failures after updating the one test that asserted the old generic message |
| Device steps (opt-in): registration, change password, plan editor | 3/3 PASS |
| MVC browser scenarios: registration, login, change password, transactions, categories, budgets, goals, contributions, session expiry, API outage | PASS |

## Limitations

- The Android transaction add/edit forms keep their existing feature-specific messages. They already
  validate amounts on the device before sending.
- A desktop screen reader and spoken TalkBack output were not used (as before).
