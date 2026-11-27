# OWLI — Input validation, form handling and data integrity audit (27–28 November 2026)

Branch `fix/input-validation-audit` from `dev` `94dbf1c`.
- **Environment:** disposable `OWLI_Runtime_20261126`; synthetic `@example.test` accounts only.
- **Services:** an audit API on port 7127 and an audit MVC on port 63078, both from the task worktree, with Hangfire off.
- **Clients:** Chrome driven by Puppeteer, and the Android 16 emulator `owli_api36_test` (debug build, with `adb reverse` pointing it at the audit API).
- **Untouched:** the owner's own services (API 7027, MVC 62978, the worker and the Pixel 9 emulator), production and Cloudflare.
- **Evidence labels:** PASS / FAIL / BLOCKED / NOT TESTED.

The Web API is the authoritative boundary. Every rule below is enforced there whatever the client does. MVC and Android repeat the rules only so users get an immediate, specific message.

## Method

1. **Static inventory.** Every request contract and every MVC and Android form field was mapped against the API attributes, the service checks and the database column types (table below).
2. **API probes.** 158 direct requests plus 15 registration requests covered:
   - malformed JSON, wrong types, unknown properties, unsupported enum values, invalid identifiers and dates;
   - oversized bodies, SQL-like and HTML text, replayed ClientIds and cross-account access.
   Each rejection's message was inspected for leaked internals, and SQL Server was checked afterwards.
3. **MVC probes.** 61 manipulated form posts went past the HTML input types and `maxlength`, using native `form.submit()`. Each post was checked in the rendered page and with a SQL row count. The Administrator budget and category forms were probed the same way.
4. **Android on the emulator.** A Compose instrumentation step types into the real goal, bill and budget forms. Results were verified in SQL Server.
5. **Fixes and regressions.** Each confirmed defect was reproduced, fixed at the root, given a regression test, and re-probed at runtime.

## Documented input policy

| Input | Policy (all platforms, enforced by the API) |
|---|---|
| **Money** | Positive, at most 2 decimals, at most 9,999,999,999,999,999.99 (`decimal(18,2)`). Never rounded, truncated or reinterpreted.<br>Typed text is digits with one optional decimal separator. Grouping separators, currency symbols, a leading `+`, exponents (`1e5`), `NaN` and `Infinity` are refused with a message.<br>Surrounding spaces and leading zeros are accepted (`000100` = 100).<br>**Android** also reads a single `,` as the decimal separator (`10,50` = 10.50) because some decimal keyboards produce it. **MVC** refuses a comma and says to use a decimal point.<br>A comma is never a thousands separator anywhere. |
| **Money (API JSON)** | A JSON number, or a numeric string with the same rules (`"100.50"` equals 100.50). JSON exponent numbers (`1e5`) are the same value and are accepted. Invalid literals are refused with a plain field message. |
| **Names** (full name, category, goal, bill) | Required, not whitespace-only, trimmed, length-limited (100 or 50). No control characters inside (line breaks, tabs, NUL); padding at the ends is trimmed first. Unicode letters, accents, apostrophes and hyphens are valid. HTML and SQL-like text is stored as text and encoded on output. |
| **Descriptions and notes** | Optional, at most 200 characters, stored exactly as sent and encoded on output. Control characters are not restricted (unchanged behaviour). |
| **Email** | Required, at most 256 characters, a syntactically valid address with no inner spaces. Trimmed; case-insensitive. Identity's user-name characters apply: ASCII letters and digits plus `. _ - + @`; other characters get a specific field message. Syntax is never presented as verification. Account-enumeration protections are unchanged. |
| **Passwords** | Unchanged policy: 12 to 128 characters, upper and lower case, a digit, a symbol and 4 distinct characters. Never trimmed or altered. Spaces count as characters. |
| **Dates** | ISO calendar dates. Impossible dates (Feb 30, Feb 29 in a non-leap year) are refused.<br>Unchanged business rules: transaction dates may be any valid date (Android's picker offers years 1–9999); contribution and payment dates cannot be later than today in Philippine time; bill first due dates and report months cover years 2000–9998; budget months must be the first of a month. |
| **Whole numbers** | Alert threshold is 1–100; reminder days are 0–30; page sizes are 1–100. Decimals, letters and values beyond `int` are refused. |
| **Selections** | Type is `Income`/`Expense` exactly; bill frequency is `Weekly`/`Monthly`/`Quarterly`/`Yearly` by name only. A currency must be offered by the rate provider. A category must be accessible, active for new records and of the matching type. Another member's records answer 404. |

## Defects found and fixed

All were reproduced at runtime on `dev` `94dbf1c` before the fix and re-probed afterwards.

| # | Platform | Defect (reproduction) | Root cause | Fix | Regression test | Runtime after fix |
|---|---|---|---|---|---|---|
| V1 | MVC Member · transaction create/edit | **`10,50` was stored as 1050.00; `1e5` as 100000; `1,000.00` as 1000.** These are silent transformations into different amounts. | The default `decimal` binder parses with the culture's thousands separators and exponents. | A `MoneyModelBinder` reads the amount exactly with the same rules as the other money fields. | `InputValidationChecks` (real MVC binder) | PASS: each refused with "Enter the amount as a number with a decimal point…"; 0 rows stored |
| V2 | Android · budget limit | **`10,50` was read as 1050** (commas were stripped as thousands separators). Add Transaction reads the same text as 10.50. | Separate parser in `parseLimit` | One device-wide reader (`typedAmount`) for every amount field | `BudgetsTest`, `AmountInputTest` | PASS: budget saved as 10.50 on the emulator (SQL `LimitAmount = 10.50`) |
| V3 | Android · goal target, bill amount, contribution | `1e5`, `1E+3` and `+5` were accepted and silently converted. | `String.toBigDecimalOrNull()` accepts exponents and signs. | Same shared reader | `AmountInputTest` | PASS: each named on the emulator; nothing sent |
| V4 | MVC Member · budget limit, goal target, contribution, bill amount; MVC Administrator · budget limit | Text such as `abc` or `1e5` showed the **"We could not complete this request" error page** (HTTP 500). | A computed `decimal.Parse(...)` property was evaluated by MVC model validation. | `[ValidateNever]` on the computed properties; the field rule names the problem. | `InputValidationChecks` (real MVC validator) | PASS: field message, HTTP 400, nothing stored |
| V5 | MVC Member · converter | `10.555` was **rounded to 10.56, sent, and then reported as "The reference-rate service is unavailable"**. An empty amount showed "The Amount field is required." | A lenient parse, `ToString("0.00")` on send, and an implicit-required default message | `MoneyInput` validation; explicit `Required` message | `InputValidationChecks` | PASS: "Amounts can have no more than two decimal places." / "Enter an amount." |
| V6 | API · recurring bills | Frequency `"1"` was accepted and stored as a schedule. | `Enum.TryParse` accepts numeric strings. | Match enum names only | `InputValidationTests` | PASS: 400 "Frequency must be Weekly, Monthly, Quarterly or Yearly." |
| V7 | API, MVC and Android · recurring bills | A bill in an unsupported currency (`ZZZ`) was stored and could never be paid. | No provider check on bill writes | API: the provider check on create and on currency change; the account currency and an unchanged currency need no call. MVC: the supported list. Android: `SupportedCurrencies`. | `InputValidationTests` | PASS: API 400; MVC field message; Android named, nothing sent |
| V8 | API, MVC and Android · names | **Control characters and line breaks were accepted** in registration full names, `PUT /api/auth/me`, and category, goal and bill names. The profile endpoint and the Android plan editor already refused them, so behaviour was inconsistent. | No shared rule | `SingleLineText` on the contracts and MVC models; Android registration and category editor checks | `InputValidationTests`, `ApiSecurityTests`, `InputValidationChecks`, `RegisterViewModelTest`, `CategoryViewModelsTest` | PASS: API 400 with field message; SQL 0 rows |
| V9 | API · every endpoint | Malformed JSON values returned serializer text, including .NET type names (`System.Nullable`1[System.Decimal]`), internal namespaces (`PersonalFinance.Infrastructure…`) and byte positions. Attribute defaults exposed regular expressions ("must match the regular expression '^(Income\|Expense)$'"). | The default `InvalidModelStateResponseFactory` and default attribute messages | `SafeValidationResponses` gives one plain message per JSON field; explicit messages on every contract attribute | `InputValidationTests` | PASS: e.g. `amount: This value is not recognised or is not in the expected format.` |
| V10 | API · write endpoints | A body over the 16 KB endpoint limit returned **500**. | `BadHttpRequestException` fell through to the generic handler. | Kestrel's own status (413, "The request is too large.") | `InputValidationTests` | PASS: 413 |
| V11 | API · registration | An email with an inner space, or with characters outside Identity's user-name set (for example an apostrophe), got the generic "Registration could not be completed." | Identity user-name errors were not mapped. | Field message "Enter a valid email address." or "Use an email address with only letters, numbers and . _ - + before the @." | `ApiSecurityTests` | PASS |
| V12 | Android · goal, bill and contribution forms | Amount and reminder fields opened the **alphabetic keyboard**. | No `keyboardOptions` | Decimal keyboard for amounts, number keyboard for reminder days, single line | Covered by the emulator step | PASS (emulator) |

**Previously verified behaviour, re-confirmed and unchanged:**
- **Money rules on the API:** zero, negative, three decimals and over-maximum amounts are refused with specific messages.
- **Idempotency:** a ClientId replay returns the original record with 200; reuse with a different body returns 409.
- **Concurrency:** If-Match is enforced on goals, and transaction version checks apply.
- **Cross-account access:** another member's transactions, categories, goals and bills give 404 or 400.
- **Text safety:** XSS, SQL-like and emoji text is stored as text and encoded on output.
- **Dates and pagination:** invalid dates, pagination, sorting and filter values are refused.
- **Duplicates:** duplicate category names (case and padding variants) and duplicate budget month+category are refused.
- **Overfunding:** legitimate savings overfunding is still accepted.
- **Account enumeration:** login gives the same message for an unknown account and a wrong password.
- **Passwords:** a trailing space makes the password differ; it is never trimmed.

## Input validation matrix

Status: PASS = verified at runtime after the fix; UNIT = automated only.

| Platform | Screen | Field | Accepted | Rejected | Defect | Status |
|---|---|---|---|---|---|---|
| API | all JSON writes | body / any field | valid JSON per contract | malformed JSON, wrong types, unknown properties (field message, no internals), over 16 KB (413) | V9, V10 | PASS |
| API | transactions | amount | 0.01 … 9,999,999,999,999,999.99, ≤2 decimals | 0, negative, 10.555, over max, `NaN`, `Infinity`, `000100` literal, `"abc"` | — | PASS |
| API | transactions | type / category / currency / date / description / clientId | Income/Expense; owned active matching category; provider-supported ISO code; valid date; ≤200 characters; non-empty GUID | lowercase or unknown type; 0, foreign or mismatched category; `php`/`ZZZ`/`PHPP`; Feb 30; 201 characters; missing/empty/malformed GUID | — | PASS |
| API | categories | name / type / colour | trimmed 1–50, Unicode | blank, 51 characters, control characters, duplicate, lowercase type, `red` | V8 | PASS |
| API | budgets | limit / threshold / month / category | money; 1–100; first of month; expense category | 0, 3 decimals; 0, 101, 50.5, 2³¹; 15th; income category; duplicate | — | PASS |
| API | goals / contributions | name / target / target date / amount / date / note | trimmed 1–100; money; valid date; money; ≤ today (PH); ≤200 | blank, control characters, 101; 0, −5; Feb 30; future date; 201 | V8 | PASS |
| API | bills / payments | name / amount / currency / frequency / reminder / first due / due date | trimmed 1–100; money; supported code; name; 0–30; 2000–9998; oldest outstanding | control characters; 0; `php`, `ZZZ`; `Daily`, `monthly`, `"1"`; −1, 31, 1.5; 1999; another date (409) | V6, V7, V8 | PASS |
| API | auth / account | full name / email / passwords / version | trimmed Unicode name; valid email; policy | control characters, 101; invalid, 257, inner space, unusable characters; weak, 129, mismatch | V8, V11 | PASS |
| API | queries | page / pageSize / sort / dates / month / FX amount | per contract | 0, 101, `abc`, unknown sort, Feb 30, month 13, mid-month, 1999, FX `abc`/`0`/`10.555` | — | PASS |
| MVC Member | Add/Edit transaction | amount | `0.50`, `100.50`, ` 5 `, `000100` | `abc`, `100abc`, `1e5`, `10,50`, `1,000.00`, `+5`, `₱100`, `10..50`, `NaN`, empty, 0, −1, 10.555, >100,000,000 | V1 | PASS |
| MVC Member | Add/Edit transaction | type / category / currency / date / description | — | `Transfer`, `ZZZ`, `php`, 0, foreign category, Feb 30, garbage, empty, 201 characters | — | PASS |
| MVC Member | Budget create | limit / threshold / month | money text | `abc`, `1e5` (was 500), `10,50`, 0, 10.555; 0, 101, 50.5, `abc`, 99999999999; 2032-13, garbage | V4 | PASS |
| MVC Member | Goal / contribution | name / target / date | Unicode name | blank, control characters (was stored), 101; `1e5` (was 500), `10,50`, 0, 20 digits; Feb 30 | V4, V8 | PASS |
| MVC Member | Bill | name / currency / frequency / reminder / amount / first due | — | control characters (was stored); `ZZZ` (was stored); `1`, `Daily`; −1, 31, 1.5; `1e5` (was 500); Feb 30 | V4, V7, V8 | PASS |
| MVC Member | Category | name | HTML stored as text | blank, control characters (was stored), 51 | V8 | PASS |
| MVC Member | Converter | amount | `1.50` | `abc`, `1e5`, `10,50`, 0, −1, `10.555` (was "service unavailable"), empty (was a framework message) | V5 | PASS |
| MVC Member | Register / profile | full name | Unicode | control characters | V8 | UNIT (`InputValidationChecks`); API boundary PASS |
| MVC Administrator | Budget create | limit | — | `abc`, `1e5` (was 500), `10,50`, 0, 10.555 | V4 | PASS |
| MVC Administrator | Default category | name | — | control characters, blank, 51 | V8 | PASS |
| Android | Goal editor | target | `10,50` → 10.50 | `1e5`, `+5`, `1,250.50`, `₱100` | V3 | PASS (emulator + SQL) |
| Android | Bill editor | name / currency / reminder | — | two-line name, `ZZZ`, `1.5` (all named, nothing sent) | V7 | PASS (emulator) |
| Android | Budget create | limit | `10,50` → 10.50 (was 1050) | `1,250.50`, `1e5`, `+5`, `₱100` | V2 | PASS (emulator + SQL); rejections UNIT |
| Android | Add/Edit transaction | amount | ` 1 `, `.5`, `000100`, `12,34` | `1e3`, `+5`, `1,234.56`, `10..50`, `₱100`, `1 000` | — | UNIT (`AddTransactionViewModelTest`) |
| Android | Register / category editor | name | Unicode | control characters | V8 | UNIT |

**Inventory: 219 user-editable inputs.** The MVC counts are distinct form field names per view; the API count is every request and query property.

| Platform | Inputs | Breakdown |
|---|---|---|
| API | 85 | 28 request and query contracts, plus `If-Match` on goal writes |
| MVC Member | 54 | Transaction 7; activity filters 6; budgets 7; goal 3; contribution 3 + edit 3; bill 8; category 5; account 4; currency preferences 3; converter 3; Insights month 1; notifications filter 1 |
| MVC sign-in and registration (shared) | 6 | Login 2; register 4 |
| MVC Administrator | 17 | Categories with filters, budgets, reports month |
| Android | 57 | Login 2; register 4; account 4; currency preferences 3; add transaction 7; edit transaction 6; activity filters 5; converter 3; budgets 3; goal editor 3; contribution 3; bill editor 8; category editor 4; Insights month 1; notifications filter 1 |

**Exercised at runtime:** every API write contract field and query parameter. In MVC Member: the transaction, budget, goal, bill, category and converter fields. In MVC Administrator: the budget and default-category fields. On Android: the goal, bill and budget fields. Registration names, contribution fields, the Android transaction amount and the Android category editor have automated coverage only (UNIT).

## Regression results (task branch)

| Suite | Result |
|---|---|
| FinanceTests | 447 passed, 0 failed (6 new) |
| AuthenticationTests | 34 / 34 |
| ApiSecurityTests | 36 / 36 (1 new) |
| CategoryUniquenessTests | 15 / 15 |
| SchemaValidation | 65 / 65 |
| AdminProvisioningTests | 40 / 40 |
| AdminMvc.UiTests | all pass (new `InputValidationChecks`) |
| SqlIntegrationTests (real SQL Server) | 16 / 16 |
| HangfireTests (real SQL Server, disposable storage) | 14 / 14 |
| Android debug unit tests | 680 passed, 0 failed (after input restriction) |
| Android release unit tests | 686 passed, 0 failed (after input restriction) |
| Android instrumented suite (emulator, API 36) | 206 run, 0 failed, 25 skipped (opt-in device steps, run separately: PASS) |
| Android device step `inputValidationThroughUi` | PASS |

## Input restriction while typing (follow-up to PR #34)

Validation alone let users type `abc` into an amount and find out on save. Every money field and every whole-number
field now refuses an invalid edit as it happens. The rules (server, client and database) are unchanged; this only
supplements them.

**Rule.** A key, a paste or a drop is accepted only if the field's text would still be a valid shape. Otherwise the
whole edit is refused: the field keeps its previous text, the caret stays where it was, and one concise reason appears
under the field (announced politely). Characters are never stripped, so pasting `100abc` leaves the field unchanged
and never becomes `100`. The only change ever made to a paste is removing surrounding spaces (` 250.75 ` becomes
`250.75`), which does not alter the amount. Deleting is always allowed, and natural intermediate states (empty, `0`,
`0.`, `.5`) are accepted.

| Field kind | Allowed while typing | Refused, with reason |
|---|---|---|
| Money (MVC) | digits, one `.`, at most 2 decimals, at most 16 integer digits | letters, `e`, `+`, `-`, symbols and spaces: "Amounts use digits and one decimal point only."; `,`: "Use a decimal point (.) with no separators."; a second point; a third decimal; a 17th integer digit |
| Money (Android) | the same, with `.` or `,` as the one decimal separator | the same reasons ("…one decimal separator…") |
| Whole numbers (alert threshold, reminder days, Administrator page and page size) | ASCII digits, up to the field's length (3 / 2 / 9 / 3) | anything else: "Use whole numbers only (digits 0–9)."; extra digits: "That number is too long." |

**Where:**
- **MVC Member:** the Add/Edit transaction amount, budget limit (create and edit), alert threshold (create and edit), goal target, contribution amount (add and edit), bill amount, reminder days and converter amount.
- **MVC Administrator:** budget limit, alert threshold, and category page and page size.
- **Android:** the Add/Edit transaction amount, budget limit (create and edit), goal target, bill amount, contribution amount, reminder days and converter amount. Android's alert threshold is a choice of buttons, so it has no free-text input.

**Implementation:**
- **MVC:** the transaction, converter and whole-number inputs changed from `type="number"` to `type="text"` with `inputmode="decimal"` or `inputmode="numeric"`. Browsers let a number input accept `e`, `+` and `-` and hide the typed text from scripts. A `beforeinput` handler in `owli-ui.js` covers typing, paste and drop. An `input` fallback restores the last valid text after autofill or an IME composition that cannot be cancelled.
- **Android:** `amountEdit` and `wholeEdit` wrap each field's `onValueChange`. Decimal and number keyboards stay in place.

**Verification:**
- **MVC (Chrome via Puppeteer):** 15 restricted MVC inputs, all PASS. Real key events: `page.type` and `keyboard.press`, which drive Chrome's physical-keyboard path. Real clipboard pastes: the system Paste command. Each MVC field was checked for:
  - typing `100a.5x.55-e5,` gives `100.55`;
  - `0` → `0.` → `0.5`;
  - a digit inserted mid-text with the arrow keys is accepted;
  - pastes of `100abc`, `1e5`, `-25`, `1,250.50`, `10.555` and `₱100` are refused whole with a reason, and ` 250.75 ` pastes as `250.75`;
  - whole numbers: typing `1.5-e` gives `15`, extra digits are refused, and a paste of `50.5` is refused.
  
  With the key handling bypassed, the server still answers `100abc` with HTTP 400.
- **Android (emulator `owli_api36_test`):** `amountInputRestrictionThroughUi` and `transactionAndConverterAmountRestrictionThroughUi` cover the goal target, Add Transaction amount, converter amount and bill reminder days:
  - software-keyboard keys one at a time: `100a.5x.55-e` gives `100.55`;
  - clipboard paste (the Paste action) of `100abc`, `1e5`, `-25`, `1,250.50` and `10.555` is refused whole; ` 250.75 ` gives `250.75`;
  - hardware-keyboard key events (`input text`, one key at a time): `12a.5.55` gives `12.55`;
  - reminder `1.5x` gives `15`, then a third digit is refused.
- **Unit tests:** `AmountInputTest.typingAndPastingRefuseTheWholeEditInsteadOfStrippingCharacters` and `InputValidationChecks` §5. The latter fails if any money or whole-number input loses its restriction or returns to `type="number"`.

**Limitation.** On Android, a burst of hardware key events injected within a single frame (much faster than human typing) drops the keys that follow a refused key in the same burst. For example, `input text 12a.5.55` sent at once leaves `12`. The reason and the shortened text stay visible, and nothing can be saved silently. At human pace every key is handled correctly.

## Decisions for the owner

1. **MVC transaction cap.** The MVC transaction form caps amounts at 100,000,000; the API and Android allow up to 9,999,999,999,999,999.99. An Android-created transaction above 100 million can be read on MVC but not re-saved there without lowering it. The cap is an earlier deliberate choice with its own test, so it was **left unchanged**. Recommendation: align MVC with the API maximum.
2. **Decimal comma on MVC.** Android reads `10,50` as 10.50; MVC refuses it with a message. Neither transforms the value. Accepting a single decimal comma on MVC as well would make the valid inputs identical.
3. **Emails outside Identity's ASCII user-name characters** (apostrophes, non-ASCII local parts) are valid addresses but cannot register. They now get an accurate message. Widening `AllowedUserNameCharacters` is an authentication-policy change and was not made.
4. **Date ranges.** Transaction dates and budget months accept any calendar year (0001–9999), matching Android's picker, while bills and reports use 2000–9998. No new restriction was added; a common range would be a business-rule decision.
5. **Control characters in descriptions and notes** remain accepted and stored exactly. Names are now single-line.

## Remaining limitations

- MVC Member registration and profile name rules are verified by unit tests and at the API boundary, not by a browser run.
- The Android Add/Edit transaction amount and category editor changes are covered by unit tests; the emulator step covered goals, bills and budgets.
- The MVC converter keeps its 1,000,000,000 reference-quote cap, while Android's converter allows the full money range. The converter is read-only.
- No production, Cloudflare or operational Hangfire storage was touched. Production runtime behaviour is **not** verified by this audit.
- Synthetic records from the probes remain in the disposable runtime catalog, including three `ZZZ` bills that were the pre-fix reproductions. The one synthetic default category created by an Administrator probe was deleted.
