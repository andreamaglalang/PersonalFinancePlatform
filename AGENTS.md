# OWLI project instructions

These rules apply to Codex, Claude Code, all repository tasks, and any project
skills, plans, or automation added later. Read [the Git workflow](docs/DEVELOPMENT_WORKFLOW.md)
before starting work. Direct user instructions take precedence.

## Branch policy

- `dev` is the permanent development and integration branch and shared source of
  truth. Start every new task from the latest synchronized `dev` on a temporary,
  appropriately named branch. Do not create a task branch from `main`, the
  preserved handoff, or an old integration branch.
- `handoff/owli-20261125` is the consolidated original baseline and recovery
  reference. Initial documentation publication and creation of `dev` are complete.
  Preserve handoff; all subsequent work uses task branches from synchronized
  `dev`, including documentation and environment preparation.
- `main` is stable release code. Merge verified `dev` into `main` only with the
  owner's explicit approval. Never develop directly on `main`.
- `development/owli-stabilization`, `fix/*`, `feature/*`, `test/*`, and `chore/*` are temporary
  task branches. Integrate completed, verified work into `dev` through review.
  Do not automatically merge incomplete, failing, or unverified work.

## Every development task

1. Inspect working tree status and ownership; preserve existing changes.
2. Fetch remote state and synchronize local `dev` with `origin/dev` using a safe
   fast-forward. If missing, divergent, or blocked, investigate and report;
   never silently fall back to an older baseline.
3. Create a focused task branch from synchronized `dev`. During concurrent work,
   assign ownership and use separate branches and isolated Git worktrees.
4. Investigate, implement within the assigned scope, and perform relevant
   automated and runtime checks. Report what was actually tested and any limits.
5. Review correctness, regressions, security, and scope, then commit and push.
6. Check for conflicting work, integrate verified changes into `dev` through the
   appropriate review/merge process, and verify integrated behavior. Use the
   updated `dev` as the next task's baseline.

## Safety and coordination

- Never force-push, reset/rewrite existing branch history, discard changes,
  delete branches without authorization, or overwrite another agent's work.
- Avoid simultaneous edits to the same files and duplicate implementations;
  perform independent reviews where appropriate.
- Never publish secrets, private configuration, certificates/private keys,
  database files/backups, real financial data, or personal machine details.
  Keep security ignore rules and local environments intact.
- Preparation tasks do not authorize application changes, finance migrations or
  data changes, service restarts, or existing runtime configuration changes.
  The approved Phase 0 finalization permits only guarded creation/provisioning
  of dedicated local `OWLI_Hangfire` storage and disabled, private future-worker
  configuration. It does not authorize processing servers or job registration.
- Skills and plans must respect this workflow. A skill does not authorize
  bypassing branch policy, publishing secrets, or merging into `main`.

For current setup, architecture, issues, and verification, start at
[the documentation index](docs/README.md) and [the handoff guide](docs/handoff/README.md).
Older reports retain historical implementation claims; they do not override
this branch policy or constitute current verification.

## OWLI AI development standards

Follow [AI_DEVELOPMENT.md](docs/AI_DEVELOPMENT.md) for skill selection, academic
requirements, financial safeguards, agent ownership, and evidence labels.
Use only relevant skills; scale planning and tests to task risk. Project policy
supersedes conflicting optional skill workflows and inherited role templates,
subject to mandatory platform/security policy. Phase 0 configuration changes
stay on `chore/ai-agent-skills-setup` until reviewed and explicitly approved for
integration; do not begin the system audit or application work in that phase.

## Approved methodology and shared knowledge

Use relevant knowledge → Superpowers investigation/testing as needed → Ponytail
minimal complete correction → Impeccable only for UI → independent review and
verification → affected knowledge update → reviewed integration into `dev`.
Keep one plan and acceptance record per substantial task; do not invoke every
skill or repeat completed investigations for every task.

Read [PRODUCT.md](PRODUCT.md) for approved expected behavior,
[backend integration](docs/backend-integration.md) for observed contracts, and
[the single wiki index](docs/karpathy-wiki/wiki/index.md) for source-backed
knowledge. Current source defines implemented behavior; runtime evidence defines
verified behavior. LLM Wiki is knowledge management, distinct from Karpathy
coding guidelines. Its project root is `docs/karpathy-wiki/` (with `raw/` and
`wiki/` underneath); never initialize a second root-level wiki. Follow
[wiki rules](docs/karpathy-wiki/README.md); never copy private configuration or
financial records into raw sources or articles.

Default ownership: Codex handles API, Domain/Infrastructure, SQL/EF, financial
rules, authentication and the future worker. Claude handles MVC/Android,
Compose/Room client behavior, integration UI and accessibility. Both review,
test, coordinate contracts and maintain knowledge. This is flexible ownership,
not exclusive capabilities. Agree on shared-file ownership before concurrent
work; use separate worktrees and task branches from synchronized `dev`.

The approved target is a separate `PersonalFinance.Worker` .NET console host,
initially reusing `FinanceJobs`, stable recurring IDs and Manila schedules.
See [worker preparation](docs/worker-config/README.md). It is not implemented.
Do not change API hosting/DevJobsController, start processors, register jobs,
run finance migrations or generate the implementation roadmap during Phase 0.
The current setup PR requires final review/approval before merge into `dev`.
