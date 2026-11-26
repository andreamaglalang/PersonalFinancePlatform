# OWLI architecture and worker decision

> Sources: Project owner, 2026-11-26; OWLI application source, 2026-11-26
> Raw: [Approved requirements](../../raw/owli/2026-11-26-approved-requirements.md); [Source snapshot](../../raw/owli/2026-11-26-application-source.md)
> Updated: 2026-11-26

## Confirmed implementation

At `6687eb95ab3741a747da36d334aa3420c4ed7c23`, API startup calls
`AddInfrastructure`, then `AddFinanceJobs`. The enabled path registers a Hangfire
server and startup recurring registration inside the API. No independent worker
project exists. This is source evidence; effective runtime flags are not inferred.

Infrastructure depends on Domain and supplies SQL/financial services. MVC and
Android consume Web API contracts. The current integration reference is
[backend-integration.md](../../../backend-integration.md); it records profile and
reporting differences without declaring them runtime bugs. Approved expected
behavior lives in [PRODUCT.md](../../../../PRODUCT.md), not this observation page.

## Approved target and decision

An independent `PersonalFinance.Worker` .NET console host is mandatory; YAGNI does
not remove that academic requirement. Reuse Infrastructure/Domain, never the API
executable. Ordinary DI must not implicitly activate processing. Keep the
selected `FinanceJobs` family initially, preserving stable IDs and Manila
schedules. The alternative has different IDs/schedules and must not be activated
alongside it. Preserve job type/signature/serializer compatibility during cutover.

Keep detailed target responsibilities in [worker preparation](../../../worker-config/README.md)
rather than duplicating an implementation plan. HTTP current-user dependencies,
the API's direct Development job endpoint, summary policy and portable SQL tests
remain audit concerns. No architecture correction has been implemented.

## See Also

- [Financial invariants](financial-invariants.md)
- [Verification and decisions](verification-and-decisions.md)
