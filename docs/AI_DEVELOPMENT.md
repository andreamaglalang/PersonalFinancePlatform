# OWLI shared AI development standards

This is the single project-specific source for Claude Code, Codex, and ChatGPT
coordination. [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md), and the
[Git workflow](DEVELOPMENT_WORKFLOW.md) remain in effect. The goal is to
stabilize and complete OWLI, preserving working implementations rather than
rebuilding the system. Phase 0 sets up this environment only; audit, final
roadmap, fixes, migrations, redesign, and deployment are subsequent work.

## Priority and engineering discipline

Subject to mandatory platform and security policies, resolve guidance in this
order: explicit user/academic requirements; security, financial integrity and
correctness; verified architecture/business rules; shared OWLI instructions;
task-relevant skill guidance; general agent preferences.

State assumptions and observable success criteria, trace the affected flow,
reuse existing code/platform capabilities, and make the smallest complete
change. Avoid speculative abstractions, dependencies, adjacent cleanup, repeated
investigations, and duplicate documentation. Preserve working behavior. Simplify
complexity introduced by your own changes. A deadline never waives correctness.

Skills complement these rules; they do not override branch policy or authorize
an operation. Check applicability, then load only the useful workflow/reference.
For a straightforward correction, a short statement of scope plus meaningful
verification is enough. Do not impose a lengthy brainstorming/design ceremony
when the requirement is already clear. Use red/green regression tests for bugs
and meaningful risk-based tests for new logic; do not mirror trivial edits with
unnecessary tests. Do not repeatedly rerun broad checks after they pass unless
new evidence warrants it. Keep essential financial/auth/database/sync tests.

## Lightweight skill selection

| Task | Relevant guidance | Minimum evidence |
| --- | --- | --- |
| Bug fix | Superpowers systematic debugging; Karpathy/Ponytail simplicity | Reproduction, root cause, focused regression and affected flow |
| Feature | Karpathy or Ponytail; Superpowers planning/TDD when nontrivial | Acceptance criteria, meaningful behavior checks, affected callers |
| Architecture correction | Superpowers investigation/planning/review; preserve existing design | Concrete failure/constraint, alternatives only as needed, regression coverage |
| UI/UX | Impeccable targeted critique/polish/accessibility; scoped engineering | Rendered checks on target sizes and states; preserve OWLI brand |
| Authentication/security | Superpowers debugging/review/verification | Trust boundaries, ownership/roles, session invalidation, adversarial cases |
| Database | Superpowers planning/debugging/review; explicit target approval | Schema/transaction/concurrency analysis; disposable target only when authorized |
| Cross-platform integration | Superpowers investigation/verification; one owner per flow | Android → API → database → MVC and reverse; offline/retry/account-switch checks |
| Testing/verification | Superpowers verification before completion | Fresh command/results plus runtime limits; no inference from build alone |
| Code review | Ponytail review or Superpowers review | Connected code, correctness/security/regressions, findings with evidence |

Karpathy and Ponytail overlap: use one concise engineering discipline, rather
than executing redundant full checklists. Superpowers supplies structure for
risk/complexity. Impeccable applies only to interface work; OWLI screens are
financial task interfaces, not a request for a decorative redesign. Its web
live-mode/browser/CSS instructions are not Kotlin APIs: use native Android
references and Compose/Material/accessibility equivalents. Preserve lavender
branding, existing design tokens, names, business copy, and cross-client
consistency. No complete redesign or replacement visual system without explicit
approval and technical justification. Phase 0 does not run `init`, `document`,
UI detectors, or live editing against application files.

## Financial and academic requirements for later phases

The stack is .NET 10 MVC/Web API, EF Core/SQL Server, Identity/JWT, Android
Kotlin/Compose with Room, Frankfurter FX, and Hangfire. Existing implementation
is evidence to investigate, not a license to infer completeness.

- Cover income/expenses, history, categories, budgets and validation, savings
  goals/contributions, recurring bills/payment records, reports/summaries,
  currency preferences/foreign-currency transactions, and notification inbox.
- Android requires secure authentication/API access and reliable Room offline
  recording, synchronization, retry and recovery. Do not infer conflict safety
  from the existence of Room or a successful offline test.
- MVC Member is a complete web counterpart of Android, with functional parity,
  responsive/consistent branding, shared accounts and business rules, and
  bidirectional synchronization through the common API/database. Preserve the
  separate Administrator functionality: budget/category administration,
  financial reports and appropriate role-based access.
- Protect money precision/rounding, historical FX rates, persistence,
  transactions, data ownership, authorization, concurrency, idempotency and
  ambiguous delivery/retry behavior. Shortcuts must never weaken these.
- **Academic Hangfire requirement (user supplied): run the background job server
  in a separate console worker, rather than inside the Web API.** Upcoming bill
  reminders, monthly financial summaries and budget threshold alerts are required.
  Current handoff describes in-API hosting; preserve that evidence as historical
  implementation, not acceptance of the required architecture. Investigate the
  mismatch during the audit. Do not refactor, activate jobs or change storage in
  Phase 0, and never run both retained job families together.
- Schema changes, migrations, provisioning, live financial writes, credential or
  certificate regeneration, runtime configuration and deployment remain outside
  Phase 0. Do not access the existing SQL container/database merely to test skills.

## Collaboration and evidence

Both coding agents can implement, investigate, debug, test, document and review.
Assign based on context, complexity and risk; do not enforce fixed roles or
provider/model aliases inherited from unrelated global templates. Preserve
accounts and safeguards. Use a concise handoff: task, owner, branch/worktree,
base SHA, files owned, dependencies/risks, changes, checks and unresolved issues.
High-risk financial, auth, migration or sync work gets independent review where
practical. The reviewer must not silently become a second implementation owner.

For concurrency, each owner starts a distinct task branch/worktree from the
latest synchronized `dev`; agree on disjoint file ownership and integration
order before editing. Integrate reviewed, verified work through `dev`, never
`main` or preserved handoff. A separate review worktree should use the candidate
commit without changing the author's working directory. Do not delete existing
branches/worktrees or discard changes as routine cleanup.

ChatGPT coordinates prompts/plans, documentation review and independent GitHub
verification. It cannot inspect uncommitted local work or assume installed
skills exist elsewhere. Publish only sanitized, reviewed project evidence; note
the repository/ref/commit for every handoff. Local CLI GitHub authentication and
plugin authorization are independent; never silently replace accounts or assume
that plugin writes resolve CLI push permissions.

Use these labels precisely: **Implemented** (source exists), **Suspected issue**
(hypothesis), **Confirmed issue** (reproduced/evidenced), **Fixed** (change made),
**Verified** (specific fresh checks support it), **Blocked** (named dependency),
**Not tested** (no execution evidence). Classify setup checks as PASS, FAIL,
BLOCKED or NOT TESTED. Builds/offline checks alone do not prove runtime behavior.
Keep historical handoff issues distinct from independently confirmed findings.

The internal completion target is Friday night, 27 November 2026, with Saturday
night, 28 November 2026 reserved for final testing/unexpected issues (Asia/Manila).
Prioritize critical-path root causes, early cross-client integration, reuse and
meaningful regression checks. This is a prioritization constraint, not a final
implementation roadmap or permission to skip safety.

## Skill execution boundaries

Use the verified upstreams/versions and platform-specific installation methods
in [Phase 0 setup](PHASE0_AI_SETUP.md). Never overwrite an instruction file with
an upstream installer example. Do not vendor downloaded third-party code,
caches, authentication data or global settings into this public repository.

Keep normal approval/sandbox protections. Do not enable unsafe live-copy agent
fallbacks, bypass flags, optional external telemetry/webhooks, or unreviewed
command hooks. Impeccable may download a checksum-verified engine on first use;
review that separately when an interface task actually needs it. Ponytail's
optional board/webhook is not required for engineering discipline. Existing
unrelated global settings are preserved, not silently trusted or rewritten.
