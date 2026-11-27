# Sanitized input-validation audit evidence

> Source: OWLI source baseline 94dbf1c (dev); task branch fix/input-validation-audit; docs/audits/OWLI_Input_Validation_Audit.md
> Collected: 2026-11-28
> Published: 2026-11-28

Direct API probes, manipulated MVC form posts and Android emulator form input against
an audit API/MVC on the disposable runtime catalog with synthetic accounts.

Reproduced before the fix:
- MVC transaction amount: "10,50" stored as 1050.00, "1e5" as 100000.
- Android budget limit: "10,50" read as 1050; goal, bill and contribution amounts accepted "1e5" and "+5".
- MVC budget, goal, contribution and bill amounts: unparseable text produced HTTP 500.
- The MVC converter rounded 10.555 to 10.56 and reported a service outage.
- API: bill frequency "1" and currency "ZZZ" were stored.
- API: control characters were accepted in names.
- API: serializer type names were returned in 400 bodies.
- API: a body over the size limit returned 500.

After the fix:
- Each case gives a specific 400 or field message, and SQL showed no rejected rows.
- A decimal comma saved 10.50 for an Android goal and budget.
- Regression suites passed. Production runtime was not verified.
